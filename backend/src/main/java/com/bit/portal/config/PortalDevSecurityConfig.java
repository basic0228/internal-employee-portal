package com.bit.portal.config;

import com.bit.portal.auth.AccountMapper;
import com.bit.portal.auth.EmploymentGuardFilter;
import com.bit.portal.auth.PortalPrincipal;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;

@Configuration
@Profile("portal-dev")
@MapperScan({"com.bit.portal.employee", "com.bit.portal.auth"})
public class PortalDevSecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    UserDetailsService portalUsers(AccountMapper accounts) {
        return username -> {
            var account = accounts.findByUsername(username);
            if (account == null) throw new UsernameNotFoundException("Account not found");
            return new PortalPrincipal(account);
        };
    }

    @Bean
    SecurityFilterChain portalSecurity(HttpSecurity http, AccountMapper accounts) throws Exception {
        // Keep Spring's session fixation protection and CSRF defaults. Login uses the standard filter.
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/", "/index.html", "/assets/**", "/favicon.svg", "/icons.svg",
                        "/api/health", "/api/health/db", "/api/auth/csrf", "/api/auth/login").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/**").authenticated()
                .anyRequest().denyAll())
            .requestCache(cache -> cache.disable())
            .formLogin(form -> form.loginProcessingUrl("/api/auth/login")
                .successHandler((request, response, authentication) -> response.setStatus(204))
                .failureHandler((request, response, failure) -> {
                    response.setStatus(401); response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"message\":\"아이디 또는 비밀번호를 확인하세요.\"}");
                }))
            .logout(logout -> logout.logoutUrl("/api/auth/logout")
                .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)))
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, failure) -> response.setStatus(401))
                .accessDeniedHandler((request, response, failure) -> response.setStatus(403)))
            // Runs after session restoration and before CSRF/authorization so retired sessions are invalidated.
            .addFilterAfter(new EmploymentGuardFilter(accounts), SecurityContextHolderFilter.class);
        return http.build();
    }
}
