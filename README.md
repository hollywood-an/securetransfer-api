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
cp .env.example .env          # then set DB_PASSWORD, JWT_SECRET, ADMIN_PASSWORD, TELLER_PASSWORD

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

| Variable          | Used by                        | Notes                                        |
|-------------------|--------------------------------|----------------------------------------------|
| `DB_PASSWORD`     | docker-compose **and** the app | Local Postgres password                      |
| `JWT_SECRET`      | the app                        | Signing key for login tokens; **≥ 32 chars** |
| `ADMIN_PASSWORD`  | the app                        | Password for the seeded `admin` login        |
| `TELLER_PASSWORD` | the app                        | Password for the seeded `teller` login       |

All four are **required** (no committed defaults) — the app fails fast if any is
unset. On first startup it seeds two staff logins, `admin` and `teller`, using
`ADMIN_PASSWORD` / `TELLER_PASSWORD`. Public `POST /auth/register` only ever
creates CUSTOMER accounts.

## Project status
- [x] **Phase 0** — Project scaffold, Docker Postgres, Flyway schema (6 tables)
- [x] **Phase 1** — Customers & accounts (create + read balance/ledger)
- [x] **Phase 2** — Atomic, concurrency-safe transfers (double-entry ledger)
- [x] **Phase 3** — Idempotency (Idempotency-Key replay protection)
- [x] **Phase 4** — Auth & RBAC (JWT login, roles, ownership checks)
- [x] **Phase 5** — Audit log (append-only; account freeze; admin `GET /audit`)
- [ ] Phase 6 — Agentic fraud triage
- [ ] Phase 7 — Integration tests
- [ ] Phase 8 — CI/CD + deploy

## API endpoints
All endpoints except `/auth/**` require a JWT: log in, then send
`Authorization: Bearer <token>` on each request.

| Method | Path             | Access       | Purpose                                        |
|--------|------------------|--------------|------------------------------------------------|
| POST   | `/auth/register` | public       | Self-service signup (creates a CUSTOMER)       |
| POST   | `/auth/login`    | public       | Log in → returns a signed JWT                  |
| POST   | `/customers`     | TELLER/ADMIN | Create a customer record                       |
| POST   | `/accounts`      | TELLER/ADMIN | Open an account for a customer                 |
| GET    | `/accounts/{id}` | owner/staff  | Balance + ledger (CUSTOMER: own accounts only) |
| POST   | `/transfers`     | owner/staff  | Move money (CUSTOMER: from own account only)   |
| GET    | `/admin/users`   | ADMIN        | List login accounts                            |
| POST   | `/admin/accounts/{id}/freeze`   | ADMIN | Freeze an account (blocks its transfers) |
| POST   | `/admin/accounts/{id}/unfreeze` | ADMIN | Return an account to normal service      |
| GET    | `/audit`         | ADMIN        | Audit log — filter by actor/action/date, paginated |

Unauthenticated → `401`; authenticated but not allowed → `403`.

Sensitive actions are recorded in an **append-only audit log** (separate from the
money ledger): account freeze/unfreeze and staff viewing a customer's account.
The actor is always the authenticated user, never client-supplied. A transfer
touching a **frozen** account is rejected with `409`.

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
- `V3__users.sql` — adds the `users` table (login accounts: BCrypt password,
  role, optional `customer_id` link).
- `V4__account_status.sql` — adds `accounts.status` (ACTIVE/FROZEN) for the
  admin freeze feature.

Money is `NUMERIC(19,4)`; the ledger and audit log are append-only.
