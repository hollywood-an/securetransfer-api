package com.securetransfer.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Concurrency stress test — proves the money core is correct UNDER CONTENTION, not
 * just on single-threaded happy paths. Three independent races:
 *
 *  1. Hundreds of concurrent transfers on ONE shared account pair, in BOTH directions
 *     with a net imbalance — maximal row-lock contention (exercises the deadlock-ordered
 *     SELECT ... FOR UPDATE) with a non-trivial expected end state.
 *  2. Many concurrent requests reusing the SAME Idempotency-Key — a duplicate can never
 *     double-charge.
 *  3. More concurrent withdrawals than the balance can cover — the funds check can never
 *     be raced into an overdraft.
 *
 * The hard invariants are ASSERTED (exact per-account balances, each reconciled against
 * its own ledger, exactly-once, no oversell); throughput/latency are only REPORTED
 * (they vary by machine), so the test is deterministic in CI.
 */
class ConcurrentTransferStressTest extends IntegrationTestBase {

    private static final int TRANSFERS = 300;                     // total concurrent transfers
    private static final int THREADS = 32;                        // concurrent workers (matches test pool size)
    private static final BigDecimal AMOUNT = new BigDecimal("100.00");
    private static final BigDecimal FUND = new BigDecimal("500000.00"); // each account

    @Test
    @DisplayName("Hundreds of concurrent bidirectional transfers land on the exact expected balances")
    void concurrentContendedTransfersLandExactly() throws Exception {
        String admin = adminToken();
        TestCustomer c = registerCustomer("stress", "pw-stress-123", "Stress", "stress@example.com");
        long a = createAccount(admin, c.customerId(), "USD", FUND);
        long b = createAccount(admin, c.customerId(), "USD", FUND);

        // Asymmetric workload so the end state is NON-trivial: 1 in 3 goes B->A, the rest
        // A->B. Net = 200 out of A, 100 into A. A "money never moved" bug would leave both
        // at FUND and fail the exact-balance checks below.
        AtomicInteger aToBCount = new AtomicInteger();
        AtomicInteger bToACount = new AtomicInteger();
        AtomicInteger created = new AtomicInteger();
        AtomicInteger nonCreated = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        long[] latencies;
        long wallNanos;
        try {
            List<Callable<Long>> tasks = new ArrayList<>();
            for (int i = 0; i < TRANSFERS; i++) {
                final boolean bToA = (i % 3 == 0);          // 100 B->A, 200 A->B
                tasks.add(() -> {
                    long from = bToA ? b : a;
                    long to = bToA ? a : b;
                    long t0 = System.nanoTime();
                    int status = mockMvc.perform(post("/transfers")
                                    .header("Authorization", bearer(admin))
                                    .header("Idempotency-Key", UUID.randomUUID().toString())
                                    .contentType(APPLICATION_JSON)
                                    .content(transferBody(from, to, AMOUNT.toPlainString())))
                            .andReturn().getResponse().getStatus();
                    long latency = System.nanoTime() - t0;
                    if (status == 201) {
                        created.incrementAndGet();
                        (bToA ? bToACount : aToBCount).incrementAndGet();
                    } else {
                        nonCreated.incrementAndGet();
                    }
                    return latency;
                });
            }
            long wallStart = System.nanoTime();
            List<Future<Long>> futures = pool.invokeAll(tasks);
            wallNanos = System.nanoTime() - wallStart;
            latencies = new long[futures.size()];
            for (int i = 0; i < futures.size(); i++) {
                latencies[i] = futures.get(i).get();
            }
        } finally {
            pool.shutdown();
            pool.awaitTermination(120, TimeUnit.SECONDS);
        }
        Arrays.sort(latencies);

        // --- report (informational; never asserted) ---
        System.out.printf("%n=== CONCURRENCY STRESS: %d contended transfers, %d workers ===%n",
                TRANSFERS, THREADS);
        System.out.printf("created(201)=%d  other=%d  A->B=%d  B->A=%d  wall-clock=%.0f ms  throughput=%.0f/sec%n",
                created.get(), nonCreated.get(), aToBCount.get(), bToACount.get(),
                wallNanos / 1e6, TRANSFERS / (wallNanos / 1e9));
        System.out.printf("latency ms: p50=%.1f  p95=%.1f  p99=%.1f  max=%.1f%n",
                pct(latencies, 50) / 1e6, pct(latencies, 95) / 1e6,
                pct(latencies, 99) / 1e6, latencies[latencies.length - 1] / 1e6);

        // --- assert the HARD invariants ---
        // Every transfer moved money (generously funded, so none hit the funds check).
        assertThat(nonCreated.get()).as("no failed/errored transfers").isZero();
        assertThat(aToBCount.get()).as("A->B count").isEqualTo(200);
        assertThat(bToACount.get()).as("B->A count").isEqualTo(100);

        // 1. EXACT per-account balances — the strong proof of no lost updates. A sent 200
        //    and received 100 (net -100 transfers); B the reverse.
        BigDecimal net = AMOUNT.multiply(BigDecimal.valueOf(100)); // (200-100) * $100 = $10,000
        assertThat(balanceOf(a)).as("A exact balance").isEqualByComparingTo(FUND.subtract(net));
        assertThat(balanceOf(b)).as("B exact balance").isEqualByComparingTo(FUND.add(net));

        // 2. Each balance reconciles against its OWN ledger: balance == fund + credits - debits.
        assertThat(balanceOf(a)).as("A reconciles with its ledger")
                .isEqualByComparingTo(FUND.add(ledgerSum(a, "CREDIT")).subtract(ledgerSum(a, "DEBIT")));
        assertThat(balanceOf(b)).as("B reconciles with its ledger")
                .isEqualByComparingTo(FUND.add(ledgerSum(b, "CREDIT")).subtract(ledgerSum(b, "DEBIT")));

        // 3. Double-entry is complete: exactly two ledger legs per moved transfer.
        long moved = count("SELECT count(*) FROM transfers WHERE from_account IN (?,?) "
                + "AND status IN ('COMPLETED','FLAGGED')", a, b);
        assertThat(moved).as("all transfers moved money").isEqualTo(TRANSFERS);
        assertThat(count("SELECT count(*) FROM ledger_entries WHERE account_id IN (?,?)", a, b))
                .as("two ledger legs per transfer").isEqualTo(2L * TRANSFERS);
    }

    @Test
    @DisplayName("Concurrent duplicate Idempotency-Key requests charge the account exactly once")
    void concurrentDuplicateKeyChargesExactlyOnce() throws Exception {
        String admin = adminToken();
        TestCustomer c = registerCustomer("idem", "pw-idem-123", "Idem", "idem@example.com");
        long from = createAccount(admin, c.customerId(), "USD", FUND);
        long to = createAccount(admin, c.customerId(), "USD", new BigDecimal("0.00"));

        final String sharedKey = UUID.randomUUID().toString();   // the SAME key for every request
        final String body = transferBody(from, to, "5000.00");
        final int duplicates = 24;

        List<Integer> statuses = runConcurrently(duplicates, () -> mockMvc.perform(post("/transfers")
                        .header("Authorization", bearer(admin))
                        .header("Idempotency-Key", sharedKey)
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getStatus());

        // money moved EXACTLY once — no double-charge under the race
        assertThat(balanceOf(from)).as("sender charged exactly once")
                .isEqualByComparingTo(FUND.subtract(new BigDecimal("5000.00")));
        assertThat(balanceOf(to)).as("receiver credited exactly once")
                .isEqualByComparingTo(new BigDecimal("5000.00"));
        assertThat(count("SELECT count(*) FROM ledger_entries WHERE account_id IN (?,?)", from, to))
                .as("exactly one debit + one credit").isEqualTo(2L);

        int ok = tally(statuses, 201), conflict = tally(statuses, 409);
        System.out.printf("%n=== IDEMPOTENCY UNDER CONCURRENCY: %d duplicates, same key ===%n", duplicates);
        System.out.printf("201 (processed/replayed)=%d  409 (in-progress duplicate)=%d  other=%d%n",
                ok, conflict, duplicates - ok - conflict);
        // every duplicate resolved cleanly; the split of 201 vs 409 is race-dependent
        assertThat(ok + conflict).as("only 201 or 409 — never a corrupt/error outcome").isEqualTo(duplicates);
        assertThat(ok).as("the transfer was processed (and possibly replayed)").isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Concurrent withdrawals can never be raced into an overdraft")
    void concurrentWithdrawalsCannotOverdraw() throws Exception {
        String admin = adminToken();
        TestCustomer c = registerCustomer("funds", "pw-funds-123", "Funds", "funds@example.com");
        long src = createAccount(admin, c.customerId(), "USD", new BigDecimal("1000.00")); // covers 10 x $100
        long dst = createAccount(admin, c.customerId(), "USD", new BigDecimal("0.00"));

        final String body = transferBody(src, dst, "100.00");
        final int attempts = 40;                                  // 4x more than can be afforded

        List<Integer> statuses = runConcurrently(attempts, () -> mockMvc.perform(post("/transfers")
                        .header("Authorization", bearer(admin))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getStatus());

        int ok = tally(statuses, 201), rejected = tally(statuses, 422);
        System.out.printf("%n=== FUNDS CHECK UNDER CONCURRENCY: %d withdrawals, only 10 affordable ===%n", attempts);
        System.out.printf("201 (succeeded)=%d  422 (insufficient funds)=%d  other=%d%n",
                ok, rejected, attempts - ok - rejected);

        // exactly 10 succeeded — the app's funds check serialized correctly, no oversell
        assertThat(ok).as("exactly ten withdrawals succeeded").isEqualTo(10);
        assertThat(rejected).as("the rest were cleanly rejected").isEqualTo(attempts - 10);
        assertThat(balanceOf(src)).as("source drained to exactly zero, never negative")
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(balanceOf(dst)).as("destination received exactly ten transfers")
                .isEqualByComparingTo(new BigDecimal("1000.00"));
        assertThat(count("SELECT count(*) FROM ledger_entries WHERE account_id IN (?,?)", src, dst))
                .as("two ledger legs per successful transfer").isEqualTo(20L);
    }

    // ---- helpers ----

    /** Run `n` copies of `task` concurrently on an n-wide pool; return their results. */
    private <T> List<T> runConcurrently(int n, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Callable<T>> tasks = new ArrayList<>();
            for (int i = 0; i < n; i++) tasks.add(task);
            List<T> out = new ArrayList<>();
            for (Future<T> f : pool.invokeAll(tasks)) out.add(f.get());
            return out;
        } finally {
            pool.shutdown();
            pool.awaitTermination(60, TimeUnit.SECONDS);
        }
    }

    private static int tally(List<Integer> statuses, int code) {
        return (int) statuses.stream().filter(s -> s == code).count();
    }

    private BigDecimal ledgerSum(long accountId, String direction) {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM ledger_entries WHERE account_id = ? AND direction = ?",
                BigDecimal.class, accountId, direction);
    }

    /** Percentile (nearest-rank) of a sorted long[]. */
    private static double pct(long[] sorted, int percentile) {
        int idx = (int) Math.ceil(percentile / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(idx, sorted.length - 1))];
    }

    private String transferBody(long from, long to, String amount) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fromAccount", from);
        body.put("toAccount", to);
        body.put("amount", new BigDecimal(amount));
        return json(body);
    }
}
