package com.securetransfer.api.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests the fixed score→action policy bands — the single source of truth that both
 * the AI path and the rules fallback use, so the same score always maps to the same
 * action.
 */
class RecommendedActionTest {

    @Test
    @DisplayName("fromScore maps the fixed bands: <40 APPROVE, 40-69 HOLD, >=70 ESCALATE")
    void mapsScoreToActionByBand() {
        assertThat(RecommendedAction.fromScore(0)).isEqualTo(RecommendedAction.APPROVE);
        assertThat(RecommendedAction.fromScore(39)).isEqualTo(RecommendedAction.APPROVE);
        assertThat(RecommendedAction.fromScore(40)).isEqualTo(RecommendedAction.HOLD);   // HOLD_SCORE boundary
        assertThat(RecommendedAction.fromScore(69)).isEqualTo(RecommendedAction.HOLD);
        assertThat(RecommendedAction.fromScore(70)).isEqualTo(RecommendedAction.ESCALATE); // ESCALATE_SCORE boundary
        assertThat(RecommendedAction.fromScore(100)).isEqualTo(RecommendedAction.ESCALATE);
    }

    @Test
    @DisplayName("The same score always maps to the same action; 72 is unambiguously ESCALATE")
    void isDeterministic() {
        for (int s = 0; s <= 100; s++) {
            assertThat(RecommendedAction.fromScore(s)).isEqualTo(RecommendedAction.fromScore(s));
        }
        // 72 is the score that, in the live evaluation, mapped to BOTH HOLD and ESCALATE
        // across different reviews. It is now unambiguously ESCALATE. (Finding #3.)
        assertThat(RecommendedAction.fromScore(72)).isEqualTo(RecommendedAction.ESCALATE);
    }
}
