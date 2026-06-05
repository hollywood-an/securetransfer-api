package com.securetransfer.api.domain;

/**
 * Lifecycle of a fraud review. Matches the CHECK constraint on
 * fraud_reviews.status (V5__fraud_reviews_workflow.sql).
 */
public enum FraudReviewStatus {
    PENDING,          // flagged and queued; the agent hasn't produced a verdict yet
    AGENT_COMPLETED,  // the agent's verdict is recorded (awaiting a human decision)
    AGENT_FAILED,     // the agent errored with no usable verdict
    DECIDED           // a human (TELLER/ADMIN) has recorded the final decision
}
