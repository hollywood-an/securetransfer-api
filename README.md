# SecureTransfer API

A backend service for fictional bank accounts and transfers — built to be
correct under the conditions that actually matter in finance: atomic
transfers, a concurrency-safe and append-only double-entry ledger,
idempotent payments, role-based access, an immutable audit trail, and an
AI fraud-triage layer with human-in-the-loop guardrails.

> Built as a portfolio flagship project. The full roadmap is in
> [`SecureTransfer-API-Build-Plan.md`](./SecureTransfer-API-Build-Plan.md),
> built in phases, one at a time.

## Stack
- **Java 25**, **Spring Boot 3.5** (Web, Data JPA, Security, Validation)
- **PostgreSQL 16** (local via Docker Compose)
- **Flyway** for database migrations
- **JUnit 5 + Testcontainers** for integration tests
- **Gradle** build, **Docker + GitHub Actions** for CI/CD (later phase)

## Prerequisites
- JDK 25 (the Gradle wrapper builds against Java 25)
- Docker Desktop (running)

## Getting started

```bash
# 1. Create your local secrets file from the template
cp .env.example .env          # then edit .env and set a DB_PASSWORD

# 2. Start PostgreSQL (exposed on localhost:5433 to avoid clashing with any
#    Postgres already running on the standard 5432)
docker compose up -d

# 3. Run the app (it reads DB_PASSWORD from the environment).
#    Load .env into your shell first, then start the app:
set -a; source .env; set +a
./gradlew bootRun

# 4. Run the tests (spins up a throwaway Postgres via Testcontainers)
./gradlew test
```

On startup, Flyway applies the migrations in
`src/main/resources/db/migration` and the app connects to Postgres.

## Configuration
Secrets come from the environment, never from committed files:

| Variable      | Used by                         | Notes                          |
|---------------|---------------------------------|--------------------------------|
| `DB_PASSWORD` | docker-compose **and** the app  | Local Postgres password        |

## Project status
- [x] **Phase 0** — Project scaffold, Docker Postgres, Flyway schema (6 tables)
- [x] **Phase 1** — Customers & accounts (create + read balance/ledger)
- [x] **Phase 2** — Atomic, concurrency-safe transfers (double-entry ledger)
- [x] **Phase 3** — Idempotency (Idempotency-Key replay protection)
- [ ] Phase 4 — Auth & RBAC
- [ ] Phase 5 — Audit log
- [ ] Phase 6 — Agentic fraud triage
- [ ] Phase 7 — Integration tests
- [ ] Phase 8 — CI/CD + deploy

## API endpoints
Phases 1–2 (no authentication yet — Phase 4 adds JWT + roles):

| Method | Path             | Purpose                                          |
|--------|------------------|--------------------------------------------------|
| POST   | `/customers`     | Create a customer                                |
| POST   | `/accounts`      | Create an account for a customer                 |
| GET    | `/accounts/{id}` | Get an account's balance + ledger history        |
| POST   | `/transfers`     | Move money between accounts (atomic, row-locked) |

`POST /transfers` **requires an `Idempotency-Key` header** (`400` if missing).
The first request with a key processes the transfer and stores its response; a
repeat of the same key replays that stored response without moving money again
(`409` if the first is still in progress, or if the key is reused with a
different body). Transfers are atomic (`@Transactional`), use pessimistic row
locking (`SELECT … FOR UPDATE`, accounts locked in id order to avoid deadlocks),
and write two double-entry ledger rows that net to zero. Errors: `404` unknown
account, `422` insufficient funds, `400` invalid request (same-account,
non-positive amount, cross-currency, or missing key).

A ready-to-run Postman collection is in
[`postman/SecureTransfer.postman_collection.json`](./postman/SecureTransfer.postman_collection.json)
(run the requests top-to-bottom; it chains the created ids automatically).

## Database schema
- `V1__init.sql` — six tables: `accounts`, `idempotency_keys`, `transfers`,
  `ledger_entries`, `audit_log`, `fraud_reviews`.
- `V2__customers_and_account_constraints.sql` — adds the `customers` table and
  the `accounts → customers` foreign key.

Money is `NUMERIC(19,4)`; the ledger and audit log are append-only.
