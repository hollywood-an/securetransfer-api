-- =====================================================================
-- V5__fraud_reviews_workflow.sql  —  Fraud-triage review workflow (Phase 6)
--
-- V1 created fraud_reviews with the agent's verdict columns. Phase 6 adds the
-- review lifecycle: which rules flagged the transfer, the agent's model, the
-- review status, and the HUMAN decision (human-in-the-loop). The agent only
-- recommends; a TELLER/ADMIN records the actual decision here.
-- =====================================================================

ALTER TABLE fraud_reviews
    ADD COLUMN status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    ADD COLUMN flag_reasons    JSONB,                    -- which rules fired (e.g. ["LARGE_AMOUNT"])
    ADD COLUMN agent_model     VARCHAR(100),             -- model id, or "rules-fallback"
    ADD COLUMN human_decision  VARCHAR(20),              -- the human's call: APPROVE/HOLD/ESCALATE
    ADD COLUMN decided_by_user VARCHAR(255),             -- the staff member who decided
    ADD COLUMN decided_at      TIMESTAMPTZ;

ALTER TABLE fraud_reviews
    -- PENDING (review queued) -> AGENT_COMPLETED (verdict in) -> DECIDED (human acted);
    -- AGENT_FAILED is reserved for an agent error with no usable verdict.
    ADD CONSTRAINT fraud_status_valid
        CHECK (status IN ('PENDING', 'AGENT_COMPLETED', 'AGENT_FAILED', 'DECIDED')),
    ADD CONSTRAINT fraud_human_decision_valid
        CHECK (human_decision IS NULL OR human_decision IN ('APPROVE', 'HOLD', 'ESCALATE')),
    -- One review per flagged transfer.
    ADD CONSTRAINT fraud_reviews_transfer_unique UNIQUE (transfer_id);

CREATE INDEX idx_fraud_status ON fraud_reviews (status);
