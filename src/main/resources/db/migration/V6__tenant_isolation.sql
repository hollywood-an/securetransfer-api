-- =====================================================================
-- V6__tenant_isolation.sql
--
-- Partition the app into two independent "banks" (tenants) that never see
-- each other's data, while staying ONE deployment against ONE database:
--   * STAFF — the real showcase bank shared by the admin + teller logins.
--   * DEMO  — the public `demo` login's own self-contained sandbox.
--
-- Every tenant-scoped table gets a `tenant` column, backfilled to STAFF for
-- all existing rows (so the owner's showcase data stays put). We then move the
-- already-seeded `demo` login into the DEMO tenant, where it owns nothing yet —
-- a blank slate. The application derives the tenant from the login (JWT), stamps
-- it on every row a user creates, and checks it on every read/action.
--
-- Deliberately NOT scoped: ledger_entries and audit_log (append-only; ledger
-- reads are gated via account access, audit is ADMIN-only) and idempotency_keys
-- (random client UUIDs). This keeps the immutable money tables untouched.
-- =====================================================================

-- 1) Add the tenant column to each scoped table. DEFAULT 'STAFF' backfills every
--    existing row in one step; the app always supplies the value explicitly.
ALTER TABLE users         ADD COLUMN tenant VARCHAR(16) NOT NULL DEFAULT 'STAFF';
ALTER TABLE customers     ADD COLUMN tenant VARCHAR(16) NOT NULL DEFAULT 'STAFF';
ALTER TABLE accounts      ADD COLUMN tenant VARCHAR(16) NOT NULL DEFAULT 'STAFF';
ALTER TABLE transfers     ADD COLUMN tenant VARCHAR(16) NOT NULL DEFAULT 'STAFF';
ALTER TABLE fraud_reviews ADD COLUMN tenant VARCHAR(16) NOT NULL DEFAULT 'STAFF';

-- 2) Only the two known tenants are valid (mirrors the enum-as-VARCHAR+CHECK
--    convention used throughout this schema).
ALTER TABLE users         ADD CONSTRAINT users_tenant_valid         CHECK (tenant IN ('STAFF', 'DEMO'));
ALTER TABLE customers     ADD CONSTRAINT customers_tenant_valid     CHECK (tenant IN ('STAFF', 'DEMO'));
ALTER TABLE accounts      ADD CONSTRAINT accounts_tenant_valid      CHECK (tenant IN ('STAFF', 'DEMO'));
ALTER TABLE transfers     ADD CONSTRAINT transfers_tenant_valid     CHECK (tenant IN ('STAFF', 'DEMO'));
ALTER TABLE fraud_reviews ADD CONSTRAINT fraud_reviews_tenant_valid CHECK (tenant IN ('STAFF', 'DEMO'));

-- 3) Move the public demo login into its own bank. After this it owns zero
--    customers / accounts / transfers / reviews — a blank slate. (If the demo
--    login hasn't been seeded yet, this simply matches no rows.)
UPDATE users SET tenant = 'DEMO' WHERE username = 'demo';

-- 4) Email is now unique PER TENANT, not globally, so the STAFF and DEMO banks
--    can each independently hold a customer with the same email. Replace V2's
--    global-unique constraint.
ALTER TABLE customers DROP CONSTRAINT customers_email_unique;
ALTER TABLE customers ADD CONSTRAINT customers_tenant_email_unique UNIQUE (tenant, email);

-- 5) The fraud-review queue is always listed filtered by tenant — index it.
CREATE INDEX idx_fraud_reviews_tenant ON fraud_reviews (tenant);
