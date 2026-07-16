package com.securetransfer.api.integration;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securetransfer.api.domain.Role;
import com.securetransfer.api.domain.Tenant;
import com.securetransfer.api.domain.User;
import com.securetransfer.api.fraud.FraudTriageAgent;
import com.securetransfer.api.fraud.FraudVerdict;
import com.securetransfer.api.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Shared foundation for all integration tests.
 *
 * <p>Boots the FULL Spring application (MockMvc + Spring Security filter chain)
 * against a REAL throwaway PostgreSQL started by Testcontainers. Flyway applies
 * every migration (V1–V5) to that database, and Hibernate validates the entity
 * mappings against it — so these tests exercise the real SQL, locking, rollback,
 * and constraints, not an in-memory fake.
 *
 * <h2>Fraud agent is stubbed — the real Anthropic API is NEVER called</h2>
 * Three independent safeguards guarantee this regardless of any ANTHROPIC_API_KEY
 * in the environment:
 * <ol>
 *   <li>{@link #properties} forces {@code app.anthropic.api-key} blank (highest
 *       precedence), so even the real agent would short-circuit to its fallback;</li>
 *   <li>{@link StubFraudAgentConfig} replaces {@link FraudTriageAgent} with a
 *       {@code @Primary} stub that returns the deterministic rules-based verdict
 *       and touches no network;</li>
 *   <li>the production agent's own no-key fallback is a final backstop.</li>
 * </ol>
 *
 * <h2>Contract for test subclasses</h2>
 * Extend this class and write {@code @Test} methods (no class-level annotations
 * needed). Each test starts from a clean database with only the seeded
 * {@code admin}/{@code teller} staff present. Use the helpers below to set up
 * data and obtain JWTs, drive the API with {@link #mockMvc}, and assert the
 * resulting database state with {@link #jdbc}.
 *
 * <p>Test-only fraud thresholds (see {@link #properties}): large-amount = 10000,
 * velocity effectively disabled. So a transfer to a brand-NEW payee still flags
 * (NEW_PAYEE) — flagging never blocks — while a repeat small transfer does not.
 * Capture the ids returned by the helpers; do not assume fixed id values.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(IntegrationTestBase.StubFraudAgentConfig.class)
public abstract class IntegrationTestBase {

    // Seeded staff logins (re-created fresh before every test).
    protected static final String ADMIN_USERNAME = "admin";
    protected static final String ADMIN_PASSWORD = "admin-integration-pw";
    protected static final String TELLER_USERNAME = "teller";
    protected static final String TELLER_PASSWORD = "teller-integration-pw";

    // One Postgres container shared by the whole suite (started once).
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Force the fraud agent OFF no matter what ANTHROPIC_API_KEY is in the env.
        registry.add("app.anthropic.api-key", () -> "");
        // Deterministic flagging for tests: large amount >= 10000; velocity off.
        registry.add("app.fraud.large-amount-threshold", () -> "10000.00");
        registry.add("app.fraud.velocity-max-transfers", () -> "1000");
        registry.add("app.fraud.velocity-window-minutes", () -> "60");
    }

    @Autowired protected MockMvc mockMvc;
    @Autowired protected ObjectMapper objectMapper;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected FraudTriageAgent fraudTriageAgent;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    /** A registered customer plus a logged-in JWT for it. */
    public record TestCustomer(long customerId, String username, String password, String token) {
    }

    @BeforeEach
    void resetDatabase() {
        // Let any in-flight async fraud review from a prior test finish, so it
        // can't write across the truncate boundary.
        awaitNoPendingReviews();
        // Wipe all data tables (NOT flyway_schema_history). No RESTART IDENTITY:
        // ids keep climbing across tests, so a stray async task can never collide
        // with a later test's freshly-issued ids.
        jdbc.execute("TRUNCATE TABLE audit_log, ledger_entries, fraud_reviews, "
                + "idempotency_keys, transfers, accounts, users, customers CASCADE");
        seedStaff();
    }

    private void seedStaff() {
        userRepository.save(new User(ADMIN_USERNAME, passwordEncoder.encode(ADMIN_PASSWORD), Role.ADMIN, null, Tenant.STAFF));
        userRepository.save(new User(TELLER_USERNAME, passwordEncoder.encode(TELLER_PASSWORD), Role.TELLER, null, Tenant.STAFF));
    }

    // ----- HTTP / auth helpers -----

    protected String bearer(String token) {
        return "Bearer " + token;
    }

    protected String json(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize test body", e);
        }
    }

    /** Read a top-level field from a JSON response body. */
    protected String readField(String responseJson, String field) {
        try {
            return objectMapper.readTree(responseJson).get(field).asText();
        } catch (Exception e) {
            throw new IllegalStateException("Could not read field '" + field + "'", e);
        }
    }

    /** Log in and return the JWT (asserts 200). */
    protected String login(String username, String password) throws Exception {
        String resp = mockMvc.perform(post("/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content(json(Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return readField(resp, "token");
    }

    protected String adminToken() throws Exception {
        return login(ADMIN_USERNAME, ADMIN_PASSWORD);
    }

    protected String tellerToken() throws Exception {
        return login(TELLER_USERNAME, TELLER_PASSWORD);
    }

    /** Register a CUSTOMER (+ customer profile) and log it in. */
    protected TestCustomer registerCustomer(String username, String password,
                                            String name, String email) throws Exception {
        String resp = mockMvc.perform(post("/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content(json(Map.of("username", username, "password", password,
                                "name", name, "email", email))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long customerId = Long.parseLong(readField(resp, "customerId"));
        return new TestCustomer(customerId, username, password, login(username, password));
    }

    /** Open an account for a customer (as staff) and return its id. */
    protected long createAccount(String staffToken, long customerId,
                                 String currency, BigDecimal initialBalance) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customerId", customerId);
        body.put("currency", currency);
        body.put("initialBalance", initialBalance);
        String resp = mockMvc.perform(post("/accounts")
                        .header("Authorization", bearer(staffToken))
                        .contentType(APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(readField(resp, "id"));
    }

    // ----- DB-state helpers -----

    protected BigDecimal balanceOf(long accountId) {
        return jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, accountId);
    }

    protected long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    // ----- async helpers -----

    /** Poll a condition until true or the timeout elapses (fails the test if not). */
    protected void awaitUntil(Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(100);
        }
        if (!condition.getAsBoolean()) {
            throw new AssertionError("Condition not met within " + timeout);
        }
    }

    private void awaitNoPendingReviews() {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            Long pending = jdbc.queryForObject(
                    "SELECT count(*) FROM fraud_reviews WHERE status = 'PENDING'", Long.class);
            if (pending == null || pending == 0) {
                return;
            }
            sleep(50);
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    /**
     * Replaces the real Anthropic agent with a deterministic stub so integration
     * tests never make a network call. Returns the same rules-based fallback
     * verdict the production fallback would (model = "rules-fallback").
     */
    @TestConfiguration
    public static class StubFraudAgentConfig {
        @Bean
        @Primary
        public FraudTriageAgent stubFraudTriageAgent() {
            return context -> FraudVerdict.fallback(context.flagReasons(),
                    "Stubbed fraud agent (integration tests never call the real Anthropic API).");
        }
    }
}
