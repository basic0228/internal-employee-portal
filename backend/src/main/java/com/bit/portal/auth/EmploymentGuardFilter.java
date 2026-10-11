package com.bit.portal.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

public final class EmploymentGuardFilter extends OncePerRequestFilter {
    private final AccountMapper accounts;
    public EmploymentGuardFilter(AccountMapper accounts) { this.accounts = accounts; }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        var paths = PathPatternRequestMatcher.withDefaults();
        return !paths.matcher("/api/**").matches(request)
            || paths.matcher("/api/health").matches(request)
            || paths.matcher("/api/health/db").matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof PortalPrincipal principal) {
            Account current;
            try {
                // A session caches authentication, not employment eligibility. Read the DB every API request.
                current = accounts.findById(principal.accountId());
            } catch (DataAccessException unavailable) {
                writeError(response, 503, "재직 상태를 확인할 수 없습니다. 잠시 후 다시 시도하세요.");
                return;
            }
            if (current == null || !current.canAccess() || !current.role().equals(principal.role())) {
                var session = request.getSession(false);
                if (session != null) session.invalidate();
                SecurityContextHolder.clearContext();
                writeError(response, 401, "계정 사용이 제한되었습니다. 관리자에게 문의하세요.");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    static void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"message\":\"" + message + "\"}");
    }
}
