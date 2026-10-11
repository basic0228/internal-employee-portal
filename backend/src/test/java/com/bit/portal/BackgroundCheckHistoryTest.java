package com.bit.portal;

import com.bit.portal.auth.AccountMapper;
import com.bit.portal.backgroundcheck.*;
import com.bit.portal.lab.MockBackgroundApi;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real loopback HTTP + H2 only; these are regression cases, not additional external API measurements. */
@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:bg-history;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
    "spring.sql.init.mode=always","spring.sql.init.encoding=UTF-8",
    "spring.sql.init.schema-locations=classpath:db/migration/V1__employees_and_accounts.sql,classpath:db/migration/V3__employee_contact_fields.sql,classpath:db/migration/V5__background_check_history.sql",
    "spring.sql.init.data-locations=classpath:db/migration/V2__required_employee_seed.sql",
    "portal.bootstrap.admin-username=bg-admin","portal.bootstrap.admin-password=BackgroundPassword1",
    "background-check.enabled=true","background-check.recording-enabled=true","background-check.candidate-key=PRIVATE_TEST_KEY",
    "background-check.client.connect-timeout-ms=1000","background-check.client.request-timeout-ms=3000",
    "background-check.policy.polling-interval-ms=100","background-check.policy.polling-max-duration-ms=10000",
    "background-check.policy.polling-max-attempts=2"
})
@ActiveProfiles("portal-dev") @AutoConfigureMockMvc
class BackgroundCheckHistoryTest {
    static final MockBackgroundApi api=fixture();
    static MockBackgroundApi fixture() { try { return new MockBackgroundApi(0); } catch(Exception e) { throw new IllegalStateException(e); } }
    @DynamicPropertySource static void destination(DynamicPropertyRegistry r) { r.add("background-check.base-url",api::baseUrl); }
    @AfterAll static void close() { api.close(); }
    @Autowired BackgroundCheckHistoryService history;
    @Autowired BackgroundCheckHistoryMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired AccountMapper accounts;
    @Autowired PasswordEncoder encoder;
    long actor;
    @BeforeEach void resetH2Only() {
        jdbc.update("DELETE FROM background_check_views");jdbc.update("DELETE FROM background_checks");api.reset();
        api.lostCreateDelayMs=4000; // Fixture only: normal requests get 3s; created-but-lost replies take 4s.
        actor=accounts.findByUsername("bg-admin").id();
        if(accounts.findByUsername("bg-user")==null) accounts.insert("EMP-001","bg-user",encoder.encode("BackgroundPassword1"),"USER");
    }
    CreateBackgroundCheckRequest input() { return new CreateBackgroundCheckRequest(UUID.randomUUID(),"PRIVATE_FIRST","PRIVATE_LAST",LocalDate.parse("1990-01-01"),false); }
    MockHttpSession login(String name) throws Exception {
        return (MockHttpSession)mvc.perform(post("/api/auth/login").with(csrf()).param("username",name).param("password","BackgroundPassword1"))
            .andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
    }
    @Test void persistsOnlyMetadataAndSameActionNeverSendsAnotherPost() {
        var input=input();var first=history.create("EMP-001",actor,input);var again=history.create("EMP-001",actor,input);
        assertThat(first.localState()).isEqualTo("KNOWN");assertThat(first.status()).isEqualTo("pending");
        assertThat(again.requestId()).isEqualTo(first.requestId());assertThat(api.posts.get()).isEqualTo(1);
        var saved=jdbc.queryForList("SELECT * FROM background_checks").toString();
        assertThat(saved).doesNotContain("PRIVATE_FIRST","PRIVATE_LAST","1990-01-01","PRIVATE_TEST_KEY","criminalRecord");
        assertThatThrownBy(()->history.create("EMP-001",actor,input())).isInstanceOf(ResponseStatusException.class);
        assertThat(api.posts.get()).isEqualTo(1);
    }
    @Test void duplicateReservationsFromDifferentThreadsProduceOnePost() throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            var results=new ArrayList<Future<?>>();
            for(int i=0;i<2;i++) results.add(pool.submit(()->{try {start.await();history.create("EMP-001",actor,input());} catch(ResponseStatusException expected) {assertThat(expected.getStatusCode().value()).isEqualTo(409);} catch(InterruptedException e) {throw new RuntimeException(e);} }));
            start.countDown();for(var result:results) result.get(5,TimeUnit.SECONDS);
        }
        assertThat(api.posts.get()).isEqualTo(1);assertThat(mapper.count("EMP-001")).isEqualTo(1);
    }
    @Test void lostPostResponseRemainsUnknownEvenWhenTheExternalRecordExists() throws Exception {
        api.mode="LOST_CREATE";var request=input();var row=history.create("EMP-001",actor,request);
        assertThat(row.localState()).isEqualTo("UNKNOWN");assertThat(api.posts.get()).isEqualTo(1);
        assertThat(row.lastOutcome()).isEqualTo("REQUEST_TIMEOUT");
        assertThat(history.create("EMP-001",actor,request).localState()).isEqualTo("UNKNOWN");
        assertThatThrownBy(()->history.create("EMP-001",actor,input())).isInstanceOf(ResponseStatusException.class);
        assertThat(api.posts.get()).isEqualTo(1);
        api.mode="NORMAL";
        assertThatThrownBy(()->history.resolve("EMP-001",row.requestId(),actor,"CHK-mock-1",false)).isInstanceOf(ResponseStatusException.class);
        Thread.sleep(110);
        var candidates=history.candidates("EMP-001",row.requestId());
        assertThat(candidates.path("checks").get(0).path("checkId").asText()).isEqualTo("CHK-mock-1");
        var linked=history.resolve("EMP-001",row.requestId(),actor,"CHK-mock-1",true);
        assertThat(linked.status()).isEqualTo("clear");assertThat(api.posts.get()).isEqualTo(1);
        assertThat(linked.lastOutcome()).isEqualTo("MANUALLY_LINKED");
        assertThat(mapper.views(row.requestId())).hasSize(1);
    }
    @Test void observationErrorKeepsPendingAndDoesNotCreateAnotherCheck() throws Exception {
        var row=history.create("EMP-001",actor,input());Thread.sleep(110);api.mode="INTERNAL_ERROR";
        history.refresh("EMP-001",row.requestId(),actor,true);
        var failed=history.get("EMP-001",row.requestId());assertThat(failed.status()).isEqualTo("pending");assertThat(failed.pollStopped()).isTrue();
        int calls=api.calls.get();history.refresh("EMP-001",row.requestId(),actor,true);assertThat(api.calls.get()).isEqualTo(calls);
        assertThat(api.posts.get()).isEqualTo(1);
    }
    @Test void automaticPollingReturnsOnlyMetadataButManualViewAuditsTheActor() throws Exception {
        var row=history.create("EMP-001",actor,input());Thread.sleep(110);
        assertThat(history.refresh("EMP-001",row.requestId(),actor,true)).doesNotContainKey("detail");
        assertThat(history.get("EMP-001",row.requestId()).status()).isEqualTo("clear");Thread.sleep(110);
        var detail=history.refresh("EMP-001",row.requestId(),actor,false);
        assertThat(detail).containsKeys("detail","views");assertThat(mapper.views(row.requestId())).hasSize(1);
        assertThat(jdbc.queryForList("SELECT * FROM background_check_views").toString()).doesNotContain("PRIVATE_FIRST","PRIVATE_LAST");
    }
    @Test void stoppedPendingAndExhaustedDeadlineCannotSendMoreAutomaticGets() throws Exception {
        api.mode="PENDING_FOREVER";var row=history.create("EMP-001",actor,input());
        Thread.sleep(110);history.refresh("EMP-001",row.requestId(),actor,true);
        Thread.sleep(110);history.refresh("EMP-001",row.requestId(),actor,true);
        assertThat(history.get("EMP-001",row.requestId()).pollStopped()).isTrue();
        int calls=api.calls.get();history.refresh("EMP-001",row.requestId(),actor,true);assertThat(api.calls.get()).isEqualTo(calls);
        jdbc.update("UPDATE background_checks SET poll_stopped=FALSE,poll_deadline=? WHERE request_id=?",OffsetDateTime.now().minusSeconds(1),row.requestId());
        history.refresh("EMP-001",row.requestId(),actor,true);assertThat(api.calls.get()).isEqualTo(calls);
    }
    @Test void pendingTransitionsToFlaggedAndStopsWithoutAnotherAutomaticGet() throws Exception {
        api.mode="PENDING_THEN_FLAGGED";var row=history.create("EMP-001",actor,input());
        assertThat(row.status()).isEqualTo("pending");Thread.sleep(110);
        history.refresh("EMP-001",row.requestId(),actor,true);
        assertThat(history.get("EMP-001",row.requestId()).status()).isEqualTo("pending");Thread.sleep(110);
        history.refresh("EMP-001",row.requestId(),actor,true);
        var completed=history.get("EMP-001",row.requestId());
        assertThat(completed.status()).isEqualTo("flagged");assertThat(completed.pollStopped()).isTrue();
        assertThat(completed.completedAt()).isNotNull();assertThat(completed.pollCount()).isEqualTo(2);
        int calls=api.calls.get();history.refresh("EMP-001",row.requestId(),actor,true);
        assertThat(api.calls.get()).isEqualTo(calls);assertThat(api.posts.get()).isEqualTo(1);
    }
    @Test void completedChecksRequireExplicitManualRecheckAndOldHistoryIsPreserved() {
        api.mode="IMMEDIATE_FINAL";var first=history.create("EMP-001",actor,input());
        assertThatThrownBy(()->history.create("EMP-001",actor,input())).isInstanceOf(ResponseStatusException.class);
        var other=input();history.create("EMP-001",actor,new CreateBackgroundCheckRequest(other.requestId(),other.firstName(),other.lastName(),other.dateOfBirth(),true));
        assertThat(mapper.count("EMP-001")).isEqualTo(2);assertThat(mapper.find(first.requestId())).isNotNull();
    }
    @Test void restartReservationBecomesUnknownAndDoesNotUnlockCreation() {
        String id=UUID.randomUUID().toString();var at=OffsetDateTime.now().minusMinutes(1);
        mapper.reserve(id,"EMP-001",actor,at,at.plusSeconds(1));
        assertThat(history.get("EMP-001",id).localState()).isEqualTo("UNKNOWN");
        assertThatThrownBy(()->history.create("EMP-001",actor,input())).isInstanceOf(ResponseStatusException.class);
        assertThat(api.calls.get()).isZero();
    }
    @Test void userAndAnonymousCannotAccessHistoryOrCreateAndAdministratorNeedsCsrf() throws Exception {
        String path="/api/admin/employees/EMP-001/background-checks";
        mvc.perform(get(path+"/history")).andExpect(status().isUnauthorized());
        var user=login("bg-user");mvc.perform(get(path+"/history").session(user)).andExpect(status().isForbidden());
        mvc.perform(post(path).session(user).with(csrf()).contentType("application/json").content("{}" )).andExpect(status().isForbidden());
        mvc.perform(post(path).session(login("bg-admin")).contentType("application/json").content("{}" )).andExpect(status().isForbidden());
        mvc.perform(post(path).session(login("bg-admin")).with(csrf()).contentType("application/json").content("{}" )).andExpect(status().isBadRequest());
        assertThat(api.calls.get()).isZero();
    }
    @Test void detailAndReconciliationCannotLinkAnotherEmployee() {
        api.mode="LOST_CREATE";var row=history.create("EMP-002",actor,input());api.mode="NORMAL";
        assertThatThrownBy(()->history.resolve("EMP-001",row.requestId(),actor,"CHK-mock-1",true)).isInstanceOf(ResponseStatusException.class);
        assertThat(history.get("EMP-002",row.requestId()).localState()).isEqualTo("UNKNOWN");
    }
    @Test void userCannotAccessOwnOrOtherEmployeesDetailRefreshAndReconciliation() throws Exception {
        var row=history.create("EMP-001",actor,input());var user=login("bg-user");
        mvc.perform(get("/api/me").session(user)).andExpect(status().isOk())
            .andExpect(jsonPath("$.employeeId").value("EMP-001"));
        int calls=api.calls.get();
        for(String employee:List.of("EMP-001","EMP-002")) {
            String base="/api/admin/employees/"+employee+"/background-checks";
            for(String path:List.of(base+"/history",base+"/"+row.checkId(),base+"/history/"+row.requestId()+"/candidates"))
                mvc.perform(get(path).session(user)).andExpect(status().isForbidden());
            for(String path:List.of(base,base+"/history/"+row.requestId()+"/refresh",base+"/history/"+row.requestId()+"/resolve"))
                mvc.perform(post(path).session(user).with(csrf()).contentType("application/json").content("{}"))
                    .andExpect(status().isForbidden());
        }
        assertThat(api.calls.get()).isEqualTo(calls);assertThat(api.posts.get()).isEqualTo(1);
    }
    @Test void administratorCanCreateViaCsrfProtectedControllerAndReadSavedActor() throws Exception {
        var admin=login("bg-admin");String path="/api/admin/employees/EMP-001/background-checks";
        mvc.perform(post(path).session(admin).with(csrf()).contentType("application/json").content("""
            {"requestId":"%s","firstName":"민준","lastName":"김","dateOfBirth":"1990-03-15","confirmRecheck":false}
            """.formatted(UUID.randomUUID())))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("pending"))
            .andExpect(jsonPath("$.requestedByUsername").value("bg-admin")).andExpect(jsonPath("$.firstName").doesNotExist());
        mvc.perform(get(path+"/history").session(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.checks[0].requestedByUsername").value("bg-admin"));
        assertThat(api.posts.get()).isEqualTo(1);
    }
    @Test void duplicateRefreshClaimsCannotExceedPollingLimit() throws Exception {
        var row=history.create("EMP-001",actor,input());
        assertThat(row.localState()).as("Normal create outcome: %s",row.lastOutcome()).isEqualTo("KNOWN");
        Thread.sleep(110);api.mode="CONCURRENCY";
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);var results=new ArrayList<Future<?>>();
            for(int i=0;i<2;i++) results.add(pool.submit(()->{try {start.await();history.refresh("EMP-001",row.requestId(),actor,true);} catch(InterruptedException e) {throw new RuntimeException(e);} }));
            start.countDown();for(var result:results) result.get(5,TimeUnit.SECONDS);
        }
        assertThat(api.calls.get()).isEqualTo(2);assertThat(mapper.find(row.requestId()).pollCount()).isEqualTo(1);
    }
    @Test void overloadHintBlocksManualReconciliationInsteadOfRetryingPost() {
        api.mode="OVERLOAD";var row=history.create("EMP-001",actor,input());
        assertThat(row.localState()).isEqualTo("UNKNOWN");assertThat(row.nextPollAt()).isAfter(row.observedAt().plusSeconds(1));
        assertThatThrownBy(()->history.candidates("EMP-001",row.requestId())).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->history.resolve("EMP-001",row.requestId(),actor,"CHK-known",true)).isInstanceOf(ResponseStatusException.class);
        assertThat(api.calls.get()).isEqualTo(1);
    }
}
