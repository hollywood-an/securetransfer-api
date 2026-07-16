package com.securetransfer.api.config;

import com.securetransfer.api.domain.Role;
import com.securetransfer.api.domain.User;
import com.securetransfer.api.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Seeds the staff login accounts (admin, teller) on startup if they don't exist.
 *
 * Public self-registration only ever creates CUSTOMER users, so staff accounts
 * have to be bootstrapped somehow — we do it here, hashing the password with the
 * app's PasswordEncoder (no password hash is hardcoded in a migration). The
 * passwords come from config with DEV-ONLY defaults; override them via env in
 * any real deployment.
 */
@Component
public class DataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final String adminPassword;
    private final String tellerPassword;
    private final String demoPassword;

    public DataInitializer(UserRepository users,
                           PasswordEncoder passwordEncoder,
                           @Value("${app.seed.admin-password}") String adminPassword,
                           @Value("${app.seed.teller-password}") String tellerPassword,
                           @Value("${app.seed.demo-password:}") String demoPassword) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.adminPassword = adminPassword;
        this.tellerPassword = tellerPassword;
        this.demoPassword = demoPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedStaff("admin", adminPassword, Role.ADMIN);
        seedStaff("teller", tellerPassword, Role.TELLER);
        // A public demo TELLER (see app.seed.demo-password). Skipped when blank so
        // a real deployment can disable it by setting DEMO_PASSWORD to empty.
        if (demoPassword != null && !demoPassword.isBlank()) {
            seedStaff("demo", demoPassword, Role.TELLER);
        }
    }

    private void seedStaff(String username, String rawPassword, Role role) {
        if (users.existsByUsername(username)) {
            return;
        }
        users.save(new User(username, passwordEncoder.encode(rawPassword), role, null));
        log.warn("Seeded {} user '{}' with a configured password — change it in production.",
                role, username);
    }
}
