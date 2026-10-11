package com.bit.portal;

import com.bit.portal.auth.AccountMapper;
import com.bit.portal.employee.Employee;
import com.bit.portal.employee.EmployeeMapper;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:portal;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.sql.init.mode=always", "spring.sql.init.encoding=UTF-8",
    "spring.sql.init.schema-locations=classpath:db/migration/V1__employees_and_accounts.sql,classpath:db/migration/V3__employee_contact_fields.sql",
    "spring.sql.init.data-locations=classpath:db/migration/V2__required_employee_seed.sql",
    "portal.bootstrap.admin-username=test-admin", "portal.bootstrap.admin-password=TestPassword1"
})
@ActiveProfiles("portal-dev")
@AutoConfigureMockMvc
class PortalIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired EmployeeMapper employees;
    @Autowired AccountMapper accounts;
    @Autowired PasswordEncoder encoder;

    @BeforeEach
    void resetOnlyInMemoryTestDatabase() {
        // This test context explicitly uses H2, never the Railway datasource.
        jdbc.update("DELETE FROM user_accounts");
        jdbc.update("DELETE FROM employees");
        seed();
        accounts.insert(null, "test-admin", encoder.encode("TestPassword1"), "ADMIN");
        accounts.insert("EMP-001", "test-user", encoder.encode("TestPassword1"), "USER");
    }

    private void seed() {
        var populator = new ResourceDatabasePopulator(new ClassPathResource("db/migration/V2__required_employee_seed.sql"));
        populator.setSqlScriptEncoding("UTF-8");
        populator.execute(jdbc.getDataSource());
    }

    private MockHttpSession login(String username) throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/auth/login").with(csrf())
                .param("username", username).param("password", "TestPassword1"))
            .andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
    }
    private String createBody(String id, String username) {
        return """
            {"employeeId":"%s","fullName":"테스트직원","dateOfBirth":"1990-01-01",
             "username":"%s","password":"TestPassword1","role":"ADMIN"}
            """.formatted(id, username);
    }

    @Test void requiredSeedIsExactAndRepeatedSeedPreservesChanges() {
        List<Employee> seed = employees.findAll();
        assertThat(seed).extracting(Employee::employeeId, Employee::fullName, Employee::dateOfBirth)
            .containsExactly(
                tuple("EMP-001", "김민준", LocalDate.parse("1990-03-15")),
                tuple("EMP-002", "김민준", LocalDate.parse("1994-11-02")),
                tuple("EMP-003", "남궁서준", LocalDate.parse("1988-07-21")),
                tuple("EMP-004", "황보라온", LocalDate.parse("1995-02-09")),
                tuple("EMP-005", "김솔", LocalDate.parse("1992-12-30")),
                tuple("EMP-006", "선우진", LocalDate.parse("1991-05-05")),
                tuple("EMP-007", "이서연", null),
                tuple("EMP-008", "박민준", LocalDate.parse("1993-08-17")),
                tuple("EMP-009", "최지우", LocalDate.parse("1996-04-03")),
                tuple("EMP-010", "정하윤", LocalDate.parse("1989-10-11")));
        jdbc.update("UPDATE employees SET full_name = '정정된이름' WHERE employee_id = 'EMP-003'");
        seed();
        assertThat(employees.findAll()).hasSize(10);
        assertThat(employees.findById("EMP-003").fullName()).isEqualTo("정정된이름");
    }

    @Test void anonymousCannotReadEmployees() throws Exception {
        mvc.perform(get("/api/admin/employees")).andExpect(status().isUnauthorized());
    }

    @Test void userCannotUseAdministratorApisOrReadAnotherEmployee() throws Exception {
        var user = login("test-user");
        mvc.perform(get("/api/admin/employees").session(user)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/employees/EMP-002").session(user)).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/employees").session(user).with(csrf())
                .contentType("application/json").content(createBody("EMP-011", "other")))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/me").session(user).param("employeeId", "EMP-002"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.employeeId").value("EMP-001"))
            .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test void administratorCreatesOnlyUserAndHashesPassword() throws Exception {
        mvc.perform(post("/api/admin/employees").session(login("test-admin")).with(csrf())
                .contentType("application/json").content(createBody("EMP-011", "new-user")))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.employeeId").value("EMP-011"))
            .andExpect(jsonPath("$.password").doesNotExist()).andExpect(jsonPath("$.passwordHash").doesNotExist());
        var account = accounts.findByUsername("new-user");
        assertThat(account.role()).isEqualTo("USER");
        assertThat(account.passwordHash()).isNotEqualTo("TestPassword1");
        assertThat(encoder.matches("TestPassword1", account.passwordHash())).isTrue();
        mvc.perform(get("/api/me").session(login("new-user")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.employeeId").value("EMP-011"));
    }

    @Test void duplicateUsernameRollsBackNewEmployee() throws Exception {
        mvc.perform(post("/api/admin/employees").session(login("test-admin")).with(csrf())
                .contentType("application/json").content(createBody("EMP-011", "test-user")))
            .andExpect(status().isConflict());
        assertThat(employees.findById("EMP-011")).isNull();
    }

    @Test void existingSeedCanGetAccountWithoutInventingMissingBirthDate() throws Exception {
        mvc.perform(post("/api/admin/employees").session(login("test-admin")).with(csrf())
                .contentType("application/json").content("""
                    {"employeeId":"EMP-007","fullName":"이서연","dateOfBirth":null,
                     "username":"missing-birth","password":"TestPassword1"}
                    """))
            .andExpect(status().isCreated());
        assertThat(employees.findAll()).hasSize(10);
        assertThat(employees.findById("EMP-007").dateOfBirth()).isNull();
    }

    @Test void conflictingSeedDetailsAreNotOverwritten() throws Exception {
        mvc.perform(post("/api/admin/employees").session(login("test-admin")).with(csrf())
                .contentType("application/json").content(createBody("EMP-002", "second-user")))
            .andExpect(status().isConflict());
        assertThat(employees.findById("EMP-002").fullName()).isEqualTo("김민준");
        assertThat(accounts.findByUsername("second-user")).isNull();
    }

    @Test void protectedMutationAndLoginRequireCsrf() throws Exception {
        mvc.perform(post("/api/admin/employees").session(login("test-admin"))
                .contentType("application/json").content(createBody("EMP-011", "new-user")))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/login").param("username", "test-user").param("password", "TestPassword1"))
            .andExpect(status().isForbidden());
    }

    @Test void actualMaskedCsrfEndpointWorksWithLogin() throws Exception {
        var result = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.getResponse().getContentAsString());
        mvc.perform(post("/api/auth/login")
                .session((MockHttpSession) result.getRequest().getSession(false))
                .header(json.get("headerName").asText(), json.get("token").asText())
                .param("username", "test-user").param("password", "TestPassword1"))
            .andExpect(status().isNoContent());
    }

    @Test void terminationInvalidatesBothPreviouslyIssuedSessionsAndBlocksLogin() throws Exception {
        var first = login("test-user");
        var second = login("test-user");
        mvc.perform(post("/api/admin/employees/EMP-001/termination").session(login("test-admin")).with(csrf()))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/me").session(first)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").session(second)).andExpect(status().isUnauthorized());
        assertThat(first.isInvalid()).isTrue();
        assertThat(second.isInvalid()).isTrue();
        mvc.perform(post("/api/auth/login").with(csrf()).param("username", "test-user").param("password", "TestPassword1"))
            .andExpect(status().isUnauthorized());
    }

    @Test void employmentChangeAloneRevokesSessionEvenIfAccountStillEnabled() throws Exception {
        var session = login("test-user");
        jdbc.update("UPDATE employees SET employment_status = 'TERMINATED', terminated_at = CURRENT_TIMESTAMP WHERE employee_id = 'EMP-001'");
        assertThat(accounts.findByUsername("test-user").enabled()).isTrue();
        mvc.perform(get("/api/me").session(session)).andExpect(status().isUnauthorized());
        assertThat(session.isInvalid()).isTrue();
    }

    @Test void logoutInvalidatesSession() throws Exception {
        var session = login("test-user");
        mvc.perform(post("/api/auth/logout").session(session).with(csrf())).andExpect(status().isNoContent());
        assertThat(session.isInvalid()).isTrue();
    }

    @Test void invalidAndMultibyteOversizedPasswordsAreRejected() throws Exception {
        var admin = login("test-admin");
        mvc.perform(post("/api/admin/employees").session(admin).with(csrf())
                .contentType("application/json").content(createBody("wrong-id", "new-user")))
            .andExpect(status().isBadRequest());
        String body = createBody("EMP-011", "new-user").replace("TestPassword1", "가".repeat(25));
        mvc.perform(post("/api/admin/employees").session(admin).with(csrf())
                .contentType("application/json").content(body))
            .andExpect(status().isBadRequest());
    }

    @Test void publicHealthAndStaticEntryRemainAvailableInDevelopmentProfile() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk());
        mvc.perform(get("/api/health/db")).andExpect(status().isOk());
    }

    @Test void contactSaveUsesSessionOwnerAndPreservesPersonnelFields() throws Exception {
        jdbc.update("UPDATE employees SET updated_at = TIMESTAMP WITH TIME ZONE '2000-01-01 00:00:00+00' WHERE employee_id = 'EMP-001'");
        var before = employees.findById("EMP-001");
        var other = employees.findById("EMP-002");
        mvc.perform(put("/api/me/contact").session(login("test-user")).with(csrf())
                .param("employeeId", "EMP-002").contentType("application/json").content("""
                    {"email":"  staff@example.test  ","phone":"  +82 (10) 1234-5678  "}
                    """))
            .andExpect(status().isOk()).andExpect(jsonPath("$.employeeId").value("EMP-001"))
            .andExpect(jsonPath("$.email").value("staff@example.test"));
        var saved = employees.findById("EMP-001");
        assertThat(saved.phone()).isEqualTo("+82 (10) 1234-5678");
        assertThat(saved.fullName()).isEqualTo(before.fullName());
        assertThat(saved.dateOfBirth()).isEqualTo(before.dateOfBirth());
        assertThat(saved.employmentStatus()).isEqualTo(before.employmentStatus());
        assertThat(saved.updatedAt()).isAfter(before.updatedAt());
        assertThat(employees.findById("EMP-002")).isEqualTo(other);
        mvc.perform(get("/api/admin/employees/EMP-001").session(login("test-admin")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.email").value("staff@example.test"));
    }

    @Test void contactBodyRejectsEmployeeIdAndPersonnelOrAccountFields() throws Exception {
        var session = login("test-user");
        var before = employees.findById("EMP-001");
        for (String field : List.of("employeeId", "fullName", "dateOfBirth", "employmentStatus", "role", "password")) {
            mvc.perform(put("/api/me/contact").session(session).with(csrf()).contentType("application/json")
                    .content("{\"email\":\"changed@example.test\",\"phone\":null,\"" + field + "\":\"changed\"}"))
                .andExpect(status().isBadRequest());
        }
        assertThat(employees.findById("EMP-001")).isEqualTo(before);
        assertThat(accounts.findByUsername("test-user").role()).isEqualTo("USER");
        assertThat(encoder.matches("TestPassword1", accounts.findByUsername("test-user").passwordHash())).isTrue();
    }

    @Test void invalidContactFormatOrLengthDoesNotChangeStoredData() throws Exception {
        var session = login("test-user");
        var before = employees.findById("EMP-001");
        for (String body : List.of(
                "{\"email\":\"not-an-email\",\"phone\":null}",
                "{\"email\":null,\"phone\":\"abc\"}",
                "{\"email\":null,\"phone\":\"" + "1".repeat(33) + "\"}",
                "{\"email\":\"" + "a".repeat(255) + "@example.test\",\"phone\":null}")) {
            mvc.perform(put("/api/me/contact").session(session).with(csrf()).contentType("application/json").content(body))
                .andExpect(status().isBadRequest());
        }
        assertThat(employees.findById("EMP-001")).isEqualTo(before);
    }

    @Test void emptyContactFieldsClearOnlyContacts() throws Exception {
        jdbc.update("UPDATE employees SET email = 'old@example.test', phone = '010-1234-5678' WHERE employee_id = 'EMP-001'");
        mvc.perform(put("/api/me/contact").session(login("test-user")).with(csrf())
                .contentType("application/json").content("{\"email\":\"  \",\"phone\":null}"))
            .andExpect(status().isOk());
        assertThat(employees.findById("EMP-001").email()).isNull();
        assertThat(employees.findById("EMP-001").phone()).isNull();
        assertThat(employees.findById("EMP-001").fullName()).isEqualTo("김민준");
    }

    @Test void contactMutationRequiresAuthenticationAndCsrf() throws Exception {
        String body = "{\"email\":\"staff@example.test\",\"phone\":null}";
        mvc.perform(put("/api/me/contact").with(csrf()).contentType("application/json").content(body))
            .andExpect(status().isUnauthorized());
        mvc.perform(put("/api/me/contact").session(login("test-user")).contentType("application/json").content(body))
            .andExpect(status().isForbidden());
        assertThat(employees.findById("EMP-001").email()).isNull();
    }

    @Test void retiredSessionCannotSaveContact() throws Exception {
        var user = login("test-user");
        mvc.perform(post("/api/admin/employees/EMP-001/termination").session(login("test-admin")).with(csrf()))
            .andExpect(status().isNoContent());
        mvc.perform(put("/api/me/contact").session(user).with(csrf()).contentType("application/json")
                .content("{\"email\":\"staff@example.test\",\"phone\":null}"))
            .andExpect(status().isUnauthorized());
        assertThat(user.isInvalid()).isTrue();
        assertThat(employees.findById("EMP-001").email()).isNull();
    }

    @Test void contactSavePreservesUnknownBirthDate() throws Exception {
        accounts.insert("EMP-007", "missing-birth", encoder.encode("TestPassword1"), "USER");
        mvc.perform(put("/api/me/contact").session(login("missing-birth")).with(csrf()).contentType("application/json")
                .content("{\"email\":null,\"phone\":\"010-1234-5678\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.dateOfBirth").doesNotExist());
        assertThat(employees.findById("EMP-007").dateOfBirth()).isNull();
    }

    @Test void contextPathDoesNotBypassRetiredSessionCheck() throws Exception {
        var session = login("test-user");
        jdbc.update("UPDATE employees SET employment_status = 'TERMINATED', terminated_at = CURRENT_TIMESTAMP WHERE employee_id = 'EMP-001'");
        mvc.perform(get("/portal/api/me").contextPath("/portal").session(session))
            .andExpect(status().isUnauthorized());
        assertThat(session.isInvalid()).isTrue();
    }

    @Test void labAndSensitiveBackgroundResultsAreAdministratorOnly() throws Exception {
        mvc.perform(get("/api/admin/reliability-lab/capabilities")).andExpect(status().isUnauthorized());
        var user=login("test-user");
        mvc.perform(get("/api/admin/reliability-lab/capabilities").session(user)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/employees/EMP-001/background-checks").session(user)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/employees/EMP-001/background-checks/CHK-known").session(user)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/reliability-lab/capabilities").session(login("test-admin")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
    }

    @Test void labExecutionRequiresCsrfAndIsDisabledUntilExplicitActivation() throws Exception {
        String body="""
            {"candidateKey":"private-test-key","type":"GET_BASELINE","endpoint":"DETAIL","employeeId":"EMP-001","checkId":"CHK-known",
             "repeats":1,"concurrency":1,"connectTimeoutMs":1000,"requestTimeoutMs":1000,"durationMs":2000,"intervalMs":0,
             "pollIntervalMs":100,"maxPolls":1,"callBudget":20,"postAcknowledged":false}
            """;
        var admin=login("test-admin");
        mvc.perform(post("/api/admin/reliability-lab/experiments").session(admin).contentType("application/json").content(body))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/reliability-lab/experiments").session(admin).with(csrf()).contentType("application/json").content(body))
            .andExpect(status().isServiceUnavailable()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-test-key"))));
    }
}
