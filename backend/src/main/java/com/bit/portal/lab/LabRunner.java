package com.bit.portal.lab;

import com.bit.portal.backgroundcheck.client.BackgroundCheckClient;
import com.bit.portal.backgroundcheck.client.BackgroundCheckClient.Reply;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("portal-dev")
public class LabRunner {
    private final BackgroundCheckClient client;
    private final ObjectMapper json;
    private final boolean enabled, postEnabled;
    private final String specHash;
    private final ConcurrentMap<String, Control> active=new ConcurrentHashMap<>();
    private final Semaphore running=new Semaphore(1);
    public LabRunner(BackgroundCheckClient client, ObjectMapper json,
            @Value("${lab.enabled:false}") boolean enabled, @Value("${lab.post-enabled:false}") boolean postEnabled) {
        this.client=client; this.json=json; this.enabled=enabled; this.postEnabled=postEnabled;
        try (var spec=LabRunner.class.getResourceAsStream("/background-check-api.yaml")) {
            specHash=spec==null?"UNRECORDED":java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(spec.readAllBytes()));
        } catch(Exception error) { throw new IllegalStateException("Cannot fingerprint API specification"); }
    }
    public Map<String,Object> capabilities() {
        return Map.of("enabled", enabled && client.permitted(), "postEnabled", postEnabled && enabled && client.permitted(),
            "source",client.source(), "maxCalls",20, "maxConcurrency",3, "maxDurationMs",120000,
            "transport","Java 21 HttpClient / HTTP_1_1 / request + whole-body deadline",
            "transparentRetry","Application retries OFF; JDK transport recovery is not wire-instrumented");
    }
    public Control reserve(long owner, LabRequest request) {
        if (!enabled || !client.permitted()) throw error(HttpStatus.SERVICE_UNAVAILABLE,"실험 호출이 비활성입니다. 승인 후 서버에서 활성화하세요.");
        validate(request);
        if (!running.tryAcquire()) throw error(HttpStatus.CONFLICT,"다른 실험이 실행 중입니다.");
        var control=new Control(UUID.randomUUID().toString(),owner);
        active.put(control.id,control); return control;
    }
    public void cancel(String id,long owner) {
        var control=active.get(id);
        if (control==null || control.owner!=owner) throw error(HttpStatus.NOT_FOUND,"실행을 찾을 수 없습니다.");
        control.stop("USER_CANCELLED");
    }
    public void release(Control control) { if (active.remove(control.id,control)) running.release(); }
    public static final class Control {
        public final String id; private final long owner;
        final AtomicReference<String> reason=new AtomicReference<>();
        final AtomicInteger started=new AtomicInteger();
        boolean finished;
        long deadline;
        Control(String id,long owner) { this.id=id; this.owner=owner; }
        void stop(String why) { reason.compareAndSet(null,why); }
        boolean stopped() { return reason.get()!=null; }
    }
    private void validate(LabRequest r) {
        boolean post=r.type()==LabRequest.Type.CREATE_POLL || r.type()==LabRequest.Type.REPEAT_POST;
        if (post != (r.endpoint()==LabRequest.Endpoint.CREATE)) throw error(HttpStatus.BAD_REQUEST,"실험 유형과 Endpoint가 맞지 않습니다.");
        if (!post && r.type()==LabRequest.Type.GET_BASELINE && r.concurrency()!=1) throw error(HttpStatus.BAD_REQUEST,"순차 실험의 동시성은 1입니다.");
        if (post && (!postEnabled || !r.postAcknowledged() || r.concurrency()!=1)) throw error(HttpStatus.BAD_REQUEST,"POST 승인과 순차 실행 조건을 확인하세요.");
        String target=r.endpoint()==LabRequest.Endpoint.DETAIL ? r.checkId() : r.employeeId();
        if (target==null || !target.matches("[A-Za-z0-9._-]{1,128}")) throw error(HttpStatus.BAD_REQUEST,"조회 대상을 확인하세요.");
        if (r.candidateKey().chars().anyMatch(c -> c<33 || c>126)) throw error(HttpStatus.BAD_REQUEST,"키 형식을 확인하세요.");
        if (post) {
            try {
                if (r.firstName()==null || r.firstName().isBlank() || r.lastName()==null || r.lastName().isBlank()
                    || r.dateOfBirth()==null || !LocalDate.parse(r.dateOfBirth()).isBefore(LocalDate.now()))
                    throw new IllegalArgumentException();
            } catch (RuntimeException error) { throw error(HttpStatus.BAD_REQUEST,"조회용 성·이름·생년월일을 확인하세요."); }
        }
        int planned=planned(r);
        if (planned>r.callBudget()) throw error(HttpStatus.BAD_REQUEST,"예정 호출 수가 전체 예산을 초과합니다.");
    }
    static int planned(LabRequest r) {
        return switch(r.type()) {
            case GET_BASELINE -> r.repeats();
            case GET_CONCURRENCY -> r.repeats()*r.concurrency();
            case CREATE_POLL -> 1+r.maxPolls();
            case REPEAT_POST -> 4; // baseline + two POSTs + final reconciliation
        };
    }
    public void execute(Control control,LabRequest r,OutputStream output) {
        control.deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(r.durationMs());
        var state=new State(control,r,output);
        try (var transport=client.open(r.connectTimeoutMs())) {
            state.transport=transport;
            state.event("start",Map.of("run",state.run(), "attempts",List.of()));
            switch(r.type()) {
                case GET_BASELINE -> state.getPhase(1);
                case GET_CONCURRENCY -> { for(int c=1;c<=r.concurrency() && !state.stopCheck();c++) state.getPhase(c); }
                case CREATE_POLL -> state.createPoll();
                case REPEAT_POST -> state.repeatPost();
            }
            control.finished=true;
            state.event("result",Map.of("run",state.run(),"attempts",state.attempts,"phases",state.phases,
                "workflows",state.workflows(),"postComparison",state.postComparison));
        } catch (IOException disconnected) { control.stop("DISCONNECTED"); }
        catch (Exception failure) {
            control.stop("RUN_ERROR");
            control.finished=true;
            try { state.event("result",Map.of("run",state.run(),"attempts",state.attempts,"phases",state.phases,
                "workflows",state.workflows(),"postComparison",state.postComparison)); } catch(IOException ignored) { }
        } finally { release(control); }
    }
    private final class State {
        final Control control; final LabRequest r; final OutputStream output;
        final Instant started=Instant.now();
        final List<Map<String,Object>> attempts=new ArrayList<>(), phases=new ArrayList<>();
        final Map<String,String> aliases=new LinkedHashMap<>();
        final Map<String,Map<String,Object>> flows=new LinkedHashMap<>();
        final Map<String,Object> postComparison=new LinkedHashMap<>();
        BackgroundCheckClient.Transport transport;
        State(Control control,LabRequest r,OutputStream output) { this.control=control; this.r=r; this.output=output; }
        boolean stopCheck() {
            if(System.nanoTime()>=control.deadline) control.stop("DURATION_LIMIT");
            return control.stopped();
        }
        Map<String,Object> run() {
            var map=new LinkedHashMap<String,Object>();
            map.put("runId",control.id); map.put("comparisonGroupId",control.id); map.put("type",r.type());
            map.put("endpoint",r.endpoint()); map.put("targetAlias","target-1"); map.put("source",client.source());
            map.put("startedAt",started); map.put("updatedAt",Instant.now());
            map.put("reason",control.reason.get()); map.put("state",control.stopped()?"STOPPED":control.finished?"COMPLETED":"RUNNING");
            map.put("plannedUpperBound",planned(r)); map.put("started",attempts.size());
            map.put("notStarted",Math.max(0,planned(r)-attempts.size())); map.put("finished",attempts.size());
            map.put("conditions",Map.of("repeats",r.repeats(),"maxConcurrency",r.concurrency(),
                "connectTimeoutMs",r.connectTimeoutMs(),"requestTimeoutMs",r.requestTimeoutMs(),
                "durationMs",r.durationMs(),"intervalMs",r.intervalMs(),"pollIntervalMs",r.pollIntervalMs(),
                "maxPolls",r.maxPolls(),"callBudget",r.callBudget()));
            map.put("javaVersion",System.getProperty("java.version")); map.put("client","JDK HttpClient HTTP_1_1");
            map.put("revision",System.getenv().getOrDefault("RAILWAY_GIT_COMMIT_SHA","LOCAL_UNRECORDED"));
            map.put("specVersion","1.1.1"); map.put("specSha256",specHash); map.put("quantileMethod","TYPE_7");
            map.put("connectionMode","reusedClient (physical connection reuse not instrumented)");
            map.put("retryMode","Application OFF / transparent transport recovery not instrumented");
            return map;
        }
        void event(String type,Object data) throws IOException {
            output.write(json.writeValueAsBytes(Map.of("type",type,"data",data))); output.write('\n'); output.flush();
        }
        boolean pause(int ms) {
            long end=Math.min(control.deadline,System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(ms));
            while(System.nanoTime()<end && !stopCheck()) {
                try { Thread.sleep(Math.min(25,Math.max(1,TimeUnit.NANOSECONDS.toMillis(end-System.nanoTime())))); }
                catch(InterruptedException error) { Thread.currentThread().interrupt(); control.stop("CANCELLED"); }
            }
            return !stopCheck();
        }
        Reply call(String endpoint,String checkId,int stage) {
            if(stopCheck()) return null;
            int seq=control.started.incrementAndGet();
            if(seq>r.callBudget()) { control.stop("CALL_BUDGET"); return null; }
            return transport.exchange(endpoint,r.employeeId(),checkId,postBody(),r.candidateKey(),
                r.requestTimeoutMs(),control.deadline,control::stopped);
        }
        JsonNode postBody() {
            var body=json.createObjectNode();
            body.put("employeeId",r.employeeId());body.put("firstName",r.firstName());body.put("lastName",r.lastName());body.put("dateOfBirth",r.dateOfBirth());
            return body;
        }
        void record(Reply reply,String endpoint,int stage,String expectedCheck) throws IOException {
            if(reply==null || "NOT_STARTED".equals(reply.outcome())) return;
            var row=new LinkedHashMap<String,Object>();
            row.put("attemptId",UUID.randomUUID().toString()); row.put("sequence",attempts.size()+1);
            row.put("logicalOperationId",control.id+"/"+stage+"/"+(attempts.size()+1));
            row.put("endpoint",endpoint);row.put("concurrency",stage);row.put("retryIndex",0);
            row.put("outcome",reply.outcome());row.put("httpCode",reply.httpCode());row.put("elapsedMs",reply.elapsedMs());
            row.put("queueWaitMs",reply.queueWaitMs());row.put("startedAt",reply.startedAt());row.put("endedAt",reply.endedAt());
            row.put("requestTimeoutMs",r.requestTimeoutMs());row.put("httpVersion",reply.httpVersion());
            row.put("responseBytes",reply.responseBytes());row.put("retryAfter",reply.retry());
            var issues=new ArrayList<String>();
            JsonNode body=reply.body();
            boolean ok=reply.httpCode()!=null && reply.httpCode()>=200 && reply.httpCode()<300;
            if("HTTP_RESPONSE".equals(reply.outcome())) {
                Set<Integer> documented="CREATE".equals(endpoint)?Set.of(201,400,401):Set.of(200,400,401,404,500,503);
                if(!documented.contains(reply.httpCode())) issues.add("UNDOCUMENTED_HTTP_CODE");
                if(body==null || !body.isObject()) issues.add("INVALID_JSON_OBJECT");
                else if(ok) {
                    if((!"DETAIL".equals(endpoint) && !Objects.equals(r.employeeId(), body.path("employeeId").asText()))) issues.add("EMPLOYEE_ID_MISMATCH");
                    if("LIST".equals(endpoint)) {
                        if(!body.path("checks").isArray()) issues.add("EXPECTED_CHECKS_ARRAY");
                        if(!body.path("totalCount").isIntegralNumber()) issues.add("EXPECTED_TOTAL_COUNT_INTEGER");
                        if(body.path("checks").isArray()) for(var check:body.path("checks")) validateCheck(check,issues,null);
                    } else {
                        validateCheck(body,issues,expectedCheck);
                        String raw=body.path("checkId").asText("");
                        if(!raw.isBlank()) {
                            String alias=aliases.computeIfAbsent(raw,id->"check-"+(aliases.size()+1));
                            row.put("checkRef",alias);row.put("status",body.path("status").asText());
                            var flow=flows.computeIfAbsent(alias,id->new LinkedHashMap<>());
                            flow.put("checkRef",alias);
                            if("CREATE".equals(endpoint)) flow.put("postInitialStatus",body.path("status").asText());
                            for(String field:List.of("createdAt","completedAt")) {
                                var instant=instant(body.path(field));
                                if(instant!=null) flow.put(field,instant.toString());
                            }
                            if(body.path("estimatedCompletionSeconds").isIntegralNumber()) row.put("estimatedCompletionSeconds",body.path("estimatedCompletionSeconds").asLong());
                            String status=body.path("status").asText();
                            if("pending".equals(status)) {
                                flow.putIfAbsent("firstPendingObserved",reply.endedAt().toString());
                                flow.put("lastPendingObserved",reply.endedAt().toString());
                                flow.put("state","CENSORED");
                            } else if(Set.of("clear","flagged").contains(status)) {
                                flow.put("finalStatus",status);flow.putIfAbsent("firstFinalObserved",reply.endedAt().toString());flow.put("state","TERMINAL");
                            }
                        }
                    }
                }
            }
            if(reply.transportIssue()!=null) issues.add(reply.transportIssue());
            row.put("issues",issues); attempts.add(row);
            if(reply.httpCode()!=null && (reply.httpCode()==401 || reply.httpCode()==404)) control.stop("HTTP_"+reply.httpCode());
            if(reply.httpCode()!=null && (reply.httpCode()==503 || reply.httpCode()==429) || reply.retry().preferredMs()!=null) control.stop("RETRY_AFTER_OR_OVERLOAD");
            if(ok && !issues.isEmpty()) control.stop("CONTRACT_MISMATCH");
            event("progress",Map.of("run",run(),"attempt",row));
        }
        void validateCheck(JsonNode body,List<String> issues,String expected) {
            if(!body.path("checkId").isTextual() || !body.path("checkId").asText().matches("[A-Za-z0-9._-]{1,128}")) issues.add("EXPECTED_CHECK_ID");
            if(expected!=null && !expected.equals(body.path("checkId").asText())) issues.add("CHECK_ID_MISMATCH");
            if(!Set.of("pending","clear","flagged").contains(body.path("status").asText())) issues.add("STATUS_ENUM");
            if(instant(body.path("createdAt"))==null) issues.add("EXPECTED_CREATED_AT");
            if(body.hasNonNull("completedAt") && instant(body.path("completedAt"))==null) issues.add("INVALID_COMPLETED_AT");
            Instant created=instant(body.path("createdAt")), completed=instant(body.path("completedAt"));
            if(created!=null && completed!=null && completed.isBefore(created)) issues.add("TIMESTAMP_ORDER");
        }
        List<Map<String,Object>> workflows() {
            for(var flow:flows.values()) {
                if(flow.containsKey("createdAt") && flow.containsKey("completedAt"))
                    flow.put("businessDurationMs",Duration.between(Instant.parse((String)flow.get("createdAt")),Instant.parse((String)flow.get("completedAt"))).toMillis());
                if(flow.containsKey("firstPendingObserved") && flow.containsKey("firstFinalObserved"))
                    flow.put("observedPendingToFinalMs",Duration.between(Instant.parse((String)flow.get("firstPendingObserved")),Instant.parse((String)flow.get("firstFinalObserved"))).toMillis());
            }
            return new ArrayList<>(flows.values());
        }
        void getPhase(int c) throws IOException {
            long begin=System.nanoTime(); int countBefore=attempts.size();
            try(var pool=Executors.newFixedThreadPool(c)) {
                for(int offset=0;offset<r.repeats() && !stopCheck();offset+=c) {
                    var futures=new ArrayList<Future<Reply>>();
                    for(int j=0;j<Math.min(c,r.repeats()-offset);j++) futures.add(pool.submit(()->call(r.endpoint().name(),r.checkId(),c)));
                    for(var future:futures) {
                        try { record(future.get(),r.endpoint().name(),c,r.endpoint()==LabRequest.Endpoint.DETAIL?r.checkId():null); }
                        catch(InterruptedException e) { Thread.currentThread().interrupt();control.stop("CANCELLED"); }
                        catch(ExecutionException e) { control.stop("RUN_ERROR"); }
                    }
                    if(offset+c<r.repeats() && !pause(r.intervalMs())) break;
                }
            }
            phases.add(Map.of("concurrency",c,"n",attempts.size()-countBefore,"elapsedMs",(System.nanoTime()-begin)/1_000_000.0));
        }
        void createPoll() throws IOException {
            Reply created=call("CREATE",null,1);record(created,"CREATE",1,null);
            if(created==null || !"HTTP_RESPONSE".equals(created.outcome())) { postComparison.put("result","UNKNOWN_NO_REPOST");return; }
            if(stopCheck() || created.httpCode()!=201 || created.body()==null) return;
            String check=created.body().path("checkId").asText();
            if(!"pending".equals(created.body().path("status").asText())) return;
            for(int i=0;i<r.maxPolls() && !stopCheck();i++) {
                if(!pause(r.pollIntervalMs())) break;
                Reply reply=call("DETAIL",check,1);record(reply,"DETAIL",1,check);
                if(reply==null || !"HTTP_RESPONSE".equals(reply.outcome()) || stopCheck()) break;
                if(reply.body()!=null && Set.of("clear","flagged").contains(reply.body().path("status").asText())) return;
            }
            if(!control.stopped()) control.stop("POLL_LIMIT");
        }
        void repeatPost() throws IOException {
            Reply before=call("LIST",null,1);record(before,"LIST",1,null);
            if(before==null || !Integer.valueOf(200).equals(before.httpCode()) || stopCheck()) return;
            Set<String> prior=ids(before.body()); var returned=new ArrayList<String>();
            boolean unknown=false;
            for(int i=0;i<2 && !stopCheck();i++) {
                Reply post=call("CREATE",null,1);record(post,"CREATE",1,null);
                if(post==null || !"HTTP_RESPONSE".equals(post.outcome())) { unknown=true;break; }
                if(!Integer.valueOf(201).equals(post.httpCode()) || post.body()==null || stopCheck()) break;
                returned.add(post.body().path("checkId").asText());
                if(i==0 && !pause(r.intervalMs())) break;
            }
            Reply after=call("LIST",null,1);record(after,"LIST",1,null);
            Set<String> afterIds=after!=null && Integer.valueOf(200).equals(after.httpCode())?ids(after.body()):null;
            postComparison.put("returnedRefs",returned.stream().map(id->aliases.get(id)).toList());
            postComparison.put("newHistoryCount",afterIds==null?-1:afterIds.stream().filter(id->!prior.contains(id)).count());
            postComparison.put("result",unknown?"UNKNOWN_NO_REPOST":returned.size()==2?
                    (returned.get(0).equals(returned.get(1))?"SAME_CHECK_ID":"DISTINCT_CHECK_IDS"):"INCOMPLETE_OR_REJECTED");
            postComparison.put("historyReconciled",afterIds!=null && afterIds.containsAll(returned));
            postComparison.put("limitation","History differences alone cannot attribute uncertain POSTs or exclude another caller.");
        }
        Set<String> ids(JsonNode body) {
            Set<String> ids=new HashSet<>();
            if(body!=null && body.path("checks").isArray()) for(var check:body.path("checks")) ids.add(check.path("checkId").asText());
            return ids;
        }
    }
    static Instant instant(JsonNode value) { try { return value.isTextual()?Instant.parse(value.asText()):null; } catch(RuntimeException e) { return null; } }
    private static ResponseStatusException error(HttpStatus status,String message) { return new ResponseStatusException(status,message); }
}
