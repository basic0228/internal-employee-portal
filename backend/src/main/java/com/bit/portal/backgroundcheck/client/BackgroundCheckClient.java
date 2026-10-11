package com.bit.portal.backgroundcheck.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** One measured application attempt; retry/polling decisions belong to the caller. */
@Component
@Profile("portal-dev")
public class BackgroundCheckClient {
    public static final String PRODUCTION = "https://54capvm12g.execute-api.ap-northeast-2.amazonaws.com";
    private final URI base;
    private final boolean externalEnabled;
    private final ObjectMapper json;
    private final Semaphore slots = new Semaphore(3, true);
    public BackgroundCheckClient(ObjectMapper json,
            @Value("${background-check.base-url:https://54capvm12g.execute-api.ap-northeast-2.amazonaws.com}") String url,
            @Value("${background-check.external-enabled:false}") boolean externalEnabled) {
        this.json = json; this.base = URI.create(url); this.externalEnabled = externalEnabled;
        boolean production = PRODUCTION.equals(url);
        boolean local = "http".equals(base.getScheme()) && Set.of("127.0.0.1", "localhost").contains(base.getHost())
            && base.getPort() > 0 && (base.getPath().isEmpty() || "/".equals(base.getPath()));
        if (!production && !local) throw new IllegalArgumentException("Unsupported API destination");
    }
    public String source() { return PRODUCTION.equals(base.toString()) ? "EXTERNAL" : "LOCAL_MOCK"; }
    public boolean permitted() { return !"EXTERNAL".equals(source()) || externalEnabled; }
    public Transport open(int connectMs) {
        return new Transport(HttpClient.newBuilder().connectTimeout(Duration.ofMillis(connectMs))
                .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build());
    }
    public final class Transport implements AutoCloseable {
        private final HttpClient http;
        private Transport(HttpClient http) { this.http = http; }
        public Reply exchange(String endpoint, String employeeId, String checkId, JsonNode postBody, String key,
                int requestMs, long deadlineNanos, BooleanSupplier cancelled) {
            if (!permitted()) throw new IllegalStateException("External calls disabled");
            long queued = System.nanoTime(); boolean acquired = false; CompletableFuture<HttpResponse<byte[]>> future = null;
            var info = new AtomicReference<HttpResponse.ResponseInfo>();
            long start = 0; Instant at = null;
            try {
                while (!(acquired = slots.tryAcquire(50, TimeUnit.MILLISECONDS))) {
                    if (cancelled.getAsBoolean() || System.nanoTime() >= deadlineNanos) return Reply.notStarted();
                }
                if (cancelled.getAsBoolean() || System.nanoTime() >= deadlineNanos) return Reply.notStarted();
                int allowed = (int) Math.max(1, Math.min(requestMs, TimeUnit.NANOSECONDS.toMillis(deadlineNanos-System.nanoTime())));
                String path = switch (endpoint) {
                    case "LIST" -> "/background-checks?employeeId=" + safeId(employeeId);
                    case "DETAIL" -> "/background-checks/" + safeId(checkId);
                    case "CREATE" -> "/background-checks";
                    default -> throw new IllegalArgumentException("Unknown endpoint");
                };
                var builder = HttpRequest.newBuilder(URI.create(base.toString().replaceAll("/$", "") + path))
                        .header("X-Candidate-Key", key).timeout(Duration.ofMillis(allowed));
                if ("CREATE".equals(endpoint)) builder.header("Content-Type", "application/json").POST(
                        HttpRequest.BodyPublishers.ofString(json.writeValueAsString(postBody)));
                else builder.GET();
                start = System.nanoTime(); at = Instant.now();
                future = http.sendAsync(builder.build(), response -> {
                    info.set(response);
                    return limitedBody(262144);
                });
                long attemptDeadline = Math.min(deadlineNanos, start + TimeUnit.MILLISECONDS.toNanos(allowed));
                HttpResponse<byte[]> response;
                while (true) {
                    if (cancelled.getAsBoolean()) throw new CancellationException();
                    long left = attemptDeadline-System.nanoTime();
                    if (left <= 0) throw new TimeoutException();
                    try { response = future.get(Math.min(50, Math.max(1, TimeUnit.NANOSECONDS.toMillis(left))), TimeUnit.MILLISECONDS); break; }
                    catch (TimeoutException ignored) { if (System.nanoTime() >= attemptDeadline) throw ignored; }
                }
                double elapsed = ms(System.nanoTime()-start); // Stop before JSON parsing.
                Instant endedAt = Instant.now();
                JsonNode body = null;
                try { body = json.readTree(response.body()); } catch (Exception ignored) { /* no payload logging */ }
                return new Reply("HTTP_RESPONSE", response.statusCode(), elapsed, ms(start-queued), at, endedAt,
                        response.version().name(), response.body().length, retry(response.headers(), body), body, null);
            } catch (Exception failure) {
                if (future != null) future.cancel(true);
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                Throwable cause = failure;
                while ((cause instanceof ExecutionException || cause instanceof CompletionException) && cause.getCause()!=null) cause=cause.getCause();
                String outcome = cause instanceof HttpConnectTimeoutException ? "CONNECT_TIMEOUT"
                    : cause instanceof HttpTimeoutException || cause instanceof TimeoutException ? "REQUEST_TIMEOUT"
                    : cause instanceof CancellationException || cause instanceof InterruptedException ? "CANCELLED" : "NETWORK_ERROR";
                var observed = info.get();
                return new Reply(start == 0 ? "NOT_STARTED" : outcome, observed == null ? null : observed.statusCode(),
                        start == 0 ? 0 : ms(System.nanoTime()-start), start == 0 ? ms(System.nanoTime()-queued) : ms(start-queued),
                        at, Instant.now(), observed == null ? null : observed.version().name(), null,
                        observed == null ? Retry.empty() : retry(observed.headers(), null), null,
                        cause instanceof BodyLimitException ? "BODY_LIMIT" : null);
            } finally { if (acquired) slots.release(); }
        }
        @Override public void close() { http.shutdownNow(); }
    }
    public record Reply(String outcome, Integer httpCode, double elapsedMs, double queueWaitMs,
            Instant startedAt, Instant endedAt, String httpVersion, Integer responseBytes, Retry retry,
            JsonNode body, String transportIssue) {
        static Reply notStarted() { return new Reply("NOT_STARTED", null, 0, 0, null, Instant.now(), null, null, Retry.empty(), null, null); }
        @Override public String toString() { return "Reply[outcome="+outcome+", payload=REDACTED]"; }
    }
    public record Retry(String header, Long headerMs, Long bodyMs, boolean invalidHeader, boolean conflict) {
        static Retry empty() { return new Retry(null,null,null,false,false); }
        public Long preferredMs() { return headerMs != null ? headerMs : bodyMs; }
    }
    public static Retry retry(HttpHeaders headers, JsonNode body) {
        String raw = headers.firstValue("Retry-After").orElse(null);
        Long headerMs = null; String safe = null; boolean invalid = false;
        if (raw != null) {
            try {
                if (raw.length()>80) throw new IllegalArgumentException();
                if (raw.matches("[0-9]+")) headerMs = Math.multiplyExact(Long.parseLong(raw),1000);
                else headerMs = Math.max(0, Duration.between(Instant.now(), ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toMillis());
                safe = raw;
            } catch (RuntimeException error) { invalid = true; }
        }
        Long bodyMs = null;
        if (body != null && body.path("retryAfter").isIntegralNumber() && body.path("retryAfter").canConvertToLong()) {
            long seconds=body.path("retryAfter").asLong();
            if (seconds>=0 && seconds<=Long.MAX_VALUE/1000) bodyMs=seconds*1000;
        }
        return new Retry(safe,headerMs,bodyMs,invalid,headerMs!=null && bodyMs!=null && !headerMs.equals(bodyMs));
    }
    private static String safeId(String id) {
        if (id==null || !id.matches("[A-Za-z0-9._-]{1,128}")) throw new IllegalArgumentException("Invalid target");
        return id;
    }
    private static double ms(long nanos) { return nanos/1_000_000.0; }
    private static HttpResponse.BodySubscriber<byte[]> limitedBody(int max) {
        var delegate=HttpResponse.BodySubscribers.ofByteArray();
        return new HttpResponse.BodySubscriber<>() {
            int size; Flow.Subscription subscription;
            public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
            public void onSubscribe(Flow.Subscription value) { subscription=value; delegate.onSubscribe(value); }
            public void onNext(List<ByteBuffer> buffers) {
                for (var buffer:buffers) size+=buffer.remaining();
                if (size>max) { subscription.cancel(); delegate.onError(new BodyLimitException()); }
                else delegate.onNext(buffers);
            }
            public void onError(Throwable error) { delegate.onError(error); }
            public void onComplete() { delegate.onComplete(); }
        };
    }
    private static class BodyLimitException extends RuntimeException { }
}
