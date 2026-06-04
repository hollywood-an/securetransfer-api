-- =====================================================================
-- V4__account_status.sql  —  Account freeze support (Phase 5)
--
-- Adds a lifecycle status to accounts so an admin can FREEZE one. A frozen
-- account can't send or receive transfers (enforced in TransferService).
-- Existing accounts default to ACTIVE.
-- =====================================================================

ALTER TABLE accounts ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';

ALTER TABLE accounts
    ADD CONSTRAINT accounts_status_valid CHECK (status IN ('ACTIVE', 'FROZEN'));
