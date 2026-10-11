package com.bit.portal.backgroundcheck;

import com.bit.portal.backgroundcheck.client.BackgroundCheckClient;
import com.bit.portal.employee.EmployeeService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("portal-dev")
public class BackgroundCheckService {
    private final BackgroundCheckClient client;
    private final EmployeeService employees;
    private final String key;
    private final boolean enabled;
    private final int connectMs, requestMs;
    private final BackgroundCheckPolicy policy;
    // One business call at a time per application instance. Lab retains its separate experimental limit.
    private final java.util.concurrent.Semaphore businessSlot = new java.util.concurrent.Semaphore(1);
    @Autowired
    public BackgroundCheckService(BackgroundCheckClient client, EmployeeService employees,
            @Value("${background-check.candidate-key:}") String key,
            @Value("${background-check.enabled:false}") boolean enabled,
            @Value("${background-check.client.connect-timeout-ms:0}") int connectMs,
            @Value("${background-check.client.request-timeout-ms:30000}") int requestMs, BackgroundCheckPolicy policy) {
        this.client=client;this.employees=employees;this.key=key;this.enabled=enabled;this.connectMs=connectMs;this.requestMs=requestMs;this.policy=policy;
    }
    // Retained for the existing isolated client tests.
    public BackgroundCheckService(BackgroundCheckClient client,EmployeeService employees,String key,boolean enabled,int connectMs,int requestMs) {
        this(client,employees,key,enabled,connectMs,requestMs,new BackgroundCheckPolicy());
    }
    public boolean available() { return enabled && client.permitted() && !key.isBlank() && connectMs>0 && requestMs>0 && connectMs<=30000 && requestMs<=60000; }
    public int requestMs() { return requestMs; }
    public long getBudgetMs() { return (long)requestMs*(policy.getMaxRetries()+1)+(long)policy.getRetryBackoffMs()*policy.getMaxRetries(); }
    public BackgroundCheckClient.Reply exchange(String endpoint,String id,String checkId,JsonNode body,long budgetMs) {
        if(!available()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"배경 조회 환경 설정을 확인하세요.");
        policy.validate();
        if(!businessSlot.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"다른 업무 조회가 진행 중입니다. 잠시 후 확인하세요.");
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.max(1,budgetMs));
        try(var transport=client.open(connectMs)) {
            var reply=transport.exchange(endpoint,id,checkId,body,key,requestMs,deadline,()->false);
            // POST is never retried, including 5xx and incomplete success responses.
            for(int retry=0; !"CREATE".equals(endpoint) && retry<policy.getMaxRetries(); retry++) {
                if(!"HTTP_RESPONSE".equals(reply.outcome()) || reply.httpCode()==null
                    || !java.util.Set.of(500,503).contains(reply.httpCode()) || reply.retry().invalidHeader()) break;
                long wait=Math.max(policy.getRetryBackoffMs(),reply.retry().preferredMs()==null?0:reply.retry().preferredMs());
                long left=java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime());
                if(wait>left || left-wait<requestMs) break; // Do not shorten Retry-After to fit the deadline.
                try { Thread.sleep(wait); } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
                reply=transport.exchange(endpoint,id,checkId,null,key,requestMs,deadline,()->false);
            }
            return reply;
        } finally { businessSlot.release(); }
    }
    public static boolean valid(JsonNode body,String id,String checkId,boolean create) {
        return body!=null && body.isObject() && id.equals(body.path("employeeId").asText())
            && body.path("checkId").asText().matches("[A-Za-z0-9._-]{1,128}")
            && (checkId==null || checkId.equals(body.path("checkId").asText()))
            && java.util.Set.of("pending","clear","flagged").contains(body.path("status").asText())
            && (!create || !body.path("createdAt").asText().isBlank());
    }
    public JsonNode read(String id,String checkId) {
        employees.get(id);
        if(checkId!=null && !checkId.matches("[A-Za-z0-9._-]{1,128}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"조회 번호를 확인하세요.");
            var reply=exchange(checkId==null?"LIST":"DETAIL",id,checkId,null,getBudgetMs());
            if(!"HTTP_RESPONSE".equals(reply.outcome()) || !Integer.valueOf(200).equals(reply.httpCode()) || reply.body()==null || !reply.body().isObject())
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"외부 조회 결과를 확인할 수 없습니다.");
            if(!id.equals(reply.body().path("employeeId").asText()) || checkId!=null && !checkId.equals(reply.body().path("checkId").asText()))
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,"해당 직원의 조회 결과가 아닙니다.");
            if(checkId==null && !reply.body().path("checks").isArray() || checkId!=null && !java.util.Set.of("pending","clear","flagged").contains(reply.body().path("status").asText()))
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"외부 응답 형식을 확인할 수 없습니다.");
            return reply.body();
    }
}
