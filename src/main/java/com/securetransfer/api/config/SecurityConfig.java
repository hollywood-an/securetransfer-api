package com.securetransfer.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * TEMPORARY security setup for Phases 1–3.
 *
 * Spring Security is on the classpath (added in Phase 0), and by default it
 * locks down EVERY endpoint behind a login — so without this, POST /customers
 * would return 401. Until Phase 4 builds real JWT authentication and role-based
 * access, we open everything up so the API is testable from Postman/curl.
 *
 * CSRF protection is disabled because this is a stateless JSON API (no browser
 * sessions or HTML forms), so POSTs work without a CSRF token. Phase 4 will
 * replace this whole class with a proper, locked-down configuration.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
