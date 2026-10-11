package com.bit.portal.lab;

import com.bit.portal.backgroundcheck.BackgroundCheckService;
import com.bit.portal.backgroundcheck.client.BackgroundCheckClient;
import com.bit.portal.employee.EmployeeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BackgroundCheckServiceTest {
    MockBackgroundApi api;BackgroundCheckClient client;EmployeeService employees;
    @BeforeEach void setup() throws Exception {
        api=new MockBackgroundApi(0);client=new BackgroundCheckClient(new ObjectMapper().findAndRegisterModules(),api.baseUrl(),false);employees=mock(EmployeeService.class);
    }
    @AfterEach void cleanup() { api.close(); }
    @Test void businessAndLabReuseClientAndOnlyBusinessReturnsSensitiveDetails() {
        var service=new BackgroundCheckService(client,employees,"server-only-test-key",true,1000,1000);
        var result=service.read("EMP-001","CHK-known");
        assertThat(result.path("firstName").asText()).isEqualTo("MOCK_PRIVATE_NAME");
        verify(employees).get("EMP-001");
        assertThat(api.calls.get()).isEqualTo(1);
    }
    @Test void detailMustBelongToRequestedEmployee() {
        var service=new BackgroundCheckService(client,employees,"server-only-test-key",true,1000,1000);
        var error=assertThrows(ResponseStatusException.class,()->service.read("EMP-002","CHK-known"));
        assertThat(error.getStatusCode().value()).isEqualTo(404);
    }
    @Test void disabledBusinessClientDoesNotCallExternalApi() {
        var service=new BackgroundCheckService(client,employees,"server-only-test-key",false,0,0);
        assertThrows(ResponseStatusException.class,()->service.read("EMP-001",null));
        assertThat(api.calls.get()).isZero();
        assertThat(new BackgroundCheckClient(new ObjectMapper(),BackgroundCheckClient.PRODUCTION,false).permitted()).isFalse();
    }
}
