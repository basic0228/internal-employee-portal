package com.bit.portal.auth;

import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@Profile("portal-dev")
public class AdminBootstrap implements ApplicationRunner {
    private final AccountMapper accounts;
    private final PasswordEncoder encoder;
    private final String username;
    private final String password;

    public AdminBootstrap(AccountMapper accounts, PasswordEncoder encoder,
            @Value("${portal.bootstrap.admin-username:}") String username,
            @Value("${portal.bootstrap.admin-password:}") String password) {
        this.accounts = accounts; this.encoder = encoder; this.username = username; this.password = password;
    }
    @Override
    public void run(ApplicationArguments args) {
        if (accounts.countAdmins() != 0) return; // Never reset existing credentials at startup.
        if (!username.matches("[A-Za-z0-9._-]{3,64}") || password.length() < 8
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalStateException("Set valid PORTAL_ADMIN_USERNAME and PORTAL_ADMIN_PASSWORD for the first administrator.");
        }
        accounts.insert(null, username, encoder.encode(password), "ADMIN");
    }
}
