package com.securetransfer.api.fraud;

import static org.assertj.core.api.Assertions.assertThat;

import com.securetransfer.api.domain.RecommendedAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Tests for the deterministic rules-based fallback scoring — the verdict used when
 * the AI agent is unavailable or errors. Confirms STRUCTURING carries real weight
 * so a laundering pattern is escalated even with no model call.
 */
class FraudVerdictTest {

    @Test
    @DisplayName("STRUCTURING alone scores 45 -> HOLD")
    void structuringAloneHolds() {
        FraudVerdict v = FraudVerdict.fallback(List.of(FraudRuleEvaluator.STRUCTURING), null);

        assertThat(v.fromAgent()).isFalse();
        assertThat(v.model()).isEqualTo("rules-fallback");
        assertThat(v.riskScore()).isEqualTo(45);
        assertThat(v.recommendedAction()).isEqualTo(RecommendedAction.HOLD);
    }

    @Test
    @DisplayName("STRUCTURING + HIGH_VELOCITY scores 80 -> ESCALATE")
    void structuringWithVelocityEscalates() {
        FraudVerdict v = FraudVerdict.fallback(
                List.of(FraudRuleEvaluator.STRUCTURING, FraudRuleEvaluator.HIGH_VELOCITY), null);

        assertThat(v.riskScore()).isEqualTo(80);
        assertThat(v.recommendedAction()).isEqualTo(RecommendedAction.ESCALATE);
    }

    @Test
    @DisplayName("No flags scores 0 -> APPROVE")
    void noFlagsApproves() {
        FraudVerdict v = FraudVerdict.fallback(List.of(), null);

        assertThat(v.riskScore()).isEqualTo(0);
        assertThat(v.recommendedAction()).isEqualTo(RecommendedAction.APPROVE);
    }
}
