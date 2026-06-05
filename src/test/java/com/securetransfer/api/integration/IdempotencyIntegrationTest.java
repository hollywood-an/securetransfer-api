package com.securetransfer.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.securetransfer.api.web.dto.CreateTransferRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Integration tests for the idempotency contract on POST /transfers (Phase 3).
 *
 * <p>The Idempotency-Key header is what stops a retried (or accidentally
 * double-clicked) transfer from moving money twice. These tests boot the full
 * app against a real Postgres (see {@link IntegrationTestBase}) and check BOTH
 * the HTTP response AND the resulting database state for every scenario:
 * <ul>
 *   <li>a repeat with the SAME key + SAME body replays the stored 201 and moves
 *       money only ONCE;</li>
 *   <li>a missing key is a 400 and changes nothing;</li>
 *   <li>the SAME key with a DIFFERENT body is a 409 (and the original wins);</li>
 *   <li>a key already IN_PROGRESS is a 409 and starts no transfer;</li>
 *   <li>two DIFFERENT keys both process independently.</li>
 * </ul>
 *
 * <p>Note: the first transfer to a brand-new payee gets a 'FLAGGED' status (the
 * fraud rules flag NEW_PAYEE) but still completes — flagging never blocks a
 * transfer, and idempotency behaves identically regardless of status. So these
 * tests assert money movement and replay behavior, NOT the transfer status.
 */
class IdempotencyIntegrationTest extends IntegrationTestBase {

    /**
     * Send POST /transfers with an explicit Idempotency-Key header and return the
     * raw MockMvc result so each test can assert status and read the body.
     */
    private org.springframework.test.web.servlet.ResultActions postTransfer(
            String staffToken, String idempotencyKey,
            long fromAccount, long toAccount, BigDecimal amount) throws Exception {
        // Build the body the same way the production DTO expects it.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fromAccount", fromAccount);
        body.put("toAccount", toAccount);
        body.put("amount", amount);
        return mockMvc.perform(post("/transfers")
                .header("Authorization", bearer(staffToken))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(APPLICATION_JSON)
                .content(json(body)));
    }

    @Test
    @DisplayName("Same Idempotency-Key + same body replays the first response and moves money only once")
    void sameKeySameBodyReplaysAndMovesMoneyOnce() throws Exception {
        String admin = adminToken();
        var alice = registerCustomer("alice1", "pw-alice-12345", "Alice", "alice1@example.com");
        var bob = registerCustomer("bob1", "pw-bob-12345", "Bob", "bob1@example.com");
        long accountA = createAccount(admin, alice.customerId(), "USD", new BigDecimal("100.00"));
        long accountB = createAccount(admin, bob.customerId(), "USD", new BigDecimal("0.00"));

        String key = UUID.randomUUID().toString();

        // First request: claims the key, runs the transfer, returns 201.
        String first = postTransfer(admin, key, accountA, accountB, new BigDecimal("30.00"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id1 = Long.parseLong(readField(first, "id"));

        // Second request with the IDENTICAL key + body: should REPLAY, not re-run.
        String second = postTransfer(admin, key, accountA, accountB, new BigDecimal("30.00"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id2 = Long.parseLong(readField(second, "id"));

        // Same transfer id came back both times -> it was replayed, not redone.
        assertThat(id2).isEqualTo(id1);

        // DB: exactly ONE transfers row carries this key (not two).
        assertThat(count("SELECT count(*) FROM transfers WHERE idempotency_key = ?", key))
                .isEqualTo(1L);
        // That one transfer wrote exactly two ledger legs (one DEBIT + one CREDIT).
        assertThat(count("SELECT count(*) FROM ledger_entries WHERE transfer_id = ?", id1))
                .isEqualTo(2L);
        // Money left A exactly once: 100 - 30 = 70 (NOT 60, which would mean two debits).
        assertThat(balanceOf(accountA)).isEqualByComparingTo("70.0000");
        // The key is now recorded as COMPLETED.
        assertThat(jdbc.queryForObject(
                "SELECT status FROM idempotency_keys WHERE key = ?", String.class, key))
                .isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("Missing Idempotency-Key header is rejected with 400 and creates no transfer")
    void missingIdempotencyKeyIsRejected() throws Exception {
        String admin = adminToken();
        var alice = registerCustomer("alice2", "pw-alice-12345", "Alice", "alice2@example.com");
        var bob = registerCustomer("bob2", "pw-bob-12345", "Bob", "bob2@example.com");
        long accountA = createAccount(admin, alice.customerId(), "USD", new BigDecimal("100.00"));
        long accountB = createAccount(admin, bob.customerId(), "USD", new BigDecimal("0.00"));

        // Same call as a normal transfer, but WITHOUT the Idempotency-Key header.
        // Use the typed DTO here so the body shape is checked at compile time.
        var request = new CreateTransferRequest(accountA, accountB, new BigDecimal("30.00"));
        mockMvc.perform(post("/transfers")
                        .header("Authorization", bearer(admin))
                        .contentType(APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isBadRequest());

        // DB: nothing was attempted from A.
        assertThat(count("SELECT count(*) FROM transfers WHERE from_account = ?", accountA))
                .isEqualTo(0L);
        // A's balance is untouched.
        assertThat(balanceOf(accountA)).isEqualByComparingTo("100.0000");
    }

    @Test
    @DisplayName("Reusing a key with a DIFFERENT body is rejected with 409 and the original transfer stands")
    void reusedKeyWithDifferentBodyConflicts() throws Exception {
        String admin = adminToken();
        var alice = registerCustomer("alice3", "pw-alice-12345", "Alice", "alice3@example.com");
        var bob = registerCustomer("bob3", "pw-bob-12345", "Bob", "bob3@example.com");
        long accountA = createAccount(admin, alice.customerId(), "USD", new BigDecimal("100.00"));
        long accountB = createAccount(admin, bob.customerId(), "USD", new BigDecimal("0.00"));

        String key = UUID.randomUUID().toString();

        // Original request: amount 30, succeeds.
        postTransfer(admin, key, accountA, accountB, new BigDecimal("30.00"))
                .andExpect(status().isCreated());

        // Same key, DIFFERENT amount (50): the request hash won't match -> 409.
        postTransfer(admin, key, accountA, accountB, new BigDecimal("50.00"))
                .andExpect(status().isConflict());

        // DB: still exactly one transfer for this key, and it's the ORIGINAL 30.
        assertThat(count("SELECT count(*) FROM transfers WHERE idempotency_key = ?", key))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT amount FROM transfers WHERE idempotency_key = ?", BigDecimal.class, key))
                .isEqualByComparingTo("30.0000");
        // Only the single 30 debit happened: 100 - 30 = 70 (the rejected 50 never moved).
        assertThat(balanceOf(accountA)).isEqualByComparingTo("70.0000");
    }

    @Test
    @DisplayName("A key that is already IN_PROGRESS is rejected with 409 and starts no transfer")
    void inProgressKeyConflicts() throws Exception {
        String admin = adminToken();
        var alice = registerCustomer("alice4", "pw-alice-12345", "Alice", "alice4@example.com");
        var bob = registerCustomer("bob4", "pw-bob-12345", "Bob", "bob4@example.com");
        long accountA = createAccount(admin, alice.customerId(), "USD", new BigDecimal("100.00"));
        long accountB = createAccount(admin, bob.customerId(), "USD", new BigDecimal("0.00"));

        String key = UUID.randomUUID().toString();

        // Simulate a first request that claimed the key and is still running: seed an
        // IN_PROGRESS row directly (request_hash is a fixed dummy; it won't be checked
        // because IN_PROGRESS short-circuits to 409 before any hash comparison).
        jdbc.update("INSERT INTO idempotency_keys(key, request_hash, status, created_at) "
                + "VALUES (?, ?, 'IN_PROGRESS', now())", key, "deadbeefdeadbeef");

        // A new request reusing that key must NOT proceed while the first is in flight.
        postTransfer(admin, key, accountA, accountB, new BigDecimal("10.00"))
                .andExpect(status().isConflict());

        // DB: no transfer was created for this key, and A's balance is unchanged.
        assertThat(count("SELECT count(*) FROM transfers WHERE idempotency_key = ?", key))
                .isEqualTo(0L);
        assertThat(balanceOf(accountA)).isEqualByComparingTo("100.0000");
    }

    @Test
    @DisplayName("Two DIFFERENT Idempotency-Keys both process and each moves money")
    void twoDifferentKeysBothProcess() throws Exception {
        String admin = adminToken();
        var alice = registerCustomer("alice5", "pw-alice-12345", "Alice", "alice5@example.com");
        var bob = registerCustomer("bob5", "pw-bob-12345", "Bob", "bob5@example.com");
        long accountA = createAccount(admin, alice.customerId(), "USD", new BigDecimal("100.00"));
        long accountB = createAccount(admin, bob.customerId(), "USD", new BigDecimal("0.00"));

        String key1 = UUID.randomUUID().toString();
        String key2 = UUID.randomUUID().toString();

        String first = postTransfer(admin, key1, accountA, accountB, new BigDecimal("10.00"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id1 = Long.parseLong(readField(first, "id"));

        String second = postTransfer(admin, key2, accountA, accountB, new BigDecimal("10.00"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id2 = Long.parseLong(readField(second, "id"));

        // Different keys -> two distinct transfers actually ran.
        assertThat(id2).isNotEqualTo(id1);

        // DB: exactly two transfers originated from A.
        assertThat(count("SELECT count(*) FROM transfers WHERE from_account = ?", accountA))
                .isEqualTo(2L);
        // Both debits landed: 100 - 10 - 10 = 80.
        assertThat(balanceOf(accountA)).isEqualByComparingTo("80.0000");
    }
}
