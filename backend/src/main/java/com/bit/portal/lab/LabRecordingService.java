package com.bit.portal.lab;

import com.bit.portal.lab.store.*;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("portal-dev")
public class LabRecordingService {
    private final LabRecordingMapper mapper;
    private final ObjectMapper json;
    private final boolean enabled;
    private final String instance=UUID.randomUUID().toString();
    public LabRecordingService(LabRecordingMapper mapper,ObjectMapper json,@Value("${lab.recording-enabled:false}") boolean enabled) {
        this.mapper=mapper;this.json=json;this.enabled=enabled;
    }
    public boolean enabled() { return enabled; }
    private void requireEnabled() { if(!enabled) throw error(HttpStatus.SERVICE_UNAVAILABLE,"서버 자동 저장이 비활성입니다. V4 SQL 적용 후 활성화하세요."); }
    private ResponseStatusException error(HttpStatus status,String message) { return new ResponseStatusException(status,message); }
    private JsonNode parse(String text) { try{return json.readTree(text);}catch(Exception e){throw error(HttpStatus.INTERNAL_SERVER_ERROR,"저장된 실험을 읽을 수 없습니다.");} }
    private String encode(Object value) { try{return json.writeValueAsString(value);}catch(Exception e){throw error(HttpStatus.INTERNAL_SERVER_ERROR,"실험을 저장할 수 없습니다.");} }
    public Map<String,Object> create(long owner,LabCampaignRequest request) {
        requireEnabled();
        var cases=new ArrayList<Map<String,Object>>();int maxCalls=0;
        for(var c:request.cases()) {
            boolean post=c.type()==LabRequest.Type.CREATE_POLL || c.type()==LabRequest.Type.REPEAT_POST;
            if ((!post && c.type()!=LabRequest.Type.GET_FIXED && c.type()!=LabRequest.Type.GET_RETRY && c.type()!=LabRequest.Type.POLL_EXISTING) || post!=(c.endpoint()==LabRequest.Endpoint.CREATE)
                || post && c.concurrency()!=1 || c.unit()!=LabCampaignRequest.Unit.REQUESTS)
                throw error(HttpStatus.BAD_REQUEST,"케이스의 실행 단위·유형·Endpoint를 확인하세요.");
            if(c.protocol()!=null) {
                var p=c.protocol();
                int repeats=Set.of(LabProtocol.Family.B01,LabProtocol.Family.C01,LabProtocol.Family.C02).contains(p.family())?3:1;
                var probe=new LabRequest("VALID",c.type(),c.endpoint(),"target","check","first","last","1990-01-01",repeats,c.concurrency(),
                    c.connectTimeoutMs(),c.requestTimeoutMs(),120000,c.intervalMs(),c.pollIntervalMs(),c.maxPolls(),20,true,p,true);
                LabRunner.validateProtocol(probe);
                if(c.goal()!=LabRunner.planned(probe)) throw error(HttpStatus.BAD_REQUEST,"후속 계획은 한 절차의 최대 HTTP 예산을 사용합니다.");
            } else if(c.type()==LabRequest.Type.GET_RETRY || c.type()==LabRequest.Type.POLL_EXISTING)
                throw error(HttpStatus.BAD_REQUEST,"후속 실험 프로토콜이 필요합니다.");
            maxCalls+=c.goal();
            cases.add(Map.of("caseId",UUID.randomUUID().toString(),"conditions",c));
        }
        if(maxCalls>4000) throw error(HttpStatus.BAD_REQUEST,"계획의 최대 HTTP 예산은 4000회입니다.");
        String id=UUID.randomUUID().toString();
        var plan=Map.<String,Object>of("campaignId",id,"cases",cases,"maxHttpCalls",maxCalls,
            "goalMeaning","Observed HTTP attempts, including GET, POST, polling and failures; never count unstarted calls",
            "scope","metadata only; targets and keys are not stored");
        mapper.insertCampaign(new LabStoredCampaign(id,owner,encode(plan)));return plan;
    }
    private JsonNode plan(long owner,String id) {
        requireEnabled();var row=mapper.campaign(id,owner);
        if(row==null) throw error(HttpStatus.NOT_FOUND,"저장된 계획을 찾을 수 없습니다.");
        return parse(row.planJson());
    }
    @Transactional
    public void open(String runId,long owner,LabRequest r,String campaignId,String caseId) {
        if(!enabled) { if(campaignId!=null || caseId!=null) requireEnabled();return; }
        if((campaignId==null)!=(caseId==null)) throw error(HttpStatus.BAD_REQUEST,"계획과 케이스 ID가 함께 필요합니다.");
        if(campaignId!=null) {
            // Serialize budget reservations for this campaign across server instances.
            if(mapper.lockCampaign(campaignId,owner)==null) throw error(HttpStatus.NOT_FOUND,"저장된 계획을 찾을 수 없습니다.");
            JsonNode chosen=null;
            for(var c:plan(owner,campaignId).path("cases")) if(c.path("caseId").asText().equals(caseId)) chosen=c;
            if(chosen==null) throw error(HttpStatus.NOT_FOUND,"케이스를 찾을 수 없습니다.");
            LabCampaignRequest.Case config;
            try{config=json.treeToValue(chosen.path("conditions"),LabCampaignRequest.Case.class);}catch(Exception e){throw error(HttpStatus.BAD_REQUEST,"케이스 조건을 읽을 수 없습니다.");}
            if(!config.matches(r)) throw error(HttpStatus.BAD_REQUEST,"저장된 케이스와 실행 조건이 다릅니다.");
            if(config.protocol()!=null) {
                // A pilot ends with the procedure, not with filling unused conditional HTTP capacity.
                var rows=mapper.campaignRuns(campaignId,owner);
                if(rows.stream().anyMatch(row->Objects.equals(row.caseId(),caseId)))
                    throw error(HttpStatus.CONFLICT,"이미 실행한 탐색 절차입니다. 검토 후 새 계획으로 명시적으로 다시 실행하세요.");
                boolean ordered=Set.of(LabProtocol.Family.R01,LabProtocol.Family.R03,LabProtocol.Family.C01,LabProtocol.Family.C02).contains(config.protocol().family());
                if(ordered) {
                    JsonNode previous=null;
                    for(var c:plan(owner,campaignId).path("cases")) {if(c.path("caseId").asText().equals(caseId)) break;previous=c;}
                    if(previous!=null) {
                        String priorId=previous.path("caseId").asText();
                        var prior=rows.stream().filter(row->Objects.equals(row.caseId(),priorId)).findFirst().orElse(null);
                        if(prior==null || !"COMPLETED".equals(prior.status())) throw error(HttpStatus.CONFLICT,"앞 절차가 정상 완료되어야 다음 비교를 실행할 수 있습니다.");
                        if(config.protocol().family()==LabProtocol.Family.C01 || config.protocol().family()==LabProtocol.Family.C02) {
                            var ended=LabRunner.instant(parse(prior.snapshotJson()).path("run").path("updatedAt"));
                            if(ended==null || java.time.Instant.now().isBefore(ended.plusSeconds(30)))
                                throw error(HttpStatus.CONFLICT,"동시성 수준 사이에 30초 보호 대기가 필요합니다.");
                        }
                    }
                }
            }
            int used=0;
            for(var stored:mapper.campaignRuns(campaignId,owner)) if(Objects.equals(stored.caseId(),caseId)) {
                if("RUNNING".equals(stored.status())) throw error(HttpStatus.CONFLICT,"실행 중이거나 최종 상태가 미확정인 차수가 있습니다. 자동 재개하지 않습니다.");
                used+=stored.observedCalls();
            }
            int next=LabRunner.planned(r);
            if(used+next>config.goal()) throw error(HttpStatus.BAD_REQUEST,"케이스 목표 횟수를 초과합니다.");
            if(config.protocol()==null && config.type()==LabRequest.Type.CREATE_POLL && r.maxPolls()!=Math.min(config.maxPolls(),config.goal()-used-1))
                throw error(HttpStatus.BAD_REQUEST,"폴링 예산은 남은 HTTP 목표에 맞춰야 합니다.");
        }
        var initial=Map.of("run",Map.of("runId",runId,"source","UNSTARTED","state","RUNNING"),"attempts",List.of());
        mapper.insertRun(new LabStoredRun(runId,owner,campaignId,caseId,instance,"RUNNING",LabRunner.planned(r),0,encode(initial)));
    }
    public void checkpoint(String id,long owner,Map<String,Object> snapshot) {
        if(!enabled) return;
        var row=mapper.run(id,owner);
        if(row==null) throw error(HttpStatus.INTERNAL_SERVER_ERROR,"저장할 실행을 찾을 수 없습니다.");
        var run=(Map<?,?>)snapshot.get("run");var attempts=(List<?>)snapshot.get("attempts");
        if(mapper.updateRun(new LabStoredRun(id,owner,row.campaignId(),row.caseId(),instance,
                String.valueOf(run.get("state")),row.plannedCalls(),attempts.size(),encode(snapshot)))!=1)
            throw error(HttpStatus.INTERNAL_SERVER_ERROR,"실험 저장에 실패했습니다.");
    }
    private Map<String,Object> view(LabStoredRun row) {
        var result=new LinkedHashMap<String,Object>();result.put("result",parse(row.snapshotJson()));
        result.put("runId",row.runId());result.put("caseId",row.caseId());result.put("campaignId",row.campaignId());
        result.put("recordingStatus",row.status());
        result.put("stale", "RUNNING".equals(row.status()) && !instance.equals(row.instanceId()));
        return result;
    }
    public List<Map<String,Object>> recent(long owner) {requireEnabled();return mapper.recentRuns(owner).stream().map(this::view).toList();}
    public Map<String,Object> run(long owner,String id) {
        requireEnabled();var row=mapper.run(id,owner);if(row==null) throw error(HttpStatus.NOT_FOUND,"실행을 찾을 수 없습니다.");return view(row);
    }
    public List<JsonNode> campaigns(long owner) { requireEnabled();return mapper.campaigns(owner).stream().map(c->parse(c.planJson())).toList(); }
    public Map<String,Object> campaign(long owner,String id) {
        var plan=plan(owner,id);return Map.of("plan",plan,"runs",mapper.campaignRuns(id,owner).stream().map(this::view).toList());
    }
}
