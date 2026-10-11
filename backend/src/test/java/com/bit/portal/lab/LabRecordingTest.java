package com.bit.portal.lab;

import com.bit.portal.auth.AccountMapper;
import com.bit.portal.lab.store.LabRecordingMapper;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** H2 and loopback fixture only. No Railway SQL or external API calls. */
@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:lab-recordings;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.sql.init.mode=always", "spring.sql.init.encoding=UTF-8",
    "spring.sql.init.schema-locations=classpath:db/migration/V1__employees_and_accounts.sql,classpath:db/migration/V3__employee_contact_fields.sql,classpath:db/migration/V4__lab_recordings.sql",
    "spring.sql.init.data-locations=classpath:db/migration/V2__required_employee_seed.sql",
    "portal.bootstrap.admin-username=record-admin", "portal.bootstrap.admin-password=RecordTestPassword1",
    "lab.enabled=true", "lab.post-enabled=true", "lab.recording-enabled=true"
})
@ActiveProfiles("portal-dev") @AutoConfigureMockMvc
class LabRecordingTest {
    static final MockBackgroundApi api=fixture();
    static MockBackgroundApi fixture() { try{return new MockBackgroundApi(0);}catch(Exception e){throw new IllegalStateException(e);} }
    @DynamicPropertySource static void destination(DynamicPropertyRegistry r) { r.add("background-check.base-url",api::baseUrl); }
    @AfterAll static void close() { api.close(); }
    @Autowired LabRunner runner;
    @Autowired LabRecordingService recordings;
    @Autowired LabRecordingMapper mapper;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccountMapper accounts;
    @Autowired PasswordEncoder encoder;
    @Autowired MockMvc mvc;
    long owner;
    @BeforeEach void resetInMemoryOnly() {
        jdbc.update("DELETE FROM lab_runs");jdbc.update("DELETE FROM lab_campaigns");
        api.calls.set(0);api.mode="NORMAL";
        owner=jdbc.queryForObject("SELECT id FROM user_accounts WHERE username='record-admin'",Long.class);
    }
    LabCampaignRequest.Case condition(LabRequest.Type type,int goal,int c) {
        return new LabCampaignRequest.Case(type,type==LabRequest.Type.GET_FIXED?LabRequest.Endpoint.LIST:LabRequest.Endpoint.CREATE,
            LabCampaignRequest.Unit.REQUESTS,goal,c,1000,3000,0,1,5);
    }
    LabRequest request(LabCampaignRequest.Case c,int repeats,int polls,int budget) {
        return new LabRequest("PRIVATE_TEST_KEY",c.type(),c.endpoint(),"PRIVATE_EMPLOYEE_ID",null,"PRIVATE_FIRST","PRIVATE_LAST","1990-01-01",
            repeats,c.concurrency(),c.connectTimeoutMs(),c.requestTimeoutMs(),120000,c.intervalMs(),c.pollIntervalMs(),polls,budget,true);
    }
    JsonNode execute(LabRequest r,String campaign,String caseId,OutputStream out) throws Exception {
        var control=runner.reserve(owner,r,campaign,caseId);runner.execute(control,r,out);
        return json.valueToTree(recordings.run(owner,control.id).get("result"));
    }
    @Test void persistsOneHundredActualRequestsAndRejectsOverBudgetOrOtherOwner() throws Exception {
        var c=condition(LabRequest.Type.GET_FIXED,100,1);
        var plan=recordings.create(owner,new LabCampaignRequest(List.of(c)));
        String id=(String)plan.get("campaignId"),caseId=json.valueToTree(plan).path("cases").get(0).path("caseId").asText();
        for(int i=0;i<5;i++) execute(request(c,20,5,20),id,caseId,new ByteArrayOutputStream());
        assertThat(api.calls.get()).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT SUM(observed_calls) FROM lab_runs",Integer.class)).isEqualTo(100);
        assertThatThrownBy(()->runner.reserve(owner,request(c,1,5,1),id,caseId)).isInstanceOf(ResponseStatusException.class)
            .satisfies(e->assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(400));
        assertThatThrownBy(()->recordings.campaign(owner+100,id)).isInstanceOf(ResponseStatusException.class);
        String stored=String.join("",jdbc.queryForList("SELECT snapshot_json FROM lab_runs",String.class));
        assertThat(stored).doesNotContain("PRIVATE_TEST_KEY","PRIVATE_EMPLOYEE_ID","PRIVATE_FIRST","PRIVATE_LAST","1990-01-01");
    }
    @Test void runningReservationBlocksResumeEvenOnAnotherInstance() {
        var c=condition(LabRequest.Type.GET_FIXED,100,1);var plan=recordings.create(owner,new LabCampaignRequest(List.of(c)));
        String id=(String)plan.get("campaignId"),caseId=json.valueToTree(plan).path("cases").get(0).path("caseId").asText();
        recordings.open("orphan-run",owner,request(c,1,5,1),id,caseId);
        var other=new LabRecordingService(mapper,json,true);
        assertThat(other.run(owner,"orphan-run").get("stale")).isEqualTo(true);
        assertThatThrownBy(()->other.open("new-run",owner,request(c,1,5,1),id,caseId)).isInstanceOf(ResponseStatusException.class);
        assertThat(api.calls.get()).isZero();
    }
    @Test void disconnectPreservesInFlightObservationsAndDoesNotStartNextGroup() throws Exception {
        api.mode="CONCURRENCY";
        var c=condition(LabRequest.Type.GET_FIXED,100,3);
        var out=new ByteArrayOutputStream() {
            int flushes;
            @Override public void flush() throws IOException { if(++flushes>=2) throw new IOException("test disconnect"); }
        };
        var result=execute(request(c,6,5,6),null,null,out);
        assertThat(result.path("run").path("reason").asText()).isEqualTo("DISCONNECTED");
        assertThat(result.path("attempts").size()).isEqualTo(3);assertThat(api.calls.get()).isEqualTo(3);
        assertThat(result.path("phases").size()).isEqualTo(1);
    }
    @Test void partialDuplicateBudgetIsNeverPresentedAsCompleteComparison() throws Exception {
        var c=condition(LabRequest.Type.REPEAT_POST,100,1);
        var result=execute(request(c,1,5,3),null,null,new ByteArrayOutputStream());
        assertThat(result.path("attempts").size()).isEqualTo(3);
        assertThat(result.path("postComparison").path("result").asText()).isEqualTo("INCOMPLETE_BUDGET");
        assertThat(result.path("run").path("partialProtocol").asBoolean()).isTrue();
    }
    @Test void postOnlyPollingTailIsCensoredAndCountsOneHttpRequest() throws Exception {
        var c=condition(LabRequest.Type.CREATE_POLL,100,1);
        var result=execute(request(c,1,0,1),null,null,new ByteArrayOutputStream());
        assertThat(result.path("attempts").size()).isEqualTo(1);
        assertThat(result.path("workflows").get(0).path("state").asText()).isEqualTo("CENSORED");
        assertThat(result.path("run").path("reason").asText()).isEqualTo("POLL_LIMIT");
    }
    @Test void storageFailureBeforeStartPreventsEveryExternalCall() {
        var failing=mock(LabRecordingService.class);when(failing.enabled()).thenReturn(true);
        doThrow(new IllegalStateException("storage unavailable")).when(failing).open(anyString(),anyLong(),any(),any(),any());
        var isolated=new LabRunner(new com.bit.portal.backgroundcheck.client.BackgroundCheckClient(json,api.baseUrl(),false),json,true,true);
        isolated.recordings(failing);
        assertThatThrownBy(()->isolated.reserve(owner,request(condition(LabRequest.Type.GET_FIXED,100,1),1,5,1))).isInstanceOf(IllegalStateException.class);
        assertThat(api.calls.get()).isZero();
    }
    @Test void checkpointFailureBeforeFirstCallStopsWithoutExternalRequests() {
        var failing=mock(LabRecordingService.class);when(failing.enabled()).thenReturn(true);
        doThrow(new IllegalStateException("storage unavailable")).when(failing).checkpoint(anyString(),anyLong(),any());
        var isolated=new LabRunner(new com.bit.portal.backgroundcheck.client.BackgroundCheckClient(json,api.baseUrl(),false),json,true,true);
        isolated.recordings(failing);
        var r=request(condition(LabRequest.Type.GET_FIXED,100,1),1,5,1);
        isolated.execute(isolated.reserve(owner,r),r,new ByteArrayOutputStream());
        assertThat(api.calls.get()).isZero();
    }
    @Test void campaignServerErrorStopsBeforeNextGetGroup() throws Exception {
        api.mode="INTERNAL_ERROR";
        var c=condition(LabRequest.Type.GET_FIXED,100,1);var plan=recordings.create(owner,new LabCampaignRequest(List.of(c)));
        String id=(String)plan.get("campaignId"),caseId=json.valueToTree(plan).path("cases").get(0).path("caseId").asText();
        var result=execute(request(c,6,5,6),id,caseId,new ByteArrayOutputStream());
        assertThat(result.path("attempts").size()).isEqualTo(1);assertThat(api.calls.get()).isEqualTo(1);
        assertThat(result.path("run").path("reason").asText()).isEqualTo("OBSERVED_ERROR");
    }
    @Test void followupSavesProtocolAndLogicalMetadataButNeverFillsUnusedRetryCapacity() throws Exception {
        var p=new LabProtocol(LabProtocol.Family.R01,LabProtocol.Variant.SHORT,1,1);
        var c=new LabCampaignRequest.Case(LabRequest.Type.GET_RETRY,LabRequest.Endpoint.LIST,LabCampaignRequest.Unit.REQUESTS,2,1,5000,30000,2000,5000,6,p);
        var plan=recordings.create(owner,new LabCampaignRequest(List.of(c)));
        String id=(String)plan.get("campaignId"),caseId=json.valueToTree(plan).path("cases").get(0).path("caseId").asText();
        var r=new LabRequest("PRIVATE_TEST_KEY",c.type(),c.endpoint(),"PRIVATE_EMPLOYEE_ID",null,null,null,null,1,1,5000,30000,120000,2000,5000,6,2,false,p,true);
        var result=execute(r,id,caseId,new ByteArrayOutputStream());
        assertThat(result.path("logicalOperations").size()).isEqualTo(1);assertThat(result.path("run").path("protocol").path("family").asText()).isEqualTo("R01");
        assertThat(api.calls.get()).isEqualTo(1);
        assertThatThrownBy(()->runner.reserve(owner,r,id,caseId)).isInstanceOf(ResponseStatusException.class);
        assertThat(jdbc.queryForObject("SELECT snapshot_json FROM lab_runs",String.class)).doesNotContain("PRIVATE_TEST_KEY","PRIVATE_EMPLOYEE_ID");
    }
    @Test void balancedOrderCannotSkipEarlierLevelsOrContinueAfterFailure() {
        var cs=new ArrayList<LabCampaignRequest.Case>();
        for(int order=1;order<=2;order++) cs.add(new LabCampaignRequest.Case(LabRequest.Type.GET_FIXED,LabRequest.Endpoint.LIST,
            LabCampaignRequest.Unit.REQUESTS,3,order,5000,30000,2000,5000,6,new LabProtocol(LabProtocol.Family.C01,LabProtocol.Variant.DEFAULT,1,order)));
        var plan=recordings.create(owner,new LabCampaignRequest(cs));String id=(String)plan.get("campaignId");
        var cases=json.valueToTree(plan).path("cases");var c=cs.get(1);
        var r=new LabRequest("PRIVATE_TEST_KEY",c.type(),c.endpoint(),"PRIVATE_EMPLOYEE_ID",null,null,null,null,3,2,5000,30000,120000,2000,5000,6,3,false,c.protocol(),true);
        assertThatThrownBy(()->runner.reserve(owner,r,id,cases.get(1).path("caseId").asText())).isInstanceOf(ResponseStatusException.class);
        assertThat(api.calls.get()).isZero();
    }
    MockHttpSession login(String username) throws Exception {
        return (MockHttpSession)mvc.perform(post("/api/auth/login").with(csrf()).param("username",username).param("password","RecordTestPassword1"))
            .andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
    }
    @Test void savedDataRequiresAdminAndCreatingPlansRequiresCsrf() throws Exception {
        mvc.perform(get("/api/admin/reliability-lab/recordings")).andExpect(status().isUnauthorized());
        if(jdbc.queryForObject("SELECT COUNT(*) FROM user_accounts WHERE username='record-user'",Integer.class)==0)
            accounts.insert("EMP-001","record-user",encoder.encode("RecordTestPassword1"),"USER");
        mvc.perform(get("/api/admin/reliability-lab/recordings").session(login("record-user"))).andExpect(status().isForbidden());
        var admin=login("record-admin");
        mvc.perform(post("/api/admin/reliability-lab/campaigns").session(admin).contentType("application/json")
            .content(json.writeValueAsString(new LabCampaignRequest(List.of(condition(LabRequest.Type.GET_FIXED,100,1)))))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/reliability-lab/recordings").session(admin)).andExpect(status().isOk());
    }
}
