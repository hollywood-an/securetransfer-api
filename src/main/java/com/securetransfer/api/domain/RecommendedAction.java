package com.securetransfer.api.domain;

/**
 * The action recommended (by the agent) or decided (by a human) for a flagged
 * transfer. Matches the CHECK constraints on fraud_reviews.recommended_action
 * and fraud_reviews.human_decision.
 */
public enum RecommendedAction {
    APPROVE,  // looks fine — let it stand
    HOLD,     // suspicious — a human should place/keep a hold
    ESCALATE  // needs deeper investigation
}
