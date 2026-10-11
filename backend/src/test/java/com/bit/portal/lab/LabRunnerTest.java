package com.bit.portal.lab;

import com.bit.portal.backgroundcheck.client.BackgroundCheckClient;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.*;

class LabRunnerTest {
    MockBackgroundApi api; ObjectMapper json; BackgroundCheckClient client; LabRunner runner;
    @BeforeEach void setup() throws Exception {
        api=new MockBackgroundApi(0);json=new ObjectMapper().findAndRegisterModules();
        client=new BackgroundCheckClient(json,api.baseUrl(),false);runner=new LabRunner(client,json,true,true);
    }
    @AfterEach void close() { api.close(); }
    LabRequest request(LabRequest.Type type,int repeats,int concurrency,int timeout) {
        var endpoint=type==LabRequest.Type.CREATE_POLL || type==LabRequest.Type.REPEAT_POST?LabRequest.Endpoint.CREATE:LabRequest.Endpoint.DETAIL;
        return new LabRequest("PRIVATE_TEST_KEY",type,endpoint,"EMP-001","CHK-known","PRIVATE_FIRST","PRIVATE_LAST","1990-01-01",
            repeats,concurrency,3000,timeout,10000,0,10,3,20,true);
    }
    JsonNode execute(LabRequest r) throws Exception {
        var out=new ByteArrayOutputStream();runner.execute(runner.reserve(1,r),r,out);
        String payload=out.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(payload).doesNotContain("PRIVATE_TEST_KEY","PRIVATE_FIRST","PRIVATE_LAST","MOCK_PRIVATE_NAME","criminalRecord","1990-01-01","CHK-known","EMP-001");
        var events=payload.lines().map(line->{ try {return json.readTree(line);} catch(Exception e) { throw new RuntimeException(e); } }).toList();
        assertThat(events.get(0).path("type").asText()).isEqualTo("start");
        assertThat(events.get(0).path("data").path("run").path("state").asText()).isEqualTo("RUNNING");
        assertThat(events.getLast().path("type").asText()).isEqualTo("result");
        return events.getLast().path("data");
    }
    @Test void sequentialGetProducesOnlyRealMeasuredSamples() throws Exception {
        var result=execute(request(LabRequest.Type.GET_BASELINE,3,1,1000));
        assertThat(result.path("attempts").size()).isEqualTo(3);assertThat(api.calls.get()).isEqualTo(3);
        assertThat(result.path("run").path("source").asText()).isEqualTo("LOCAL_MOCK");
        assertThat(result.path("attempts").get(0).path("httpCode").asInt()).isEqualTo(200);
        assertThat(result.path("attempts").get(0).path("elapsedMs").asDouble()).isGreaterThan(0);
    }
    @Test void concurrencyUsesSameConditionsAndNeverExceedsServerCap() throws Exception {
        api.mode="CONCURRENCY";
        var result=execute(request(LabRequest.Type.GET_CONCURRENCY,3,3,1000));
        assertThat(result.path("attempts").size()).isEqualTo(9);assertThat(result.path("phases").size()).isEqualTo(3);
        assertThat(api.maxInflight.get()).isBetween(2,3);
    }
    @Test void postThenPollingSeparatesBusinessTimeFromHttpTime() throws Exception {
        var result=execute(request(LabRequest.Type.CREATE_POLL,1,1,1000));
        assertThat(api.posts.get()).isEqualTo(1);assertThat(result.path("attempts").size()).isEqualTo(2);
        assertThat(result.path("workflows").get(0).path("state").asText()).isEqualTo("TERMINAL");
        assertThat(result.path("workflows").get(0).path("businessDurationMs").asLong()).isGreaterThanOrEqualTo(1000);
        assertThat(result.path("workflows").get(0).has("observedPendingToFinalMs")).isTrue();
    }
    @Test void repeatedPostCountsHistoryAndReturnedIdentitiesWithoutPublishingIds() throws Exception {
        var result=execute(request(LabRequest.Type.REPEAT_POST,1,1,1000));
        assertThat(api.posts.get()).isEqualTo(2);assertThat(api.calls.get()).isEqualTo(4);
        assertThat(result.path("postComparison").path("result").asText()).isEqualTo("DISTINCT_CHECK_IDS");
        assertThat(result.path("postComparison").path("newHistoryCount").asInt()).isEqualTo(2);
    }
    @Test void timedOutPostIsNotRepostedAndRemainsUnknown() throws Exception {
        api.mode="SLOW_POST";
        var result=execute(request(LabRequest.Type.REPEAT_POST,1,1,60));
        assertThat(result.path("postComparison").path("result").asText()).isEqualTo("UNKNOWN_NO_REPOST");
        Thread.sleep(400);assertThat(api.posts.get()).isEqualTo(1);assertThat(api.calls.get()).isEqualTo(3);
    }
    @Test void bodyStallIsBoundedEvenAfterHeadersArrive() throws Exception {
        api.mode="BODY_STALL";
        // Leave headroom for JVM/OS scheduling; the body deliberately stalls far beyond this budget.
        var result=execute(request(LabRequest.Type.GET_BASELINE,1,1,500));
        assertThat(result.path("attempts").get(0).path("outcome").asText()).isEqualTo("REQUEST_TIMEOUT");
        assertThat(result.path("attempts").get(0).path("httpCode").asInt()).isEqualTo(200);
        assertThat(result.path("attempts").get(0).path("elapsedMs").asDouble()).isLessThan(1800);
    }
    @Test void overloadPreservesRetryAfterConflictAndStopsFurtherRequests() throws Exception {
        api.mode="OVERLOAD";
        var result=execute(request(LabRequest.Type.GET_BASELINE,5,1,4000));
        assertThat(api.calls.get()).isEqualTo(1);
        assertThat(result.path("attempts").get(0).path("outcome").asText()).isEqualTo("HTTP_RESPONSE");
        assertThat(result.path("run").path("reason").asText()).isEqualTo("RETRY_AFTER_OR_OVERLOAD");
        assertThat(result.path("attempts").get(0).path("retryAfter").path("conflict").asBoolean()).isTrue();
    }
    @Test void invalidJsonIsContractFailureNotSuccessfulClear() throws Exception {
        api.mode="BROKEN";
        var result=execute(request(LabRequest.Type.GET_BASELINE,5,1,1000));
        assertThat(api.calls.get()).isEqualTo(1);
        assertThat(result.path("attempts").get(0).path("issues").toString()).contains("INVALID_JSON_OBJECT");
        assertThat(result.path("workflows").size()).isZero();
    }
    @Test void unauthorizedResponseIsObservedOnceAndDoesNotBecomePortalLogout() throws Exception {
        api.mode="UNAUTHORIZED";
        var result=execute(request(LabRequest.Type.GET_BASELINE,5,1,1000));
        assertThat(result.path("attempts").get(0).path("httpCode").asInt()).isEqualTo(401);
        assertThat(api.calls.get()).isEqualTo(1);
    }
    @Test void missingTargetStopsAndServerErrorsAreMeasuredWithoutHiddenApplicationRetries() throws Exception {
        api.mode="NOT_FOUND";
        var missing=execute(request(LabRequest.Type.GET_BASELINE,2,1,4000));
        assertThat(missing.path("run").path("reason").asText()).isEqualTo("HTTP_404");
        assertThat(api.calls.get()).isEqualTo(1);
        api.calls.set(0);api.mode="INTERNAL_ERROR";
        var errors=execute(request(LabRequest.Type.GET_BASELINE,2,1,4000));
        assertThat(errors.path("attempts").size()).isEqualTo(2);assertThat(api.calls.get()).isEqualTo(2);
        for(var attempt:errors.path("attempts")) {
            assertThat(attempt.path("httpCode").asInt()).isEqualTo(500);
            assertThat(attempt.path("outcome").asText()).isEqualTo("HTTP_RESPONSE");
            assertThat(attempt.path("retryIndex").asInt()).isZero();
        }
    }
    @Test void budgetAndPostAcknowledgementAreValidatedBeforeCalls() {
        var r=request(LabRequest.Type.GET_CONCURRENCY,10,3,1000);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->runner.reserve(1,r));
        var p=request(LabRequest.Type.REPEAT_POST,1,1,1000);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->new LabRunner(client,json,true,false).reserve(1,p));
        assertThat(api.calls.get()).isZero();
    }
    @Test void cancelIsOwnerBoundAndPreventsNewCalls() throws Exception {
        var r=request(LabRequest.Type.GET_BASELINE,5,1,1000);
        var control=runner.reserve(1,r);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->runner.cancel(control.id,2));
        runner.cancel(control.id,1);
        var out=new ByteArrayOutputStream();runner.execute(control,r,out);
        assertThat(api.calls.get()).isZero();assertThat(out.toString()).contains("USER_CANCELLED");
        // The previous reservation was released.
        var next=runner.reserve(1,r);runner.release(next);
    }
    @Test void responseBodySizeIsBounded() throws Exception {
        api.mode="LARGE";
        var result=execute(request(LabRequest.Type.GET_BASELINE,1,1,1000));
        assertThat(result.path("attempts").get(0).path("issues").toString()).contains("BODY_LIMIT");
    }
    @Test void requestAndTransportDiagnosticsRedactSecrets() {
        assertThat(request(LabRequest.Type.REPEAT_POST,1,1,1000).toString()).doesNotContain("PRIVATE");
        var headers=java.net.http.HttpHeaders.of(Map.of("Retry-After",List.of("not-a-header-PRIVATE_TEST_KEY")),(a,b)->true);
        var retry=BackgroundCheckClient.retry(headers,null);
        assertThat(retry.header()).isNull();assertThat(retry.invalidHeader()).isTrue();
    }
    @Test void retryAfterAcceptsHttpDateAndRejectsOverflowWithoutInventingWait() {
        String date=java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).plusMinutes(1)
            .format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME);
        var headers=java.net.http.HttpHeaders.of(Map.of("Retry-After",List.of(date)),(a,b)->true);
        assertThat(BackgroundCheckClient.retry(headers,null).headerMs()).isBetween(55000L,60000L);
        var overflow=java.net.http.HttpHeaders.of(Map.of("Retry-After",List.of("9223372036854775807")),(a,b)->true);
        var invalid=BackgroundCheckClient.retry(overflow,null);
        assertThat(invalid.invalidHeader()).isTrue();assertThat(invalid.preferredMs()).isNull();
    }
}
