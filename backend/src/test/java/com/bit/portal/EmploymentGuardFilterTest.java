package com.bit.portal;

import com.bit.portal.auth.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import jakarta.servlet.FilterChain;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class EmploymentGuardFilterTest {
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void databaseFailureNeverPassesRequestToProtectedResource() throws Exception {
        var mapper = mock(AccountMapper.class);
        when(mapper.findById(1L)).thenThrow(new DataAccessResourceFailureException("test DB unavailable"));
        var principal = new PortalPrincipal(new Account(1, "EMP-001", "user", "hash", "USER", true, "ACTIVE"));
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        var request = new MockHttpServletRequest("GET", "/api/me");
        request.setSession(new MockHttpSession());
        var response = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);
        new EmploymentGuardFilter(mapper).doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).doesNotContain("test DB unavailable");
        verifyNoInteractions(chain);
    }
}
