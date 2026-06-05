package com.securetransfer.api.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.securetransfer.api.fraud.FraudContext;
import com.securetransfer.api.fraud.FraudVerdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

/**
 * Proves the integration-test foundation works before the area suites are built:
 * the app boots against a real Testcontainers Postgres, Flyway applied every
 * migration, the schema exists, the fraud agent is the no-network stub, and the
 * shared setup helpers persist data.
 */
class FoundationSmokeTest extends IntegrationTestBase {

    @Test
    @DisplayName("Flyway applied all migrations and the full schema exists")
    void migrationsAppliedAndSchemaExists() {
        long applied = count("SELECT count(*) FROM flyway_schema_history WHERE success = true");
        assertThat(applied).isGreaterThanOrEqualTo(5); // V1..V5

        for (String table : List.of("customers", "accounts", "transfers", "ledger_entries",
                "idempotency_keys", "audit_log", "fraud_reviews", "users")) {
            long present = count(
                    "SELECT count(*) FROM information_schema.tables WHERE table_name = ?", table);
            assertThat(present).as("table %s exists", table).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("The fraud agent is the stub — never the real Anthropic client")
    void fraudAgentIsStubbed_neverCallsAnthropic() {
        // The injected agent must not be the real Anthropic implementation...
        assertThat(fraudTriageAgent.getClass().getName()).doesNotContain("Anthropic");
        // ...and it returns the deterministic rules-based fallback (no network).
        FraudVerdict verdict = fraudTriageAgent.triage(
                new FraudContext(1L, 1L, 2L, new BigDecimal("25000.00"), List.of("LARGE_AMOUNT")));
        assertThat(verdict.fromAgent()).isFalse();
        assertThat(verdict.model()).isEqualTo("rules-fallback");
    }

    @Test
    @DisplayName("Setup helpers register, log in, and open an account — and it persists")
    void setupHelpersWork() throws Exception {
        TestCustomer alice = registerCustomer("alice", "Password123", "Alice", "alice@example.com");
        long accountId = createAccount(adminToken(), alice.customerId(), "USD", new BigDecimal("100.00"));

        // HTTP succeeded (asserted inside the helpers) AND the DB reflects it.
        assertThat(balanceOf(accountId)).isEqualByComparingTo("100.0000");
        assertThat(count("SELECT count(*) FROM customers WHERE id = ?", alice.customerId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM users WHERE username = 'admin'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM accounts WHERE id = ?", accountId)).isEqualTo(1);
    }
}
