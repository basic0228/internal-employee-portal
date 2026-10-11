package com.bit.portal.lab;

import com.bit.portal.backgroundcheck.client.BackgroundCheckClient;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

/** Loopback only. These assertions verify protocol safety, not external recovery probabilities. */
class LabFollowupTest {
    MockBackgroundApi api; ObjectMapper json; LabRunner runner;
    @BeforeEach void setup() throws Exception {
        api=new MockBackgroundApi(0);json=new ObjectMapper().findAndRegisterModules();
        runner=new LabRunner(new BackgroundCheckClient(json,api.baseUrl(),false),json,true,true);
    }
    @AfterEach void close() {api.close();}
    LabRequest request(LabProtocol.Family family,LabProtocol.Variant variant) {
        var p=new LabProtocol(family,variant,family==LabProtocol.Family.R01 || family==LabProtocol.Family.R03 || family==LabProtocol.Family.C01?1:0,
            family==LabProtocol.Family.R01 || family==LabProtocol.Family.R03 || family==LabProtocol.Family.C01?1:0);
        var type=p.retry()?LabRequest.Type.GET_RETRY:family==LabProtocol.Family.P01?LabRequest.Type.POLL_EXISTING:
            family==LabProtocol.Family.P02?LabRequest.Type.CREATE_POLL:LabRequest.Type.GET_FIXED;
        return new LabRequest("PRIVATE_KEY",type,type==LabRequest.Type.CREATE_POLL?LabRequest.Endpoint.CREATE:LabRequest.Endpoint.LIST,
            "PRIVATE_EMPLOYEE",null,"PRIVATE_FIRST","PRIVATE_LAST","1990-01-01",family==LabProtocol.Family.B01 || family==LabProtocol.Family.C01?3:1,
            1,5000,30000,120000,2000,5000,6,20,true,p,true);
    }
    JsonNode execute(LabRequest r) throws Exception {
        var out=new ByteArrayOutputStream();runner.execute(runner.reserve(1,r),r,out);
        String stream=out.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(stream).doesNotContain("PRIVATE_KEY","PRIVATE_EMPLOYEE","PRIVATE_FIRST","PRIVATE_LAST","1990-01-01","PRIVATE-PENDING-ID");
        return json.readTree(stream.strip().lines().reduce((a,b)->b).orElseThrow()).path("data");
    }
    @Test void firstSuccessDoesNotFillConditionalRetryBudget() throws Exception {
        var r=execute(request(LabProtocol.Family.R01,LabProtocol.Variant.SHORT));
        assertThat(api.calls.get()).isEqualTo(1);assertThat(r.path("logicalOperations").get(0).path("eligibleInitialFailure").asBoolean()).isFalse();
        assertThat(r.path("run").path("state").asText()).isEqualTo("COMPLETED");
    }
    @Test void natural500RecoversOnlyOnceAndSharesOperationIdentity() throws Exception {
        api.mode="RETRY_ONCE";var r=execute(request(LabProtocol.Family.R01,LabProtocol.Variant.SHORT));
        assertThat(api.calls.get()).isEqualTo(2);assertThat(r.path("attempts").get(1).path("retryIndex").asInt()).isEqualTo(1);
        assertThat(r.path("attempts").get(0).path("logicalOperationId")).isEqualTo(r.path("attempts").get(1).path("logicalOperationId"));
        var op=r.path("logicalOperations").get(0);assertThat(op.path("recovered").asBoolean()).isTrue();
        assertThat(op.path("waits").get(0).path("actualWaitMs").asDouble()).isGreaterThanOrEqualTo(1900);
    }
    @Test void repeatedFailureNeverStartsUnbudgetedThirdRequest() throws Exception {
        api.mode="INTERNAL_ERROR";var r=execute(request(LabProtocol.Family.R01,LabProtocol.Variant.SHORT));
        assertThat(api.calls.get()).isEqualTo(2);assertThat(r.path("run").path("state").asText()).isEqualTo("STOPPED");
        assertThat(r.path("logicalOperations").get(0).path("recovered").asBoolean()).isFalse();
    }
    @Test void exponentialActuallyRecordsSecondWaitAndThirdAttempt() throws Exception {
        api.mode="RETRY_TWICE";var r=execute(request(LabProtocol.Family.R03,LabProtocol.Variant.EXPONENTIAL));
        assertThat(api.calls.get()).isEqualTo(3);var op=r.path("logicalOperations").get(0);
        assertThat(op.path("retriesStarted").asInt()).isEqualTo(2);assertThat(op.path("waits").get(1).path("plannedWaitMs").asLong()).isEqualTo(4000);
        assertThat(op.path("recovered").asBoolean()).isTrue();
    }
    @Test void overloadHintIsNeverShortenedToFitDeadline() throws Exception {
        api.mode="OVERLOAD_LONG";var r=execute(request(LabProtocol.Family.R02,LabProtocol.Variant.DEFAULT));
        assertThat(api.calls.get()).isEqualTo(1);assertThat(r.path("run").path("reason").asText()).isEqualTo("RETRY_DEFERRED_BUDGET");
        assertThat(r.path("logicalOperations").get(0).path("retriesStarted").asInt()).isZero();
    }
    @Test void overloadRespectsHeaderBeforeBodyAndRecordsConflict() throws Exception {
        api.mode="OVERLOAD_ONCE";var r=execute(request(LabProtocol.Family.R02,LabProtocol.Variant.DEFAULT));
        assertThat(api.calls.get()).isEqualTo(2);assertThat(r.path("attempts").get(0).path("retryAfter").path("conflict").asBoolean()).isTrue();
        assertThat(r.path("logicalOperations").get(0).path("waits").get(0).path("plannedWaitMs").asLong()).isEqualTo(2000);
    }
    @Test void authAndRateLimitAndContractFailuresNeverRetry() throws Exception {
        for(String mode:List.of("UNAUTHORIZED","NOT_FOUND","RATE_LIMIT","BROKEN")) {
            api.mode=mode;api.calls.set(0);var r=execute(request(LabProtocol.Family.R01,LabProtocol.Variant.SHORT));
            assertThat(api.calls.get()).isEqualTo(1);assertThat(r.path("run").path("state").asText()).isEqualTo("STOPPED");
        }
    }
    @Test void noPendingNeverCreatesAReplacementPost() throws Exception {
        var r=execute(request(LabProtocol.Family.P01,LabProtocol.Variant.DEFAULT));
        assertThat(api.calls.get()).isEqualTo(1);assertThat(api.posts.get()).isZero();
        assertThat(r.path("run").path("reason").asText()).isEqualTo("NO_ELIGIBLE_PENDING");
    }
    @Test void existingPendingUsesSameCheckUntilFlaggedAndPreservesObservationBounds() throws Exception {
        api.seedPending("PRIVATE_EMPLOYEE");api.mode="PENDING_THEN_FLAGGED";
        var r=execute(request(LabProtocol.Family.P01,LabProtocol.Variant.DEFAULT));
        assertThat(api.calls.get()).isEqualTo(3);assertThat(api.posts.get()).isZero();
        var flow=r.path("workflows").get(0);assertThat(flow.path("finalStatus").asText()).isEqualTo("flagged");
        assertThat(flow.path("transitionWindowStart").asText()).isNotBlank();assertThat(r.path("run").path("state").asText()).isEqualTo("COMPLETED");
    }
    @Test void immediatelyFinalPostNeverPollsOrReposts() throws Exception {
        api.mode="IMMEDIATE_FINAL";var r=execute(request(LabProtocol.Family.P02,LabProtocol.Variant.DEFAULT));
        assertThat(api.calls.get()).isEqualTo(1);assertThat(api.posts.get()).isEqualTo(1);
        assertThat(r.path("workflows").get(0).path("postInitialStatus").asText()).isEqualTo("clear");
    }
    @Test void protocolTamperingIsRejectedBeforeAnyCall() {
        var r=request(LabProtocol.Family.R01,LabProtocol.Variant.DEFAULT);
        assertThatThrownBy(()->runner.reserve(1,r)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(api.calls.get()).isZero();
    }
    @Test void cancelDuringRetryWaitPreventsTheRecoveryRequest() throws Exception {
        api.mode="RETRY_ONCE";var r=request(LabProtocol.Family.R01,LabProtocol.Variant.SHORT);
        var control=runner.reserve(1,r);var out=new ByteArrayOutputStream();
        try(var pool=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var future=pool.submit(()->runner.execute(control,r,out));
            long bound=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            while(api.calls.get()==0 && System.nanoTime()<bound) Thread.sleep(10);
            assertThat(api.calls.get()).isEqualTo(1);Thread.sleep(100);runner.cancel(control.id,1);
            future.get(3,java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(api.calls.get()).isEqualTo(1);assertThat(out.toString()).contains("USER_CANCELLED");
    }
    @Test void storageFailureAfterInitialErrorNeverStartsARetry() {
        api.mode="RETRY_ONCE";
        var store=org.mockito.Mockito.mock(LabRecordingService.class);
        org.mockito.Mockito.when(store.enabled()).thenReturn(true);
        var count=new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doAnswer(invocation->{if(count.incrementAndGet()>1) throw new IllegalStateException("mock storage failure");return null;})
            .when(store).checkpoint(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.any());
        runner.recordings(store);var r=request(LabProtocol.Family.R01,LabProtocol.Variant.SHORT);
        runner.execute(runner.reserve(1,r),r,new ByteArrayOutputStream());assertThat(api.calls.get()).isEqualTo(1);
    }
}
