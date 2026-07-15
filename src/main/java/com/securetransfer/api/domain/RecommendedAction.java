package com.securetransfer.api.domain;

/**
 * The action recommended (by the agent) or decided (by a human) for a flagged
 * transfer. Matches the CHECK constraints on fraud_reviews.recommended_action
 * and fraud_reviews.human_decision.
 */
public enum RecommendedAction {
    APPROVE,  // looks fine — let it stand
    HOLD,     // suspicious — a human should place/keep a hold
    ESCALATE; // needs deeper investigation

    /**
     * Fixed policy bands: a risk score at/above this escalates; at/above
     * {@link #HOLD_SCORE} holds; anything lower is approved. These are the single
     * source of truth for turning a 0–100 risk score into an action, used by BOTH
     * the AI path and the rules-based fallback so the same score always maps to the
     * same action (and the two paths never disagree).
     */
    public static final int ESCALATE_SCORE = 70;
    public static final int HOLD_SCORE = 40;

    /** Map a 0–100 risk score to an action using the fixed policy bands above. */
    public static RecommendedAction fromScore(int score) {
        if (score >= ESCALATE_SCORE) {
            return ESCALATE;
        }
        if (score >= HOLD_SCORE) {
            return HOLD;
        }
        return APPROVE;
    }
}
