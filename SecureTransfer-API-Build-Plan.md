# SecureTransfer API — Flagship Project Build Plan (Beginner Edition)

*A Java / Spring Boot banking transaction backend with an agentic fraud-triage layer. Tailored to the Wells Fargo Technology undergraduate program. Written assuming no prior backend or banking knowledge — every concept is explained from scratch.*

---

## 0. How to read this plan

You'll use **two tools together**:

- **claude.ai (the browser chat)** is your architect and tutor. It can't touch your files. Use it to *understand* a concept and make design decisions before you build.
- **Claude Code (the terminal tool)** is your builder. It lives inside your project folder and can write files, run tests, and commit. Use it to *do* the work.

The rhythm for every phase: **learn the concept here → build it there → run it → when confused, come back here to understand → go back there to fix → commit.**

Section 3 below (Core Concepts) is your "learn" reference. Section 6 (Phases) is your "do" checklist. Each phase points back to the concept it needs. Read Section 3 once now; re-read the relevant concept right before its phase.

Quick orientation on what a "backend" even is: it's a program running on a server that waits for **requests** ("create an account," "transfer \$50"), does something with a **database**, and sends back an **answer**. Your test tool (Postman) is the *client* sending requests; your Spring Boot app is the *server* answering them; PostgreSQL is the *database* where data actually lives. Everything in this plan is about making that middle program behave correctly when it's handling money.

---

## 1. Why this project fits Wells Fargo

Wells Fargo's posting lists specific qualifications. Build and present the project to hit each one on purpose — this table is the lens recruiters and interviewers will use.

| Wells Fargo qualification | How this project demonstrates it |
|---|---|
| Java | Core service is Java + Spring Boot |
| SQL | PostgreSQL with a real relational schema, transactions, constraints |
| Cloud technology | Containerized and deployed to AWS or Render |
| Shipping cycle (Dev → Deploy → Release → CI → CD) | GitHub Actions pipeline runs tests, builds image, deploys on merge |
| **AI (Generative / Agentic), prompt engineering** | **Bounded fraud-triage agent with defined tools, structured verdicts, and human-in-the-loop guardrails** |
| Problem solving + articulating challenges | Concurrency, atomicity, idempotency, and "AI near money" guardrails — all things you can explain crisply in an interview |

The AI layer is the differentiator. Most intern "bank app" projects stop at basic create/read/update/delete. Yours hits a named AI requirement almost no one else will.

> **Eligibility note:** This specific program targets students graduating **Dec 2026–June 2027** (rising seniors). As an earlier-year student you may be a cycle early for *this exact* posting — confirm eligibility — but the project is the right flagship for any 2026–27 finance/tech internship, so build it now.

---

## 2. The big picture — what you're building

A single backend service for fictional bank accounts that lets you:

- Create customer accounts with balances
- Transfer funds between accounts
- Reject insufficient funds and invalid transfers (safely, with nothing left half-done)
- Prevent accidental duplicate payments
- Keep a permanent, unchangeable history of every money movement
- Log in securely and control who can do what
- Keep an audit trail of sensitive actions
- (Differentiator) Have an AI agent investigate suspicious transfers and recommend action

Keep it **one Spring Boot service**, not microservices. One well-organized program is the mature, defensible choice for a project this size and far easier to build and explain.

---

## 3. Core concepts, from zero

These are the ideas that make this a *banking* system instead of a generic app. Each one below maps to a build phase later.

### Atomic transactions (→ Phase 2)
"Atomic" means all-or-nothing. A transfer is really *two* changes: subtract \$50 from Alice, add \$50 to Bob. If the program subtracts from Alice and then crashes before adding to Bob, \$50 vanishes. A database **transaction** groups changes into one indivisible unit: if anything fails, the database **rolls back** — undoes everything to the starting state. In Spring you mark a method `@Transactional` and the framework commits-or-rolls-back for you. Money must never be created or destroyed by a half-finished operation.

### Concurrency and locking (→ Phase 2)
"Concurrent" means *at the same time*. Alice has \$100; two \$80 transfers arrive in the same instant. Both read \$100, both think "plenty," both subtract — now she's at -\$60 and the bank lost money. That's a **race condition**: two operations racing on the same data. A **lock** fixes it: when transfer #1 touches Alice's row it locks it, and transfer #2 must wait until #1 finishes, by which point #2 correctly sees \$20 and is rejected. Two strategies:
- **Pessimistic** — lock the row before anyone else can read it (`SELECT ... FOR UPDATE`). Simplest to reason about.
- **Optimistic** — let everyone read, but keep a `version` number and detect+retry if someone changed the row underneath you. Higher throughput, more to explain.

### Idempotency (→ Phase 3)
Sounds scary, means something simple: *doing the same thing twice has the same effect as doing it once.* Alice taps "Send \$50," her wifi hiccups, she taps again — two identical requests hit your server, and without protection she sends \$100. An **idempotency key** is a unique ID the client attaches ("payment #abc-123"). The server records the key the first time, processes the transfer once, and stores the result. When the duplicate arrives with the same key, the server skips re-running it and just replays the original answer. One real payment, no double charge.

### The immutable ledger (→ Phase 2 & 4)
The "I actually understand banking" idea. The naive approach is one balance number you overwrite (was 100, set to 50) — but then history is gone and a bug can silently corrupt it. Banks use an **append-only ledger**: never edit or delete, only *add* rows. Every transfer writes permanent records — "\$50 left Alice at 3:01pm (transfer #abc)" and "\$50 arrived for Bob (transfer #abc)." "Immutable" means those rows never change. Balance is something you can always reconstruct from history. **Double-entry** means each transfer writes two matching rows (one out, one in) that net to zero — a centuries-old accounting trick that makes the books self-checking.

### Authentication, authorization, JWT, RBAC (→ Phase 4)
Two different questions people confuse. **Authentication** = "who are you?" (logging in, proving identity). **Authorization** = "what are you allowed to do?" A **JWT** (JSON Web Token) handles authentication: on login the server hands you a signed, tamper-proof token (like a wristband) that you show on every later request, so you don't resend your password. **RBAC** (Role-Based Access Control) handles authorization: each user has a *role* (CUSTOMER, TELLER, ADMIN) and rules define what each role may do. A CUSTOMER moves only their own money; only an ADMIN freezes an account. Without RBAC, any logged-in user could drain anyone's account.

### Audit log (→ Phase 5)
Separate from the ledger. The ledger records *money movements*; the audit log records *sensitive actions* — "Admin Dave froze account #44 at 2:14pm," "Teller viewed Alice's history." Also append-only. When something goes wrong (fraud, mistake, a regulator asks), you need an unforgeable record of who did what, when. Building one signals you understand that in finance, accountability isn't optional.

### Integration tests (→ Phase 7)
A *unit test* checks one small function alone. An **integration test** checks the pieces working together against real infrastructure — your code talking to an actual Postgres database, doing a real transfer, confirming the balance and ledger came out right. **Testcontainers** spins up a genuine throwaway Postgres in Docker just for the test, then deletes it. This matters because money bugs hide in the *seams* between code and database (locking, rollback, idempotency) — exactly what a test that fakes the database would miss.

### CI/CD (→ Phase 8)
Two linked habits. **CI** (Continuous Integration): every time you push code, an automated system runs all your tests, catching breakage immediately. **CD** (Continuous Deployment): when tests pass on the main branch, the app is automatically rebuilt and redeployed live, no manual steps. **GitHub Actions** is the robot that does both. Wells Fargo literally lists this as a qualification.

### The fraud-triage agent (→ Phase 6)
When a transfer looks suspicious (huge amount, brand-new payee, many transfers in a minute), instead of a rigid `if` statement you hand the case to an AI **agent** — a model that *investigates* by calling a few **tools** you give it (look up the account, pull recent history, check how fast money's moving) and returns a structured verdict: risk score + reasoning + recommendation (approve / hold / escalate). The critical design choices — and the impressive part — are the guardrails: its tools are **read-only** (it can look, never move money), it only *recommends* while a **human makes the final call** (human-in-the-loop), and every decision is logged.

---

## 4. Architecture overview

```
Client (Postman / simple React demo)
        │  REST + JWT
        ▼
Spring Boot API ──────────────┐
  • Auth (Spring Security/JWT) │
  • Account service           │
  • Transfer service ◄── @Transactional, row locking, idempotency
  • Ledger (append-only)      │
  • Audit log (append-only)   │
  • Fraud-triage agent ◄──────┘ async, on flagged transfers
        │
        ▼
PostgreSQL  (accounts, ledger_entries, idempotency_keys, audit_log, fraud_reviews)
        ▲
        │ read-only tools
Agent (Anthropic SDK) ── get_account / get_history / get_velocity → structured verdict
```

---

## 5. Data model

The single most resume-worthy decision: **do not store balances as a mutable number you overwrite.** Use an append-only ledger and maintain balance inside the transfer transaction.

- **`accounts`** — `id`, `customer_id`, `currency`, `balance` (maintained inside the transfer transaction), `version` (for optimistic locking).
- **`ledger_entries`** — append-only, insert-only. `id`, `transfer_id`, `account_id`, `direction` (DEBIT/CREDIT), `amount`, `created_at`. A transfer writes **two** entries that net to zero. No row is ever updated or deleted — this is your immutable history.
- **`transfers`** — `id`, `idempotency_key`, `from_account`, `to_account`, `amount`, `status`, `created_at`.
- **`idempotency_keys`** — `key` (unique), `request_hash`, `response_snapshot`, `status` (IN_PROGRESS/COMPLETED), `created_at`.
- **`audit_log`** — append-only: `actor`, `action`, `target`, `metadata`, `timestamp`.
- **`fraud_reviews`** — agent output per flagged transfer: `transfer_id`, `risk_score`, `verdict`, `reasoning`, `recommended_action`, `decided_by` (agent vs human).

Use **`BigDecimal`** for money, never `double` (doubles cause rounding errors that are unacceptable for currency). Store amounts as `NUMERIC(19,4)` or as integer cents. Mentioning this unprompted in an interview signals you actually think about finance code.

---

## 6. Phased build plan

Sized for ~5–6 focused weeks alongside coursework. Each phase ends with something that runs and is committed. Build money-safety first (Phases 1–3) — everything else is meaningless if the core can lose money.

> ### Working in parallel with Claude Code (read before starting phases)
>
> Claude Code can run multiple AI workers at once. Used well, this speeds you up; used carelessly, it produces a mess you can't review. Here's how to use it sensibly as a beginner.
>
> **The tools, simplest first:**
> - **Subagents** — isolated helpers the main agent spawns *inside one session*, each with its own context window and task. Best for delegating a self-contained side-task ("write the tests for this") while the main work continues. **Start here.** You stay in one terminal and keep one thread to follow.
> - **Worktrees** (`claude --worktree`) — separate isolated copies of the repo so two full sessions in two terminals never overwrite each other's files. More powerful, but it's an advanced git workflow. **Skip until you're comfortable** (roughly after Phase 5).
> - **Agent teams** — experimental multi-agent coordination. Ignore for now.
>
> **The two rules that keep parallelism from backfiring:**
> 1. **Never parallelize a dependency chain.** Accounts → transfers → idempotency must be built in order; running them "at the same time" just creates collisions. Parallelize only tasks that don't depend on each other.
> 2. **Give each agent its own files.** Two agents editing the same file conflict. Split by file or directory (e.g. one owns the service code, one owns the test files).
>
> **Parallelization map — what's safe to run together:**
>
> | Can run in parallel | Must stay sequential |
> |---|---|
> | Building a feature **+** writing its tests **+** drafting docs/README for it | The feature phases themselves (1 → 2 → 3) |
> | Cross-cutting artifacts: `Dockerfile`, GitHub Actions workflow, Postman collection, architecture diagram | Anything that reads another phase's output before it exists |
> | **Phase 4 (auth)** and **Phase 5 (audit log)** — fairly independent once the core exists | Phase 6 (agent) — needs transfers + history to already exist |
> | Writing integration tests for *already-built* phases while you build a later one | — |
>
> **Example subagent prompt** (use once you're inside a phase):
> > "Build the transfer service for Phase 2. While you do, spin up a subagent to write the Testcontainers integration tests for it in a separate test file, and another subagent to update the README's transfer section. Keep each agent to its own files and report back when all three finish."
>
> **Reality check:** your bottleneck won't be how fast code is written — it's how fast *you* can read and understand it. Don't launch more agents than you can review. One focused session with the occasional delegated subagent is the right speed for learning this.

### Phase 0 — Setup (½ day)
*Goal: a project that compiles, boots, and connects to a database. No features yet.*

**Build steps:**
1. Generate the project at `start.spring.io` (or let Claude Code do it) with: Spring Web, Spring Data JPA, Spring Security, PostgreSQL Driver, Validation, Flyway, Testcontainers. Use Java 25 (the current LTS) and the Gradle or Maven build (either is fine; Gradle is a bit less verbose). For Spring Boot, pick the latest **3.5.x** — it's fully Java 25-ready and has the most tutorials and Stack Overflow answers, which matters while you're learning. (Spring Boot 4.0 also supports Java 25 but is newer with fewer beginner resources.)
2. Add a `docker-compose.yml` that runs one PostgreSQL container with a database, username, and password.
3. Put the database connection details in `application.yml`, reading the password from an environment variable (never hard-code it).
4. Write the first Flyway migration (`V1__init.sql`) that creates the six tables from Section 5, with primary keys, foreign keys, and a unique constraint on `idempotency_keys.key`.
5. Add a `.gitignore` (ignore `/build`, `/target`, `.env`, IDE files) and a README skeleton.

**Tell Claude Code:**
> "Phase 0 only, then stop. Scaffold a Spring Boot 3.5.x / Java 25 project (Gradle) with the dependencies listed in the build plan. Add a docker-compose.yml for Postgres, wire application.yml to read the DB password from an env var, and write a Flyway V1 migration creating the six tables in Section 5 with proper keys and constraints. Explain each file."

**Watch out for:** the app failing to start because Docker/Postgres isn't running (start Docker Desktop first), or because the DB password env var isn't set. Flyway runs migrations automatically on startup — if a migration has a typo, the app won't boot and the error will name the bad line.

**Verify it works:** `docker compose up -d` starts Postgres, the app boots with no errors, and the logs show Flyway applying `V1`. Connect to the DB (e.g. with `psql` or a GUI like TablePlus) and confirm the six tables exist.

**Milestone:** the app boots and connects to Postgres with the schema created.

### Phase 1 — Accounts & balances (week 1)
*Concept: none new — this is your warm-up. You're learning Spring's shape: Controller (handles requests) → Service (business logic) → Repository (talks to the database) → Entity (a row in a table).*

**Build steps:**
1. Create the `Account` and `Customer` JPA entities mapping to your tables. Use `BigDecimal` for `balance`.
2. Create repositories (`AccountRepository`, `CustomerRepository`) — Spring Data generates the SQL for you.
3. Build a service layer with the logic, then a controller exposing:
   - `POST /customers` — create a customer.
   - `POST /accounts` — create an account (starts at balance 0 or a seeded amount).
   - `GET /accounts/{id}` — return balance plus ledger history.
4. Add input validation (`@Valid`, `@NotNull`, positive-amount checks) so bad input is rejected with a clear `400`.
5. Build a Postman collection with these requests saved, and commit it.

**Tell Claude Code:**
> "Phase 1 only. Create Customer and Account entities, repositories, a service layer, and a controller with POST /customers, POST /accounts, and GET /accounts/{id} returning balance and ledger history. Use BigDecimal for money and add validation. Generate a Postman collection. Explain the Controller/Service/Repository layering as you go."

**Watch out for:** using `double` for money (use `BigDecimal`); returning your database entities directly in responses (use small response objects / DTOs instead, so you don't leak internal fields); forgetting validation so negative or null amounts slip through.

**Verify it works:** in Postman, create a customer, create an account, then `GET` it back and see the right balance. Send a bad request (missing field) and confirm you get a `400`, not a 500 crash.

**Milestone:** create accounts and view balances.

### Phase 2 — Transfers done correctly (week 1–2) — *the core*
*Concepts: atomic transactions, concurrency/locking, immutable ledger. Re-read those three in Section 3 before starting — this is the most important phase.*

**Build steps:**
1. Add a `TransferService` with one `@Transactional` method that, in a single unit: loads both accounts, checks the sender has enough, subtracts from the sender, adds to the receiver, and inserts **two** `ledger_entries` (a DEBIT and a CREDIT) plus a `transfers` row.
2. Expose `POST /transfers` (from account, to account, amount).
3. **Insufficient funds → reject with `422`** and roll back (no partial changes). **Unknown account → `404`.** Negative/zero amount → `400`.
4. **Add locking** so two transfers on the same account can't both read a stale balance. Pick one and write a comment explaining the choice:
   - *Pessimistic:* a repository method using `@Lock(LockModeType.PESSIMISTIC_WRITE)` to `SELECT ... FOR UPDATE` the account rows. Lock accounts in a consistent order (e.g. by id) to avoid deadlocks.
   - *Optimistic:* a `@Version` field on `Account`; catch `OptimisticLockException` and retry.
5. Map your custom exceptions to HTTP status codes with an `@ControllerAdvice` handler so errors come back clean.

**Tell Claude Code:**
> "Phase 2 only. Implement a transactional TransferService: load both accounts, verify funds, update balances, and write two double-entry ledger rows plus a transfers row, all in one @Transactional method. Reject insufficient funds with 422 and roll back; unknown account 404. Add pessimistic row locking (SELECT FOR UPDATE) ordered by account id to prevent deadlocks. Add a @ControllerAdvice to map exceptions to status codes. Explain the locking and rollback."

**Watch out for:** the classic mistake of reading the balance, then updating it in separate steps without a lock — that's the race condition; the lock or `@Version` is what prevents it. Also: make sure the *whole* method is one transaction, so a failure after the debit rolls the debit back too. And confirm the two ledger entries' amounts net to zero.

**Verify it works:** transfer money and confirm both balances changed and two ledger rows appeared. Attempt a transfer larger than the balance → expect `422` and **no** change to either balance (proof rollback works). For concurrency, fire two transfers at once (Postman Runner or a quick script) and confirm the balance never goes negative.

**Milestone:** transfers are atomic and safe under simultaneous requests.

### Phase 3 — Idempotency (week 2)
*Concept: idempotency. Re-read it in Section 3 first.*

**Build steps:**
1. Require an `Idempotency-Key` header on `POST /transfers` (reject with `400` if missing).
2. Before processing: try to insert the key into `idempotency_keys` with status IN_PROGRESS. The **unique constraint** on the key is what makes this safe — if the row already exists, this is a repeat.
3. New key → process the transfer, store a snapshot of the response, mark the row COMPLETED.
4. Repeat key that's COMPLETED → return the stored response, do **not** run the transfer again.
5. Repeat key that's still IN_PROGRESS (a request still running) → return `409 Conflict`.
6. Optionally store a hash of the request body so the same key used with *different* details is rejected rather than silently returning the old result.

**Tell Claude Code:**
> "Phase 3 only. Add idempotency to POST /transfers using the Idempotency-Key header and the idempotency_keys table. Use the unique constraint to detect repeats: first call processes and stores the response snapshot (COMPLETED); a repeated completed key returns the stored response without re-executing; an in-progress key returns 409. Reject a missing header with 400. Explain how the unique constraint prevents double-processing."

**Watch out for:** checking "does the key exist?" and then inserting in two separate steps — under load, two requests can both pass the check. Rely on the database's unique constraint (catch the duplicate-key error) instead, so the database is the referee. Make sure the key is stored in the **same transaction** as the transfer, or a crash mid-way can leave a key marked done with no transfer behind it.

**Verify it works:** send the same transfer twice with the same `Idempotency-Key` → money moves **once**, and the second response matches the first. Send two transfers with **different** keys → both process. Send with no key → `400`.

**Milestone:** double-clicking "send" can't double-charge.

### Phase 4 — Auth & RBAC (week 3)
*Concept: authentication/authorization, JWT, RBAC. Re-read in Section 3 first.*

**Build steps:**
1. Add a `User` entity (username, hashed password, role) — store passwords hashed with BCrypt, never in plain text.
2. Add `POST /auth/register` and `POST /auth/login`. Login checks the password and returns a signed JWT containing the user id and role.
3. Add a JWT filter that runs on every request: read the `Authorization: Bearer <token>` header, verify the signature, and load the user into Spring Security's context.
4. Configure Spring Security: `/auth/**` is public; everything else requires a valid token.
5. Add roles CUSTOMER, TELLER, ADMIN and enforce them — method-level rules (`@PreAuthorize`) so only ADMIN can hit admin endpoints, and a check that a CUSTOMER can only transfer **from their own** accounts.
6. Keep the JWT signing secret in an environment variable.

**Tell Claude Code:**
> "Phase 4 only. Add a User entity with BCrypt-hashed passwords and roles (CUSTOMER, TELLER, ADMIN). Add /auth/register and /auth/login that returns a signed JWT. Add a JWT auth filter and Spring Security config: /auth/** public, everything else authenticated. Enforce that a CUSTOMER can only transfer from their own accounts, and restrict admin endpoints to ADMIN. Read the JWT secret from an env var. Explain authentication vs authorization as you build."

**Watch out for:** storing passwords unhashed (an instant red flag for a banking project); committing the JWT secret to git; and the subtle authorization bug where any logged-in user can transfer from *any* account — test that a CUSTOMER is blocked from touching someone else's account, not just that login works.

**Verify it works:** calling a protected endpoint with no token → `401`. Log in, get a token, call it again with the token → success. Log in as one customer and try to transfer from a different customer's account → `403 Forbidden`. Call an admin endpoint as a CUSTOMER → `403`.

**Milestone:** access is enforced, not cosmetic.

### Phase 5 — Audit log (week 3)
*Concept: audit log. Re-read in Section 3 first. (This phase is fairly independent of Phase 4 and can be built alongside it.)*

**Build steps:**
1. Create an `AuditService` with a single `record(actor, action, target, metadata)` method that inserts an append-only `audit_log` row.
2. Call it from every sensitive action: an admin freezing/unfreezing an account, a teller/admin viewing another user's data, a manual fraud override. (Don't log ordinary balance checks — log *sensitive* actions.)
3. Capture *who* did it from the logged-in user, plus a timestamp and a short description.
4. Add an **admin-only** `GET /audit` with filters (by actor, action, date range) and pagination.
5. Make sure audit rows are insert-only — no update or delete endpoints, ever.

**Tell Claude Code:**
> "Phase 5 only. Add an AuditService that appends rows to audit_log (actor, action, target, metadata, timestamp) and call it from sensitive actions: account freeze, viewing another user's data, manual fraud overrides. Add an admin-only GET /audit with filtering and pagination. No update/delete on audit rows. Explain why the audit log is separate from the ledger."

**Watch out for:** logging too much (every read) so the signal drowns in noise — log sensitive actions only; and exposing the audit endpoint to non-admins. Be sure the actor is taken from the authenticated user, not from a value the client sends (which could be faked).

**Verify it works:** perform an admin action (e.g. freeze an account), then `GET /audit` as an admin and see the entry with the correct actor and timestamp. Call `GET /audit` as a CUSTOMER → `403`. Confirm there's no way to edit or delete an entry.

**Milestone:** sensitive actions are traceable.

### Phase 6 — Agentic fraud triage (week 4) — *the WF differentiator*
*Concept: the fraud-triage agent. Re-read in Section 3 first. Build this only after Phases 1–5 are solid — it sits on top of a working system.*

**Build steps:**
1. Add simple rule checks that **flag** (not block) a transfer: amount over a threshold, several transfers in a short window (velocity), or a brand-new payee. A flagged transfer still completes; it's queued for review.
2. When flagged, enqueue an **async** review (e.g. `@Async` or a simple queue) so the transfer response isn't held up waiting on the AI.
3. Build the agent call with the Anthropic SDK. Give it **read-only tools**: `get_account`, `get_transaction_history`, `get_velocity_stats`. The model investigates by calling these, then returns a **structured JSON verdict**: `risk_score` (0–100) and `reasoning`; a fixed policy derives the `recommended_action` from the score (≥70 ESCALATE, ≥40 HOLD, else APPROVE) so the same score always maps to the same action. *(Refined post-launch — the model originally emitted the action too; it was made a deterministic policy mapping. See Finding #3 in [`docs/fraud-agent-evaluation.md`](docs/fraud-agent-evaluation.md).)*
4. Write the verdict to `fraud_reviews` and also log it to `audit_log`.
5. **Human-in-the-loop:** the agent only *recommends*. A TELLER/ADMIN endpoint (`POST /fraud-reviews/{id}/decision`) records the actual human decision. The agent's tools can never move or hold money themselves.
6. Keep the Anthropic API key in an environment variable.

**Tell Claude Code:**
> "Phase 6 only. Add rule-based flagging (large amount, high velocity, new payee) that marks a transfer for review without blocking it, and enqueue an async fraud review. Implement an agent using the Anthropic SDK with three read-only tools (get_account, get_transaction_history, get_velocity_stats) that returns a structured JSON verdict (risk_score, reasoning); derive the recommended_action from the score with fixed bands (≥70 ESCALATE, ≥40 HOLD, else APPROVE) so the same score always maps to the same action. Persist verdicts to fraud_reviews and audit_log. Add a TELLER/ADMIN endpoint to record the human decision. Read the API key from an env var. The agent must be read-only — explain the guardrails."

**Watch out for:** letting the AI call write into your system (keep its tools strictly read-only); blocking the transfer while the agent thinks (run it async); trusting the model's output blindly — validate that its JSON matches the expected shape before saving, and remember the *human* makes the final call. Don't log the API key.

**Verify it works:** make a large/rapid transfer → it completes but appears in the review queue with an AI verdict (score + reasoning + recommendation). Confirm the verdict is in `fraud_reviews` and `audit_log`. Record a human decision and see it stored. Confirm a normal small transfer is **not** flagged.

*Simpler fallback if short on time:* skip the autonomous agent and instead add an admin "explain this account's recent activity" endpoint that sends the history to the model and returns a plain-English summary. Still satisfies the AI requirement, less moving machinery.

**Milestone:** flagged transfers get an AI-generated, logged, human-reviewable risk assessment.

### Phase 7 — Testing (week 5, overlap earlier)
*Concept: integration tests. Re-read in Section 3 first. You can write tests for each phase as you finish it rather than all at the end — that's the better habit.*

**Build steps:**
1. Set up Testcontainers so the test suite boots a **real throwaway Postgres** in Docker, runs your Flyway migrations against it, and tears it down after.
2. Write integration tests that exercise endpoints end-to-end (MockMvc or RestAssured), asserting both the HTTP status and the resulting database state.
3. Cover the guarantees explicitly, one test each:
   - A successful transfer moves money and writes two ledger rows.
   - Insufficient funds → `422` and **no** balance change (rollback).
   - Unknown account → `404`.
   - Same idempotency key twice → money moves once.
   - Two concurrent transfers → balance never goes negative.
   - A customer touching another's account → `403`.
4. Wire the tests to run with a single command (`./gradlew test`).

**Tell Claude Code:**
> "Phase 7. Configure Testcontainers with a real Postgres and Flyway. Write integration tests covering: successful transfer + ledger rows, insufficient funds (422, rollback verified), unknown account (404), idempotent replay (money moves once), concurrent transfers (no negative balance), and unauthorized cross-account access (403). Use MockMvc and assert both status and DB state. Explain what each test proves."

**Watch out for:** testing against an in-memory fake database (like H2) instead of real Postgres — it can hide locking and SQL behavior differences, which defeats the point. Make sure Docker is running, or Testcontainers can't start. Tests that pass but never check the database state aren't really proving the money logic.

**Verify it works:** `./gradlew test` runs and all tests pass with Postgres spinning up in Docker. Deliberately break a guarantee (e.g. remove the funds check) and confirm a test goes red — proof the tests actually guard something.

**Milestone:** a green suite that proves the hard guarantees.

### Phase 8 — CI/CD + deploy (week 5–6)
*Concept: CI/CD. Re-read in Section 3 first.*

**Build steps:**
1. Write a `Dockerfile` that builds and packages the app into a container image.
2. Add a **GitHub Actions** workflow that, on every pull request, checks out the code, sets up Java, and runs the full test suite (Testcontainers works inside Actions). A red build blocks the merge.
3. Add a second workflow (or job) that runs on merge to `main`: build the Docker image and deploy it.
4. **Choose a deploy target:**
   - *Render* — fastest path. Connect the repo, add a managed Postgres, set env vars (DB password, JWT secret, Anthropic key), auto-deploy on push.
   - *AWS* — stronger resume keyword. Push the image to ECR, run it on ECS Fargate (or Elastic Beanstalk) with RDS Postgres. More setup.
5. Set all secrets as environment variables / GitHub Actions secrets — never in the repo.
6. Add a build-status badge and the live URL to the README.

**Tell Claude Code:**
> "Phase 8. Write a Dockerfile for the app. Add a GitHub Actions workflow that runs the full test suite on every pull request and blocks merges on failure. Add deployment on merge to main targeting [Render / AWS], with all secrets as env vars / Actions secrets. Add a status badge to the README. Explain each step of the pipeline."

**Watch out for:** secrets leaking into the repo or into build logs; the deployed app failing because an env var (DB URL, JWT secret, API key) isn't set on the host; and tests passing locally but failing in CI because Docker/Testcontainers behaves slightly differently there — run the workflow early so you catch this before crunch time.

**Verify it works:** open a pull request and watch Actions run the tests automatically. Merge to `main` and watch the app redeploy. Hit the live URL and complete a transfer against the deployed instance.

**Milestone:** push code → tests run → app redeploys automatically.

### Phase 9 (optional) — Lightweight web frontend (week 6+)
*Concept: a frontend is a separate small app that talks to your backend over the same REST endpoints. The backend is the kitchen; this is a simple dining room so the demo has something clickable.*

**Do this only after Phases 1–5 are solid.** The backend is the star; this is polish for the demo video and a chance to show full-stack range. Keep it deliberately small — do not gold-plate it.

- **Stack:** Vite + React (not Next.js — overkill for a simple app that just calls an API). Plain CSS or a little Tailwind. It's a separate folder/repo from the backend.
- **How it connects:** it calls your existing endpoints with `fetch`, stores the JWT from login in memory, and sends it in the `Authorization` header on later requests. No new backend logic — it's a face for what already exists.
- **Four small screens, nothing more:**
  1. **Login** — enter credentials, get a token, hold onto it.
  2. **Account dashboard** — show balance and the transaction history (your ledger, made visible).
  3. **Transfer form** — send money; show the success state *and* the rejected state (insufficient funds / invalid account) so the error handling is visible.
  4. **Admin fraud-review queue** — list flagged transfers with the agent's risk score, reasoning, and recommendation, plus approve/hold buttons (the human-in-the-loop step). This is the screen that makes the AI layer pop on camera.
- **The one gotcha to expect:** **CORS.** Browsers block a frontend from calling a backend on a different origin unless the backend explicitly allows it. You'll add a CORS config to Spring Security permitting your frontend's address. When you hit a "blocked by CORS policy" error, that's what it is — paste it to me and I'll walk you through the fix.
- **Deploy (optional):** push the static frontend to Vercel or Netlify, pointed at your deployed backend URL.
- **Milestone:** a clickable page where you log in, view a balance, make and reject a transfer, and review an AI-flagged transaction.

---

## 7. Repo & demo polish (don't skip — this is what gets read)

- **README** with: a one-line pitch, an architecture diagram (hand-drawn and photographed is fine), the four financial-system concerns you solved, run instructions, and a short "design decisions & tradeoffs" section.
- A **2–3 minute Loom demo** linked at the top: create accounts → transfer → trigger insufficient funds → replay an idempotency key → trigger a flagged transfer and show the agent verdict + audit log.
- Clean commit history (one meaningful commit per phase; squash the "wip" commits).
- A short **design-notes file** explaining *why* you chose your locking strategy, the double-entry ledger, and read-only agent tools. Interviewers love this.

---

## 8. Resume bullets

Lead with the AI version since AI is a named requirement:

- **Built SecureTransfer, a Java Spring Boot + PostgreSQL banking API processing atomic, concurrency-safe fund transfers with an append-only double-entry ledger, idempotency-key replay protection, JWT role-based access, and immutable audit logging; verified guarantees with Testcontainers integration tests and shipped via a GitHub Actions CI/CD pipeline to [AWS/Render].**
- **Designed an agentic fraud-triage layer (Anthropic SDK) with read-only investigative tools and structured risk verdicts, keeping a human in the loop for all hold decisions and logging every AI recommendation for auditability.**

---

## 9. Interview talking points

Be ready to explain each crisply (Wells Fargo values "articulating challenges and solutions"):

1. **How do you stop a transfer from running twice?** → idempotency keys, unique constraint, in-flight handling, stored response replay.
2. **What if two transfers hit the same account at once?** → your locking choice and why; rollback on failure.
3. **Why a ledger instead of just updating a balance?** → immutability, auditability, reconstructable history; double-entry nets to zero.
4. **You put AI near money — isn't that risky?** → bounded read-only tools, AI advises but humans decide, every decision logged. This answer alone can win the interview.
5. **Walk me through your CI/CD.** → tests gate merges, Testcontainers gives a prod-like database, auto-deploy on merge.

---

## 10. Stretch goals (only if ahead of schedule)

- Rate limiting on transfer endpoints.
- Scheduled PDF statement generation per account.
- Observability: structured logging + a metrics endpoint (Micrometer/Actuator).

*(The React admin dashboard that used to live here is now Phase 9.)*

---

## 11. Sequencing note

Build Phases 1–5 first and make them rock-solid — that's the credible banking backend. Phase 6 (the agent) is the differentiator but should sit *on top of* a working system, not substitute for one. A flawless core with a thoughtful AI layer beats a flashy agent on a shaky foundation every time — especially for a bank.