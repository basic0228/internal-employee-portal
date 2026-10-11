package com.bit.portal.auth;

import java.io.Serial;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public final class PortalPrincipal implements UserDetails {
    @Serial private static final long serialVersionUID = 1L;
    private final long accountId;
    private final String employeeId;
    private final String username;
    private final String passwordHash;
    private final String role;
    private final boolean enabled;

    public PortalPrincipal(Account account) {
        accountId = account.id(); employeeId = account.employeeId(); username = account.username();
        passwordHash = account.passwordHash(); role = account.role(); enabled = account.canAccess();
    }
    public long accountId() { return accountId; }
    public String employeeId() { return employeeId; }
    public String role() { return role; }
    @Override public String getUsername() { return username; }
    @Override public String getPassword() { return passwordHash; }
    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }
    @Override public boolean isEnabled() { return enabled; }
}
