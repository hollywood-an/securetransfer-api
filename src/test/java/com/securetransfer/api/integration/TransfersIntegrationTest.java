package com.securetransfer.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.securetransfer.api.service.IdempotentTransferService;
import com.securetransfer.api.web.dto.CreateTransferRequest;
import com.securetransfer.api.web.dto.TransferResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Integration tests for POST /transfers and the underlying money-movement
 * service. Every test asserts BOTH the HTTP response AND the resulting database
 * state (balances, transfers rows, ledger rows), scoped to the entities each
 * test creates — never global counts.
 *
 * <p>Note on fraud: a FIRST transfer to a brand-new payee pair flags as
 * NEW_PAYEE (status FLAGGED) but STILL completes and moves the money. So the
 * happy-path money tests assert that money moved and that exactly two ledger
 * rows exist for the transfer, rather than asserting status == COMPLETED.
 */
class TransfersIntegrationTest extends IntegrationTestBase {

    // The locking transfer service, exercised directly in the concurrency test.
    @Autowired
    private IdempotentTransferService transferService;

    // ------------------------------------------------------------------
    // helpers (local to this test file)
    // ------------------------------------------------------------------

    /** Build the JSON body for POST /transfers. */
    private String transferBody(long fromAccount, long toAccount, String amount) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fromAccount", fromAccount);
        body.put("toAccount", toAccount);
        body.put("amount", new BigDecimal(amount));
        return json(body);
    }

    /** A fresh, unique idempotency key for every transfer. */
    private String newKey() {
        return UUID.randomUUID().toString();
    }

    // ------------------------------------------------------------------
    // 1. happy path: money moves and the ledger balances
    // ------------------------------------------------------------------

    @Test
    @DisplayName("POST /transfers moves money A->B, returns 201 with sender balance, and writes a balanced two-leg ledger")
    void successfulTransferMovesMoneyAndWritesLedger() throws Exception {
        String admin = adminToken();
        TestCustomer customer = registerCustomer("alice", "pw-alice-123",
                "Alice Example", "alice@example.com");
        long accountA = createAccount(admin, customer.customerId(), "USD", new BigDecimal("100.00"));
        long accountB = createAccount(admin, customer.customerId(), "USD", new BigDecimal("0.00"));

        // --- HTTP ---
        String responseJson = mockMvc.perform(post("/transfers")
                        .header("Authorization", bearer(admin))
                        .header("Idempotency-Key", newKey())
                        .contentType(APPLICATION_JSON)
                        .content(transferBody(accountA, accountB, "30.00")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        // The response exposes the SENDER's resulting balance only (no toBalance).
        assertThat(new BigDecimal(readField(responseJson, "fromBalance")))
                .isEqualByComparingTo("70.00");
        long transferId = Long.parseLong(readField(responseJson, "id"));

        // --- DB: balances moved exactly ---
        assertThat(balanceOf(accountA)).isEqualByComparingTo("70.0000");
        assertThat(balanceOf(accountB)).isEqualByComparingTo("30.0000");

        // --- DB: a transfers row exists for this id with the right amount ---
        BigDecimal transferAmount = jdbc.queryForObject(
                "SELECT amount FROM transfers WHERE id = ?", BigDecimal.class, transferId);
        assertThat(transferAmount).isEqualByComparingTo("30.0000");

        // --- DB: exactly two ledger rows for this transfer ---
        assertThat(count("SELECT count(*) FROM ledger_entries WHERE transfer_id = ?", transferId))
                .isEqualTo(2);
        // one DEBIT on A for 30, one CREDIT on B for 30
        assertThat(count("SELECT count(*) FROM ledger_entries "
                        + "WHERE transfer_id = ? AND account_id = ? AND direction = 'DEBIT' AND amount = 30.0000",
                transferId, accountA)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM ledger_entries "
                        + "WHERE transfer_id = ? AND account_id = ? AND direction = 'CREDIT' AND amount = 30.0000",
                transferId, accountB)).isEqualTo(1);

        // --- DB: the two legs net to zero (double-entry invariant) ---
        BigDecimal net = jdbc.queryForObject(
                "SELECT COALESCE(SUM(CASE WHEN direction = 'DEBIT' THEN -amount ELSE amount END), 0) "
                        + "FROM ledger_entries WHERE transfer_id = ?",
                BigDecimal.class, transferId);
        assertThat(net).isEqualByComparingTo("0");
    }

    // ------------------------------------------------------------------
    // 2. insufficient funds -> 422, fully rolled back
    // ------------------------------------------------------------------

    @Test
    @DisplayName("POST /transfers with insufficient funds returns 422 and rolls back: balances untouched, no transfer/ledger rows")
    void insufficientFundsReturns422AndRollsBack() throws Exception {
        String admin = adminToken();
        TestCustomer customer = registerCustomer("bob", "pw-bob-123",
                "Bob Example", "bob@example.com");
        long accountA = createAccount(admin, customer.customerId(), "USD", new BigDecimal("50.00"));
        long accountB = createAccount(admin, customer.customerId(), "USD", new BigDecimal("0.00"));

        // --- HTTP: 422 Unprocessable Entity ---
        mockMvc.perform(post("/transfers")
                        .header("Authorization", bearer(admin))
                        .header("Idempotency-Key", newKey())
                        .contentType(APPLICATION_JSON)
                        .content(transferBody(accountA, accountB, "1000.00")))
                .andExpect(status().isUnprocessableEntity());

        // --- DB: balances completely unchanged ---
        assertThat(balanceOf(accountA)).isEqualByComparingTo("50.0000");
        assertThat(balanceOf(accountB)).isEqualByComparingTo("0.0000");

        // --- DB: no transfer was recorded from A, no ledger rows touched A ---
        assertThat(count("SELECT count(*) FROM transfers WHERE from_account = ?", accountA))
                .isZero();
        assertThat(count("SELECT count(*) FROM ledger_entries WHERE account_id = ?", accountA))
                .isZero();
    }

    // ------------------------------------------------------------------
    // 3. unknown destination account -> 404
    // ------------------------------------------------------------------

    @Test
    @DisplayName("POST /transfers to an unknown account returns 404 and records no transfer")
    void unknownAccountReturns404() throws Exception {
        String admin = adminToken();
        TestCustomer customer = registerCustomer("carol", "pw-carol-123",
                "Carol Example", "carol@example.com");
        long accountA = createAccount(admin, customer.customerId(), "USD", new BigDecimal("100.00"));
        long unknownAccount = 999999L;

        // --- HTTP: 404 Not Found ---
        mockMvc.perform(post("/transfers")
                        .header("Authorization", bearer(admin))
                        .header("Idempotency-Key", newKey())
                        .contentType(APPLICATION_JSON)
                        .content(transferBody(accountA, unknownAccount, "10.00")))
                .andExpect(status().isNotFound());

        // --- DB: nothing recorded, sender balance intact ---
        assertThat(count("SELECT count(*) FROM transfers WHERE from_account = ?", accountA))
                .isZero();
        assertThat(balanceOf(accountA)).isEqualByComparingTo("100.0000");
    }

    // ------------------------------------------------------------------
    // 4. transfer to the same account -> 400
    // ------------------------------------------------------------------

    @Test
    @DisplayName("POST /transfers from an account to itself returns 400 and records no transfer")
    void sameAccountReturns400() throws Exception {
        String admin = adminToken();
        TestCustomer customer = registerCustomer("dave", "pw-dave-123",
                "Dave Example", "dave@example.com");
        long accountA = createAccount(admin, customer.customerId(), "USD", new BigDecimal("100.00"));

        // --- HTTP: 400 Bad Request ---
        mockMvc.perform(post("/transfers")
                        .header("Authorization", bearer(admin))
                        .header("Idempotency-Key", newKey())
                        .contentType(APPLICATION_JSON)
                        .content(transferBody(accountA, accountA, "10.00")))
                .andExpect(status().isBadRequest());

        // --- DB: nothing recorded, balance intact ---
        assertThat(count("SELECT count(*) FROM transfers WHERE from_account = ?", accountA))
                .isZero();
        assertThat(balanceOf(accountA)).isEqualByComparingTo("100.0000");
    }

    // ------------------------------------------------------------------
    // 5. missing Idempotency-Key header -> 400
    // ------------------------------------------------------------------

    @Test
    @DisplayName("POST /transfers without an Idempotency-Key header returns 400 and records no transfer")
    void missingIdempotencyKeyReturns400() throws Exception {
        String admin = adminToken();
        TestCustomer customer = registerCustomer("erin", "pw-erin-123",
                "Erin Example", "erin@example.com");
        long accountA = createAccount(admin, customer.customerId(), "USD", new BigDecimal("100.00"));
        long accountB = createAccount(admin, customer.customerId(), "USD", new BigDecimal("0.00"));

        // --- HTTP: 400 Bad Request (no Idempotency-Key header at all) ---
        mockMvc.perform(post("/transfers")
                        .header("Authorization", bearer(admin))
                        .contentType(APPLICATION_JSON)
                        .content(transferBody(accountA, accountB, "10.00")))
                .andExpect(status().isBadRequest());

        // --- DB: nothing recorded, balances intact ---
        assertThat(count("SELECT count(*) FROM transfers WHERE from_account = ?", accountA))
                .isZero();
        assertThat(balanceOf(accountA)).isEqualByComparingTo("100.0000");
        assertThat(balanceOf(accountB)).isEqualByComparingTo("0.0000");
    }

    // ------------------------------------------------------------------
    // 6. concurrency: row locking prevents a negative balance
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Two simultaneous transfers of 80 from an account holding 100: exactly one succeeds, balance never goes negative")
    void concurrentTransfersNeverGoNegative() throws Exception {
        String admin = adminToken();
        TestCustomer customer = registerCustomer("frank", "pw-frank-123",
                "Frank Example", "frank@example.com");
        long accountA = createAccount(admin, customer.customerId(), "USD", new BigDecimal("100.00"));
        long accountB = createAccount(admin, customer.customerId(), "USD", new BigDecimal("0.00"));

        // Each thread tries to move 80, but A only has 100 — only ONE can win.
        CreateTransferRequest request =
                new CreateTransferRequest(accountA, accountB, new BigDecimal("80.00"));

        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger failures = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        // Both threads block on this latch, then fire as close to simultaneously
        // as possible so they genuinely contend for the same account row.
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        Runnable attempt = () -> {
            try {
                release.await();
                TransferResponse response = transferService.transfer(newKey(), request);
                if (response != null) {
                    successes.incrementAndGet();
                }
            } catch (RuntimeException e) {
                // Expected for the loser: insufficient funds after the winner debits.
                failures.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        };

        try {
            pool.submit(attempt);
            pool.submit(attempt);
            release.countDown();          // release both threads together
            // Wait for both attempts to finish.
            boolean finished = done.await(30, TimeUnit.SECONDS);
            assertThat(finished).as("both transfer attempts completed").isTrue();
        } finally {
            pool.shutdown();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }

        // --- exactly one winner, one loser ---
        assertThat(successes.get()).as("successful transfers").isEqualTo(1);
        assertThat(failures.get()).as("failed transfers").isEqualTo(1);

        // --- DB: 80 was moved exactly once; balance never went negative ---
        assertThat(balanceOf(accountA)).isEqualByComparingTo("20.0000");
        assertThat(balanceOf(accountB)).isEqualByComparingTo("80.0000");
        assertThat(balanceOf(accountA)).isGreaterThanOrEqualTo(BigDecimal.ZERO);

        // --- DB: exactly one transfer row from A ---
        assertThat(count("SELECT count(*) FROM transfers WHERE from_account = ?", accountA))
                .isEqualTo(1);

        // --- DB: that one transfer wrote exactly its two ledger legs ---
        long transferId = jdbc.queryForObject(
                "SELECT id FROM transfers WHERE from_account = ?", Long.class, accountA);
        assertThat(count("SELECT count(*) FROM ledger_entries WHERE transfer_id = ?", transferId))
                .isEqualTo(2);

        // Sanity: give any async fraud review a moment so it can't write across the
        // next test's truncate boundary (NEW_PAYEE flagging may have queued one).
        awaitUntil(Duration.ofSeconds(5), () ->
                count("SELECT count(*) FROM fraud_reviews WHERE status = 'PENDING'") == 0);
    }
}
