package com.bit.portal.lab;

import com.bit.portal.backgroundcheck.client.BackgroundCheckClient;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.time.Instant;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** C03 is a bounded independent-sample experiment, never a production retry policy. */
class LabComparisonTest {
    MockBackgroundApi api; ObjectMapper json; LabRunner runner;
    @BeforeEach void setup() throws Exception {
        api=new MockBackgroundApi(0);json=new ObjectMapper().findAndRegisterModules();
        runner=new LabRunner(new BackgroundCheckClient(json,api.baseUrl(),false),json,true,true);
    }
    @AfterEach void close() {api.close();}
    LabRequest request(int c) {
        return new LabRequest("PRIVATE_KEY",LabRequest.Type.GET_FIXED,LabRequest.Endpoint.LIST,"PRIVATE_EMPLOYEE",null,
            null,null,null,3,c,5000,30000,120000,2000,5000,6,3,false,
            new LabProtocol(LabProtocol.Family.C03,LabProtocol.Variant.DEFAULT,1,c),true);
    }
    JsonNode execute(LabRequest r) throws Exception {
        var out=new ByteArrayOutputStream();runner.execute(runner.reserve(1,r),r,out);
        String stream=out.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(stream).doesNotContain("PRIVATE_KEY","PRIVATE_EMPLOYEE","Internal Server Error");
        return json.readTree(stream.strip().lines().reduce((a,b)->b).orElseThrow()).path("data");
    }
    @Test void valid500AndFollowingSuccessAreIndependentSamplesWithoutRetry() throws Exception {
        api.mode="RETRY_ONCE";var r=execute(request(1));
        assertThat(api.calls.get()).isEqualTo(3);assertThat(api.posts.get()).isZero();
        assertThat(r.path("run").path("state").asText()).isEqualTo("COMPLETED");
        assertThat(r.path("attempts").get(0).path("httpCode").asInt()).isEqualTo(500);
        assertThat(r.path("attempts").get(1).path("httpCode").asInt()).isEqualTo(200);
        assertThat(r.path("logicalOperations").size()).isZero();
        for(var a:r.path("attempts")) assertThat(a.path("retryIndex").asInt()).isZero();
    }
    @Test void all500CompleteTheBoundedProcedureAtEveryLevelIncludingPartialGroup() throws Exception {
        api.mode="INTERNAL_ERROR";
        for(int c=1;c<=3;c++) {
            api.calls.set(0);var r=execute(request(c));
            assertThat(api.calls.get()).isEqualTo(3);
            assertThat(r.path("run").path("state").asText()).isEqualTo("COMPLETED");
            assertThat(r.path("run").path("maxClientCallsInFlight").asInt()).isLessThanOrEqualTo(c);
            for(var a:r.path("attempts")) assertThat(a.path("httpCode").asInt()).isEqualTo(500);
            if(c==2) assertThat(r.path("attempts").get(2).path("groupWidth").asInt()).isEqualTo(1);
        }
    }
    @Test void optionalErrorFieldsMayBeAbsentButOverloadHintsAndContractErrorsStop() throws Exception {
        api.mode="ERROR_EMPTY";assertThat(execute(request(3)).path("run").path("state").asText()).isEqualTo("COMPLETED");
        for(String mode:java.util.List.of("OVERLOAD","RATE_LIMIT","ERROR_HINT","ERROR_INVALID_HINT","ERROR_FIELDS","ERROR_JSON","BROKEN")) {
            api.calls.set(0);api.mode=mode;var r=execute(request(1));
            assertThat(api.calls.get()).as(mode).isEqualTo(1);
            assertThat(r.path("run").path("state").asText()).as(mode).isEqualTo("STOPPED");
        }
    }
    @Test void timeoutWith500HeadersStillStopsAndDoesNotCountAsAllowed500() throws Exception {
        var client=mock(BackgroundCheckClient.class);var transport=mock(BackgroundCheckClient.Transport.class);
        when(client.permitted()).thenReturn(true);when(client.source()).thenReturn("LOCAL_MOCK");when(client.open(5000)).thenReturn(transport);
        when(transport.exchange(anyString(),anyString(),isNull(),any(),anyString(),anyInt(),anyLong(),any()))
            .thenReturn(new BackgroundCheckClient.Reply("REQUEST_TIMEOUT",500,30000,0,Instant.now(),Instant.now(),"HTTP_1_1",null,
                new BackgroundCheckClient.Retry(null,null,null,false,false),null,null));
        runner=new LabRunner(client,json,true,true);var r=execute(request(1));
        assertThat(r.path("attempts").size()).isEqualTo(1);assertThat(r.path("run").path("state").asText()).isEqualTo("STOPPED");
        verify(transport,times(1)).exchange(anyString(),anyString(),isNull(),any(),anyString(),anyInt(),anyLong(),any());
    }
    @Test void storageFailureAfter500StopsBeforeAnotherSample() {
        api.mode="INTERNAL_ERROR";var store=mock(LabRecordingService.class);when(store.enabled()).thenReturn(true);
        var checkpoints=new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(i->{if(checkpoints.incrementAndGet()>1) throw new IllegalStateException("storage failure");return null;})
            .when(store).checkpoint(anyString(),anyLong(),any());
        runner.recordings(store);var r=request(1);runner.execute(runner.reserve(1,r),r,new ByteArrayOutputStream());
        assertThat(api.calls.get()).isEqualTo(1);
    }
    @Test void cancelAfter500PreventsAnotherSample() throws Exception {
        api.mode="INTERNAL_ERROR";var r=request(1);var control=runner.reserve(1,r);
        var out=new ByteArrayOutputStream(){@Override public void flush(){if(api.calls.get()>0) runner.cancel(control.id,1);}};
        runner.execute(control,r,out);assertThat(api.calls.get()).isEqualTo(1);assertThat(out.toString()).contains("USER_CANCELLED");
    }
    @Test void invalidLevelAndPostCannotUseTheComparisonException() {
        var r=request(2);
        var invalid=new LabRequest(r.candidateKey(),r.type(),r.endpoint(),r.employeeId(),null,null,null,null,3,1,5000,30000,120000,2000,5000,6,3,false,r.protocol(),true);
        assertThatThrownBy(()->runner.reserve(1,invalid)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        var post=new LabRequest(r.candidateKey(),LabRequest.Type.CREATE_POLL,LabRequest.Endpoint.CREATE,r.employeeId(),null,
            "PRIVATE_FIRST","PRIVATE_LAST","1990-01-01",3,2,5000,30000,120000,2000,5000,6,3,true,r.protocol(),true);
        assertThatThrownBy(()->runner.reserve(1,post)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(api.calls.get()).isZero();
    }
}
