package com.securetransfer.api.fraud;

import com.securetransfer.api.domain.RecommendedAction;

import java.util.List;

/**
 * The triage outcome for a flagged transfer: a risk score, the reasoning, and a
 * recommended action. Produced either by the AI agent ({@code fromAgent=true})
 * or by the deterministic rules-based fallback.
 */
public record FraudVerdict(
        int riskScore,
        String verdict,
        String reasoning,
        RecommendedAction recommendedAction,
        String model,
        boolean fromAgent
) {
    /**
     * Deterministic fallback used when the AI agent is unavailable (no API key)
     * or errors. Scores from which rules fired so the review queue stays useful
     * and actionable even with no model call.
     */
    public static FraudVerdict fallback(List<String> flags, String note) {
        int score = 0;
        if (flags != null) {
            if (flags.contains(FraudRuleEvaluator.LARGE_AMOUNT)) {
                score += 45;
            }
            if (flags.contains(FraudRuleEvaluator.HIGH_VELOCITY)) {
                score += 35;
            }
            if (flags.contains(FraudRuleEvaluator.NEW_PAYEE)) {
                score += 20;
            }
        }
        score = Math.max(0, Math.min(100, score));
        RecommendedAction action = score >= 70 ? RecommendedAction.ESCALATE
                : score >= 40 ? RecommendedAction.HOLD
                : RecommendedAction.APPROVE;
        String reasoning = (note == null ? "" : note + " ")
                + "Rules-based fallback verdict from flags: " + (flags == null ? List.of() : flags) + ".";
        return new FraudVerdict(score, "RULES_FALLBACK", reasoning, action, "rules-fallback", false);
    }
}
