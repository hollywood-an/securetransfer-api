# CLAUDE.md — SecureTransfer API

Project context for Claude Code. Read this at the start of every session.

## What this project is
A backend service for fictional bank accounts and transfers, built as a portfolio/internship flagship project. The full roadmap lives in `SecureTransfer-API-Build-Plan.md` in this folder — that is the source of truth. Follow its phases **in order, one at a time**.

## Who you're working with
The developer is learning backend development and banking-system concepts. Optimize for clarity and correctness over cleverness:
- After creating or changing files, **explain what each one does in plain English.**
- Prefer straightforward, well-commented code over advanced patterns.
- When a tradeoff exists, briefly state the options and why you chose one.

## Stack
- Java 25 (current LTS), Spring Boot 3.5.x (latest 3.5; fully Java 25-ready, most tutorials)
- Spring Web, Spring Data JPA, Spring Security
- PostgreSQL (run locally via Docker / docker-compose)
- Flyway for database migrations
- JUnit 5 + Testcontainers for integration tests
- Docker, GitHub Actions for CI/CD
- Anthropic SDK for the Phase 6 fraud-triage agent

## How to work
- **Do one phase at a time, then stop and wait.** Do not jump ahead to later phases.
- End each phase with code that runs and a single clean git commit with a clear message.
- When something fails, read the error, explain what it means, then fix it.

## Non-negotiable conventions (this is a banking system)
- **Money uses `BigDecimal`, never `double`.** Store as `NUMERIC(19,4)` or integer cents.
- **Transfers are atomic:** debit + credit + ledger writes happen inside one `@Transactional` method; on any failure, roll back fully.
- **The ledger is append-only.** `ledger_entries` and `audit_log` rows are inserted, never updated or deleted.
- **Double-entry:** every transfer writes two ledger entries (one debit, one credit) that net to zero.
- **Idempotency:** `POST /transfers` requires an `Idempotency-Key` header; a repeated key returns the stored result and does not re-execute.
- **Concurrency is handled** (row locking) so simultaneous transfers on the same account can't corrupt a balance.
- **Secrets** (DB passwords, API keys) live in environment variables or a `.env` file that is in `.gitignore`. Never commit secrets.
- The fraud-triage agent's tools are **read-only**; it recommends, a human decides, and every decision is logged.

## Parallel work
- Default to a single focused session. Use **subagents** only for independent, well-bounded side-tasks (e.g. building a feature while a subagent writes its tests and another updates docs).
- Give each parallel agent its own files/directories — never let two agents edit the same file.
- Do not parallelize the dependency chain (accounts → transfers → idempotency).
- Don't launch more parallel work than the developer can review.

## Commits
- One meaningful commit per phase. Clear, present-tense messages (e.g. "Add atomic transfer endpoint with row locking").
- Keep history clean; no "wip" noise in the final log.