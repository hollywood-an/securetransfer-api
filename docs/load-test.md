# Concurrency & Load Test

*Proving the money core is correct **under contention** — not just on single-threaded
happy paths.* The claims "atomic," "concurrency-safe," and "idempotent" are only worth
anything if they survive many threads hammering the same rows at once, so this is a
measurement, not an assertion.

The harness is a reproducible JUnit + Testcontainers test
([`ConcurrentTransferStressTest`](../src/test/java/com/securetransfer/api/integration/ConcurrentTransferStressTest.java))
that runs against a **real PostgreSQL** (real row locks, real transactions) and runs on
every CI build. It **asserts** the hard correctness invariants — exact per-account
balances, each reconciled against its own ledger, exactly-once, no oversell — and only
**reports** the throughput/latency (those vary by machine).

## TL;DR

- **300 concurrent transfers** on **one shared account pair**, in both directions with a
  net imbalance → the accounts land on the **exact** expected balances, each reconciles
  with its own ledger, and every leg is accounted for. **0 lost updates, 0 errors.**
- **24 concurrent requests reusing the same `Idempotency-Key`** → the money moved
  **exactly once**. No double-charge.
- **40 concurrent withdrawals from an account that can only cover 10** → **exactly 10
  succeeded**, the account drained to *exactly $0*, never negative. The funds check
  cannot be raced into an overdraft.

## Scenario 1 — maximal lock contention

300 transfers are fired from 32 worker threads at a single account pair `A ⇄ B` (both
funded $500,000). Directions are mixed with a deliberate imbalance — **200 `A→B`** and
**100 `B→A`** — so opposing transfers contend on *both* rows (exercising the
deadlock-ordered `SELECT … FOR UPDATE`) **and** the end state is non-trivial. Because the
code locks accounts in ascending id order, opposing transfers can't deadlock; they
serialize safely on the hot pair.

| Metric | Result |
|--------|-------:|
| Transfers | 300 (200 `A→B`, 100 `B→A`) |
| Concurrent workers | 32 |
| Succeeded (HTTP 201) | **300 / 300** |
| Errors | **0** |
| Wall-clock | 1,200 ms |
| Throughput | **250 transfers/sec** |
| Latency p50 | 123.0 ms |
| Latency p95 | **159.5 ms** |
| Latency p99 | 174.3 ms |
| Latency max | 184.8 ms |

**Asserted invariants (all passed):**

- **Exact balances** — `A = $490,000`, `B = $510,000` (net 100 transfers × $100 moved
  A→B). A "money never moved" or a lost-update bug would leave the wrong balance and fail
  here — this is the real proof of no lost updates.
- **Ledger reconciliation** — each account independently satisfies
  `balance = opening + Σ(its credits) − Σ(its debits)`.
- **Double-entry complete** — exactly **600** ledger legs for 300 transfers.

## Scenario 2 — idempotency under concurrency

24 requests fire simultaneously with the **same** `Idempotency-Key` and body (a $5,000
transfer), racing to process the "same" payment.

**Asserted:** the sender was charged **exactly once** ($5,000), the receiver credited
exactly once, and there are **exactly two** ledger legs — regardless of the race. The
idempotency claim commits a `REQUIRES_NEW` "in-progress" marker before the transfer runs,
so a concurrent duplicate loses cleanly with a `409` instead of double-spending. In a
representative run **1** request processed and **23** got `409`; that split is
race-dependent (a duplicate arriving after the winner commits would replay the stored
`201`), so only "exactly once" is asserted — never the split.

## Scenario 3 — the funds check can't be raced

An account is funded with **$1,000** (enough for ten $100 withdrawals), then **40**
withdrawals fire concurrently.

**Asserted:** **exactly 10** succeeded and **30** were rejected `422 insufficient funds`;
the source drained to **exactly $0** (never negative), the destination received exactly
$1,000, and there are exactly 20 ledger legs. Because the funds check runs *under* the
row lock, concurrent withdrawals serialize and can never oversell the balance.

## Environment & reproducing

- **PostgreSQL 16** via Testcontainers (a throwaway container per run); the app runs
  in-process and each request goes through the **full in-process stack** — Spring MVC +
  the security filter chain + the `@Transactional` service + JPA/JDBC to real Postgres —
  via `MockMvc`. The reported latency is timed around that whole request, excluding only
  the client↔server network hop. Simultaneous open DB transactions are bounded by the
  connection pool (sized to 32 for this test); on one hot pair the money-moving critical
  section serializes by design, so 250/sec is the *contended* ceiling — independent
  account pairs would run in parallel (not benchmarked here). Numbers are a representative
  local run and vary by machine; **the invariants are deterministic and gate CI.**

```bash
./gradlew test --tests "com.securetransfer.api.integration.ConcurrentTransferStressTest" --info
```
