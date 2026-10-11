package com.bit.portal.auth;

import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("portal-dev")
public class AuthController {
    @GetMapping("/api/auth/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        // Return Spring's masked token. The UI sends it back as a header, including on login/logout.
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }

    @GetMapping("/api/auth/me")
    public Map<String, Object> me(@AuthenticationPrincipal PortalPrincipal principal) {
        return Map.of("username", principal.getUsername(), "role", principal.role(),
                "employeeId", principal.employeeId() == null ? "" : principal.employeeId());
    }
}
