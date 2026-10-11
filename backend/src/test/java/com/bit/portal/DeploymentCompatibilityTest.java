package com.bit.portal;

import com.bit.portal.employee.EmployeeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:starter;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa", "spring.datasource.password="
})
@AutoConfigureMockMvc
class DeploymentCompatibilityTest {
    @Autowired MockMvc mvc;
    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate jdbc;

    @Test void starterSecurityAndHealthRemainUnchanged() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk());
        mvc.perform(get("/api/health/db")).andExpect(status().isOk());
        mvc.perform(get("/")).andExpect(status().isFound()).andExpect(redirectedUrlPattern("**/login"));
    }

    @Test void defaultProfileDoesNotActivateEmployeeFeaturesOrCreateTables() {
        assertThat(context.getBeansOfType(EmployeeService.class)).isEmpty();
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM information_schema.tables
            WHERE table_schema = 'public' AND table_name IN ('employees', 'user_accounts')
            """, Integer.class)).isZero();
    }
}
