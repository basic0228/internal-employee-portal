package com.bit.portal.backgroundcheck;

import com.bit.portal.backgroundcheck.client.BackgroundCheckClient;
import com.bit.portal.employee.EmployeeMapper;
import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Short DB transactions surround HTTP calls: never hold an employee row lock while awaiting the API. */
@Service
@Profile("portal-dev")
public class BackgroundCheckHistoryService {
    private final BackgroundCheckHistoryMapper mapper;
    private final EmployeeMapper employees;
    private final BackgroundCheckService api;
    private final BackgroundCheckPolicy policy;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final boolean enabled;
    public BackgroundCheckHistoryService(BackgroundCheckHistoryMapper mapper,EmployeeMapper employees,BackgroundCheckService api,
            BackgroundCheckPolicy policy,ObjectMapper json,PlatformTransactionManager transactions,
            @Value("${background-check.recording-enabled:false}") boolean enabled) {
        this.mapper=mapper;this.employees=employees;this.api=api;this.policy=policy;this.json=json;
        this.tx=new TransactionTemplate(transactions);this.enabled=enabled;
    }
    // PostgreSQL/H2 timestamps store microseconds. Exact lease comparisons must use the same precision.
    private static OffsetDateTime now() { return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MICROS); }
    private void configured() {
        if(!enabled) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"조회 이력 저장은 V5 SQL 적용 후 활성화해야 합니다.");
        policy.validate();
    }
    private void employee(String id) {
        if(employees.findById(id)==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"직원을 찾을 수 없습니다.");
    }
    public Map<String,Object> history(String id,int offset) {
        employee(id);
        if(!enabled) return Map.of("checks",List.of(),"available",false,"totalCount",0,"pollingIntervalMs",policy.getPollingIntervalMs());
        configured();mapper.expireDispatch(id,now());mapper.expirePolling(id,now());
        return Map.of("checks",mapper.list(id,100,offset),"available",api.available(),"totalCount",mapper.count(id),
            "pollingIntervalMs",policy.getPollingIntervalMs());
    }
    public BackgroundCheckHistory get(String employee,String request) {
        configured();mapper.expireDispatch(employee,now());mapper.expirePolling(employee,now());
        var row=mapper.find(request);
        if(row==null || !employee.equals(row.employeeId())) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"해당 직원의 요청이 아닙니다.");
        return row;
    }
    public JsonNode detail(String employee,String check,long actor) {
        if(!enabled) return api.read(employee,check); // Preserve the previously deployed GET while V5 is disabled.
        var row=mapper.findByCheck(check);
        if(row==null || !employee.equals(row.employeeId())) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"저장된 해당 직원의 조회 이력이 없습니다.");
        var result=refresh(employee,row.requestId(),actor,false);
        if(result.get("detail") instanceof JsonNode body) return body;
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"상세 결과를 확인하지 못했습니다. 조회 이력의 상태를 확인하세요.");
    }
    public BackgroundCheckHistory create(String employee,long actor,CreateBackgroundCheckRequest input) {
        configured();
        if(!api.available()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"배경 조회 환경 설정을 확인하세요.");
        String id=input.requestId().toString();
        boolean dispatch=Boolean.TRUE.equals(tx.execute(status -> {
            var person=employees.findForUpdate(employee);
            if(person==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"직원을 찾을 수 없습니다.");
            var previous=mapper.find(id);
            if(previous!=null) {
                if(!employee.equals(previous.employeeId())) throw new ResponseStatusException(HttpStatus.CONFLICT,"다른 직원에 사용한 요청 번호입니다.");
                return false; // Same local action returns its saved state; never send another POST.
            }
            if(!"ACTIVE".equals(person.employmentStatus())) throw new ResponseStatusException(HttpStatus.CONFLICT,"퇴사한 직원의 신규 조회는 제한됩니다.");
            mapper.expireDispatch(employee,now());
            if(mapper.blockers(employee)>0) throw new ResponseStatusException(HttpStatus.CONFLICT,"진행 중이거나 결과가 불명확한 요청을 먼저 확인하세요.");
            if(mapper.completed(employee)>0 && !input.confirmRecheck()) throw new ResponseStatusException(HttpStatus.CONFLICT,"기존 결과를 열람하거나 신규 조회임을 확인하세요.");
            var at=now();mapper.reserve(id,employee,actor,at.plusNanos((api.requestMs()+5000L)*1000000),at.plusNanos(policy.getPollingMaxDurationMs()*1000000L));
            return true;
        }));
        if(!dispatch) return get(employee,id);
        var body=json.createObjectNode().put("employeeId",employee).put("firstName",input.firstName().trim())
            .put("lastName",input.lastName().trim()).put("dateOfBirth",input.dateOfBirth().toString());
        BackgroundCheckClient.Reply reply;
        try { reply=api.exchange("CREATE",employee,null,body,api.requestMs()); }
        catch(ResponseStatusException busy) {
            // No exchange was started. Persist that fact instead of leaving an ambiguous reservation.
            mapper.finish(id,"NOT_SENT",null,null,now(),null,null,"NOT_STARTED",null,true,"NOT_SENT");
            return get(employee,id);
        }
        boolean valid="HTTP_RESPONSE".equals(reply.outcome()) && Integer.valueOf(201).equals(reply.httpCode())
            && BackgroundCheckService.valid(reply.body(),employee,null,true);
        String state=valid?"KNOWN":"NOT_STARTED".equals(reply.outcome())?"NOT_SENT"
            : "HTTP_RESPONSE".equals(reply.outcome()) && Set.of(400,401,403).contains(reply.httpCode())?"REJECTED":"UNKNOWN";
        var observed=now();String remote=valid?reply.body().path("checkId").asText():null, remoteStatus=valid?reply.body().path("status").asText():null;
        boolean stopped=!valid || !"pending".equals(remoteStatus);
        long wait=waitMs(reply);boolean invalidWait=wait<0;
        mapper.finish(id,state,remote,remoteStatus,observed,valid?completed(reply.body()):null,reply.httpCode(),reply.outcome(),
            invalidWait?null:observed.plusNanos(wait*1000000L),stopped || invalidWait,
            invalidWait?"INVALID_WAIT":valid?(stopped?"FINAL":null):state);
        return get(employee,id);
    }
    public JsonNode candidates(String employee,String request) {
        var row=get(employee,request);
        if(!"UNKNOWN".equals(row.localState())) throw new ResponseStatusException(HttpStatus.CONFLICT,"결과 불명 요청만 외부 이력을 대조할 수 있습니다.");
        if("INVALID_WAIT".equals(row.stopReason())) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"외부 대기 안내가 잘못되어 설정 확인이 필요합니다.");
        if(row.nextPollAt()!=null && now().isBefore(row.nextPollAt())) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"외부 API 대기 안내 이후 이력을 확인하세요.");
        return api.read(employee,null);
    }
    public Map<String,Object> refresh(String employee,String request,long actor,boolean automatic) {
        var row=get(employee,request);var at=now();
        if(!"KNOWN".equals(row.localState())) throw new ResponseStatusException(HttpStatus.CONFLICT,"조회 번호가 확인되지 않았습니다. 외부 이력을 대조하세요.");
        if("INVALID_WAIT".equals(row.stopReason())) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"외부 대기 안내가 잘못되어 설정 확인이 필요합니다.");
        if(automatic && (!"pending".equals(row.status()) || row.pollStopped())) return Map.of("history",row);
        if(automatic && (at.isAfter(row.pollDeadline()) || row.pollCount()>=policy.getPollingMaxAttempts())) {
            mapper.stop(request,"POLL_LIMIT");return Map.of("history",get(employee,request));
        }
        long budget=api.getBudgetMs();
        if(automatic) budget=Math.min(budget,Math.max(0,Duration.between(at,row.pollDeadline()).toMillis()));
        if(budget<=0) { mapper.stop(request,"POLL_LIMIT");return Map.of("history",get(employee,request)); }
        // A DB lease also prevents simultaneous refresh calls from other administrators/instances.
        var lease=at.plusNanos((budget+5000)*1000000L);
        if(mapper.claim(request,at,lease,at.plusNanos(policy.getPollingIntervalMs()*1000000L),automatic?1:0,policy.getPollingMaxAttempts())==0)
            return Map.of("history",get(employee,request));
        BackgroundCheckClient.Reply reply;
        try { reply=api.exchange("DETAIL",employee,row.checkId(),null,budget); }
        catch(ResponseStatusException failure) {
            mapper.observe(request,lease,row.status(),now(),null,null,"NOT_STARTED",now().plusNanos(policy.getPollingIntervalMs()*1000000L),true,"BUSY_OR_DISABLED");
            return Map.of("history",get(employee,request));
        }
        boolean valid="HTTP_RESPONSE".equals(reply.outcome()) && Integer.valueOf(200).equals(reply.httpCode())
            && BackgroundCheckService.valid(reply.body(),employee,row.checkId(),false);
        String remoteStatus=valid?reply.body().path("status").asText():row.status();
        var observed=now();long wait=waitMs(reply);boolean invalidWait=wait<0;
        var next=invalidWait?null:observed.plusNanos(wait*1000000L);
        // Errors end this automatic observation cycle; pending stays pending, never becomes clear.
        boolean stopped=row.pollStopped() || !valid || invalidWait || !"pending".equals(remoteStatus)
            || next.isAfter(row.pollDeadline()) || row.pollCount()+(automatic?1:0)>=policy.getPollingMaxAttempts();
        String reason=invalidWait?"INVALID_WAIT":!valid?"OBSERVATION_ERROR":!"pending".equals(remoteStatus)?"FINAL":stopped?"POLL_LIMIT":null;
        tx.executeWithoutResult(status -> {
            mapper.observe(request,lease,remoteStatus,observed,valid?completed(reply.body()):null,reply.httpCode(),reply.outcome(),next,stopped,reason);
            if(!automatic) mapper.view(request,actor,"DETAIL",valid?remoteStatus:null,valid?"HTTP_RESPONSE":"UNCONFIRMED");
        });
        var result=new HashMap<String,Object>();result.put("history",get(employee,request));
        // Automatic polling returns metadata only. Sensitive results are returned solely on manual detail view.
        if(valid && !automatic) result.put("detail",reply.body());
        if(!automatic) result.put("views",mapper.views(request));
        return result;
    }
    public BackgroundCheckHistory resolve(String employee,String request,long actor,String check,boolean confirmed) {
        var row=get(employee,request);
        if(!"UNKNOWN".equals(row.localState()) || !confirmed) throw new ResponseStatusException(HttpStatus.CONFLICT,"결과 불명 요청에 대해 관리자 연결 확인이 필요합니다.");
        if("INVALID_WAIT".equals(row.stopReason())) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"외부 대기 안내를 먼저 확인해야 합니다.");
        if(row.nextPollAt()!=null && now().isBefore(row.nextPollAt())) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"외부 API 대기 안내 이후 연결하세요.");
        var body=api.read(employee,check); // Must verify ownership with the same server-side candidate key.
        if(!BackgroundCheckService.valid(body,employee,check,false)) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"외부 결과를 확인할 수 없습니다.");
        var at=now();String remoteStatus=body.path("status").asText();
        tx.executeWithoutResult(status -> {
            int linked=mapper.finish(request,"KNOWN",check,remoteStatus,at,completed(body),200,"MANUALLY_LINKED",at.plusNanos(policy.getPollingIntervalMs()*1000000L),true,"MANUAL_RECONCILIATION");
            if(linked==0) throw new ResponseStatusException(HttpStatus.CONFLICT,"요청 상태가 변경되었습니다. 이력을 다시 확인하세요.");
            mapper.view(request,actor,"RESOLVE",remoteStatus,"MANUALLY_LINKED");
        });
        return get(employee,request);
    }
    private static OffsetDateTime completed(JsonNode body) {
        try { return OffsetDateTime.parse(body.path("completedAt").asText()); } catch(RuntimeException missing) { return null; }
    }
    private long waitMs(BackgroundCheckClient.Reply reply) {
        Long hint=reply.retry().preferredMs();
        // Do not truncate excessive/invalid hints and retry early. Stop for operator review instead.
        if(reply.retry().invalidHeader() || hint!=null && hint>86400000L) return -1;
        return Math.max(policy.getPollingIntervalMs(),hint==null?0:hint);
    }
}
