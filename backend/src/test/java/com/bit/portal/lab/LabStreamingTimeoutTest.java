package com.bit.portal.lab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.assertThat;

/** Real embedded servlet tests: MockMvc does not fire the container's async timer. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "server.address=127.0.0.1", "spring.mvc.async.request-timeout=100ms",
    "spring.datasource.url=jdbc:h2:mem:lab-stream;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.sql.init.mode=always", "spring.sql.init.encoding=UTF-8",
    "spring.sql.init.schema-locations=classpath:db/migration/V1__employees_and_accounts.sql,classpath:db/migration/V3__employee_contact_fields.sql",
    "spring.sql.init.data-locations=classpath:db/migration/V2__required_employee_seed.sql",
    "portal.bootstrap.admin-username=stream-admin", "portal.bootstrap.admin-password=StreamTestPassword1",
    "lab.enabled=true"
})
@ActiveProfiles("portal-dev")
class LabStreamingTimeoutTest {
    static final MockBackgroundApi api=createApi();
    static MockBackgroundApi createApi() { try { return new MockBackgroundApi(0); } catch(Exception e) { throw new IllegalStateException(e); } }
    @DynamicPropertySource static void destination(DynamicPropertyRegistry registry) {
        registry.add("background-check.base-url",api::baseUrl);
    }
    @AfterAll static void closeApi() { api.close(); }
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    HttpClient http;
    String origin;
    @BeforeEach void login() throws Exception {
        api.mode="BODY_STALL"; api.calls.set(0);
        origin="http://127.0.0.1:"+port;
        http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        var csrf=csrf();
        var login=HttpRequest.newBuilder(URI.create(origin+"/api/auth/login"))
            .header(csrf.path("headerName").asText(),csrf.path("token").asText())
            .header("Content-Type","application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString("username=stream-admin&password=StreamTestPassword1")).build();
        assertThat(http.send(login,HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(204);
    }
    @AfterEach void closeClient() { if(http!=null) http.shutdownNow(); }
    JsonNode csrf() throws Exception {
        return json.readTree(http.send(HttpRequest.newBuilder(URI.create(origin+"/api/auth/csrf")).GET().build(),HttpResponse.BodyHandlers.ofString()).body());
    }
    JsonNode run(int repeats,int requestMs,int durationMs) throws Exception {
        var csrf=csrf();
        String body="""
            {"candidateKey":"LOCAL_TEST_KEY","type":"GET_BASELINE","endpoint":"DETAIL",
             "employeeId":"EMP-001","checkId":"CHK-known","repeats":%d,"concurrency":1,
             "connectTimeoutMs":1000,"requestTimeoutMs":%d,"durationMs":%d,"intervalMs":100,
             "pollIntervalMs":1,"maxPolls":1,"callBudget":%d,"postAcknowledged":false}
            """.formatted(repeats,requestMs,durationMs,repeats);
        var request=HttpRequest.newBuilder(URI.create(origin+"/api/admin/reliability-lab/experiments"))
            .timeout(Duration.ofSeconds(10)).header("Content-Type","application/json")
            .header(csrf.path("headerName").asText(),csrf.path("token").asText())
            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var response=http.send(request,HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        var events=response.body().lines().map(line->{try{return json.readTree(line);}catch(Exception e){throw new IllegalStateException(e);}}).toList();
        assertThat(events.getLast().path("type").asText()).isEqualTo("result");
        return events.getLast().path("data");
    }
    @Test void servletTimerDoesNotCancelRepeatedRequestTimeoutObservations() throws Exception {
        var result=run(2,750,5000);
        assertThat(result.path("run").path("state").asText()).isEqualTo("COMPLETED");
        assertThat(result.path("run").path("reason").isNull()).isTrue();
        assertThat(result.path("attempts").size()).isEqualTo(2);
        for(var attempt:result.path("attempts")) assertThat(attempt.path("outcome").asText()).isEqualTo("REQUEST_TIMEOUT");
    }
    @Test void experimentDeadlineStillStopsTheRunAndPreservesFinalEvent() throws Exception {
        var result=run(3,2000,400);
        assertThat(result.path("run").path("state").asText()).isEqualTo("STOPPED");
        assertThat(result.path("run").path("reason").asText()).isEqualTo("DURATION_LIMIT");
        assertThat(result.path("attempts").size()).isEqualTo(1);
        assertThat(result.path("run").path("notStarted").asInt()).isEqualTo(2);
    }
}
