package com.securetransfer.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

/**
 * Integration tests for the append-only audit log.
 *
 * <p>Every test drives the REAL API through MockMvc and the real Spring Security
 * filter chain (authenticating with genuine JWTs), then asserts BOTH:
 * <ul>
 *   <li>the HTTP response (status + relevant body fields), and</li>
 *   <li>the resulting database state via the inherited {@code jdbc}.</li>
 * </ul>
 *
 * <p>IMPORTANT: the background fraud-review worker can write its own audit rows,
 * so we NEVER assert global {@code audit_log} counts. Every DB assertion is
 * scoped by the specific {@code action} (and {@code target}/{@code actor}) the
 * test is about.
 */
class AuditLogIntegrationTest extends IntegrationTestBase {

    // ----- small local helpers (kept private to this file) -----

    /** Count audit rows matching a given action + target. */
    private long auditRows(String action, String target) {
        return count("SELECT count(*) FROM audit_log WHERE action = ? AND target = ?",
                action, target);
    }

    /** Count audit rows matching action + target + actor. */
    private long auditRows(String action, String target, String actor) {
        return count("SELECT count(*) FROM audit_log WHERE action = ? AND target = ? AND actor = ?",
                action, target, actor);
    }

    /** Register a fresh customer and open one account for them (as admin). */
    private long openCustomerAccount(String adminToken, TestCustomer customer) throws Exception {
        return createAccount(adminToken, customer.customerId(), "USD", new BigDecimal("100.00"));
    }

    @Test
    @DisplayName("Freezing an account returns FROZEN and writes exactly one ACCOUNT_FROZEN audit row by 'admin'")
    void freezeWritesAuditEntry() throws Exception {
        String admin = adminToken();
        TestCustomer customer = registerCustomer("alice", "alice-pw", "Alice", "alice@example.com");
        long accountId = openCustomerAccount(admin, customer);
        String target = "account:" + accountId;

        String body = mockMvc.perform(post("/admin/accounts/{id}/freeze", accountId)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // HTTP: the returned account body reports FROZEN.
        assertThat(readField(body, "status")).isEqualTo("FROZEN");

        // DB: the account row is FROZEN.
        String dbStatus = jdbc.queryForObject(
                "SELECT status FROM accounts WHERE id = ?", String.class, accountId);
        assertThat(dbStatus).isEqualTo("FROZEN");

        // DB: exactly ONE audit row for this freeze, with the admin as actor.
        assertThat(auditRows("ACCOUNT_FROZEN", target)).isEqualTo(1);
        assertThat(auditRows("ACCOUNT_FROZEN", target, ADMIN_USERNAME)).isEqualTo(1);
    }

    @Test
    @DisplayName("Unfreezing an account returns ACTIVE and writes an ACCOUNT_UNFROZEN audit row by 'admin'")
    void unfreezeWritesAuditEntry() throws Exception {
        String admin = adminToken();
        TestCustomer customer = registerCustomer("bob", "bob-password", "Bob", "bob@example.com");
        long accountId = openCustomerAccount(admin, customer);
        String target = "account:" + accountId;

        // First freeze it.
        mockMvc.perform(post("/admin/accounts/{id}/freeze", accountId)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk());

        // Then unfreeze it.
        String body = mockMvc.perform(post("/admin/accounts/{id}/unfreeze", accountId)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // HTTP: the returned account body reports ACTIVE.
        assertThat(readField(body, "status")).isEqualTo("ACTIVE");

        // DB: the account row is back to ACTIVE.
        String dbStatus = jdbc.queryForObject(
                "SELECT status FROM accounts WHERE id = ?", String.class, accountId);
        assertThat(dbStatus).isEqualTo("ACTIVE");

        // DB: an ACCOUNT_UNFROZEN audit row exists for this account, by the admin.
        assertThat(auditRows("ACCOUNT_UNFROZEN", target, ADMIN_USERNAME)).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("GET /audit is ADMIN-only: admin gets 200 with content; customer and teller get 403")
    void auditListIsAdminOnly() throws Exception {
        String admin = adminToken();
        String teller = tellerToken();
        TestCustomer customer = registerCustomer("carol", "carol-pw", "Carol", "carol@example.com");
        long accountId = openCustomerAccount(admin, customer);

        // Produce at least one audit row.
        mockMvc.perform(post("/admin/accounts/{id}/freeze", accountId)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk());

        // ADMIN: 200 with a non-empty page.
        String body = mockMvc.perform(get("/audit")
                        .header("Authorization", bearer(admin))
                        .accept(APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode root = objectMapper.readTree(body);
        assertThat(root.get("content").isArray()).isTrue();
        assertThat(root.get("content").size()).isGreaterThanOrEqualTo(1);
        assertThat(root.get("totalElements").asLong()).isGreaterThanOrEqualTo(1);

        // CUSTOMER: forbidden.
        mockMvc.perform(get("/audit")
                        .header("Authorization", bearer(customer.token())))
                .andExpect(status().isForbidden());

        // TELLER: forbidden.
        mockMvc.perform(get("/audit")
                        .header("Authorization", bearer(teller)))
                .andExpect(status().isForbidden());

        // DB sanity: the freeze row we created really is there.
        assertThat(auditRows("ACCOUNT_FROZEN", "account:" + accountId)).isEqualTo(1);
    }

    @Test
    @DisplayName("GET /audit?action=ACCOUNT_FROZEN returns only ACCOUNT_FROZEN rows")
    void auditFilterByActionReturnsOnlyMatching() throws Exception {
        String admin = adminToken();
        TestCustomer customer = registerCustomer("dave", "dave-password", "Dave", "dave@example.com");
        long accountId = openCustomerAccount(admin, customer);
        String target = "account:" + accountId;

        // Create a freeze (ACCOUNT_FROZEN) and also a view (ACCOUNT_VIEWED), so the
        // filter has something to exclude.
        mockMvc.perform(post("/admin/accounts/{id}/freeze", accountId)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/accounts/{id}", accountId)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk());

        String body = mockMvc.perform(get("/audit")
                        .param("action", "ACCOUNT_FROZEN")
                        .header("Authorization", bearer(admin))
                        .accept(APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode content = objectMapper.readTree(body).get("content");
        assertThat(content.isArray()).isTrue();
        assertThat(content.size()).isGreaterThanOrEqualTo(1);
        for (JsonNode entry : content) {
            assertThat(entry.get("action").asText()).isEqualTo("ACCOUNT_FROZEN");
        }

        // DB sanity: both kinds of rows exist, confirming the filter actually narrowed.
        assertThat(auditRows("ACCOUNT_FROZEN", target)).isEqualTo(1);
        assertThat(auditRows("ACCOUNT_VIEWED", target, ADMIN_USERNAME)).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Staff viewing a customer's account is audited; the owner viewing their own is NOT")
    void staffViewIsAuditedButOwnerViewIsNot() throws Exception {
        String admin = adminToken();
        TestCustomer customer = registerCustomer("erin", "erin-password", "Erin", "erin@example.com");
        long accountId = openCustomerAccount(admin, customer);
        String target = "account:" + accountId;

        // Staff (admin) views the customer's account -> audited.
        mockMvc.perform(get("/accounts/{id}", accountId)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk());

        // DB: an ACCOUNT_VIEWED row by the admin exists for this account.
        assertThat(auditRows("ACCOUNT_VIEWED", target, ADMIN_USERNAME)).isGreaterThanOrEqualTo(1);

        // Owner views their OWN account -> ordinary read, NOT audited.
        mockMvc.perform(get("/accounts/{id}", accountId)
                        .header("Authorization", bearer(customer.token())))
                .andExpect(status().isOk());

        // DB: there must be NO ACCOUNT_VIEWED row attributed to the owner.
        assertThat(auditRows("ACCOUNT_VIEWED", target, customer.username())).isEqualTo(0);
    }

    @Test
    @DisplayName("Audit log is append-only: DELETE /audit is 405 and existing rows are untouched")
    void auditLogIsAppendOnly() throws Exception {
        String admin = adminToken();
        TestCustomer customer = registerCustomer("frank", "frank-pw", "Frank", "frank@example.com");
        long accountId = openCustomerAccount(admin, customer);
        String target = "account:" + accountId;

        // Create a known audit row.
        mockMvc.perform(post("/admin/accounts/{id}/freeze", accountId)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
        assertThat(auditRows("ACCOUNT_FROZEN", target)).isEqualTo(1);

        // There is no delete endpoint: the method is not allowed on /audit.
        mockMvc.perform(delete("/audit")
                        .header("Authorization", bearer(adminToken())))
                .andExpect(status().isMethodNotAllowed());

        // DB: the row we created still exists, unchanged.
        assertThat(auditRows("ACCOUNT_FROZEN", target)).isEqualTo(1);
    }
}
