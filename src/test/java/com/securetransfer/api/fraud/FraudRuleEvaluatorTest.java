package com.securetransfer.api.fraud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.securetransfer.api.config.FraudProperties;
import com.securetransfer.api.repository.TransferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Unit tests for the deterministic fraud rules, focused on the STRUCTURING
 * ("smurfing") rule: several sends kept just under the large-amount threshold.
 * TransferRepository is mocked, so no database is involved. FraudProperties uses
 * its defaults ($10,000 threshold, 0.20 proximity ratio, min 3 near-threshold sends).
 */
class FraudRuleEvaluatorTest {

    private final TransferRepository transfers = mock(TransferRepository.class);
    private final FraudProperties props = new FraudProperties();
    private final FraudRuleEvaluator evaluator = new FraudRuleEvaluator(transfers, props);

    @BeforeEach
    void isolateStructuring() {
        // Quiet the other rules so assertions target STRUCTURING: no velocity, and a
        // KNOWN payee (so NEW_PAYEE doesn't fire).
        when(transfers.countByFromAccountAndCreatedAtAfter(anyLong(), any(OffsetDateTime.class)))
                .thenReturn(0L);
        when(transfers.existsByFromAccountAndToAccount(anyLong(), anyLong())).thenReturn(true);
    }

    @Test
    @DisplayName("STRUCTURING fires on the 3rd near-threshold send (2 prior + the current one)")
    void firesOnThirdNearThresholdSend() {
        when(transfers.countOutgoingInAmountRangeSince(eq(1L), any(), any(), any())).thenReturn(2L);

        List<String> reasons = evaluator.evaluate(1L, 2L, new BigDecimal("9400.00"));

        assertThat(reasons).contains(FraudRuleEvaluator.STRUCTURING);
        // $9,400 is below the $10,000 line, so it is NOT a large-amount transfer.
        assertThat(reasons).doesNotContain(FraudRuleEvaluator.LARGE_AMOUNT);
    }

    @Test
    @DisplayName("STRUCTURING does not fire with only one prior near-threshold send")
    void needsEnoughPriorNearThresholdSends() {
        when(transfers.countOutgoingInAmountRangeSince(eq(1L), any(), any(), any())).thenReturn(1L);

        List<String> reasons = evaluator.evaluate(1L, 2L, new BigDecimal("9400.00"));

        assertThat(reasons).doesNotContain(FraudRuleEvaluator.STRUCTURING);
    }

    @Test
    @DisplayName("A small transfer is not structuring, even with many prior near-threshold sends")
    void smallAmountIsNotStructuring() {
        when(transfers.countOutgoingInAmountRangeSince(eq(1L), any(), any(), any())).thenReturn(9L);

        // $5,000 is below the $8,000 near-threshold floor.
        List<String> reasons = evaluator.evaluate(1L, 2L, new BigDecimal("5000.00"));

        assertThat(reasons).doesNotContain(FraudRuleEvaluator.STRUCTURING);
    }

    @Test
    @DisplayName("A transfer at/above the threshold is LARGE_AMOUNT, never STRUCTURING")
    void atThresholdIsLargeNotStructuring() {
        when(transfers.countOutgoingInAmountRangeSince(eq(1L), any(), any(), any())).thenReturn(9L);

        List<String> reasons = evaluator.evaluate(1L, 2L, new BigDecimal("10000.00"));

        assertThat(reasons).contains(FraudRuleEvaluator.LARGE_AMOUNT);
        assertThat(reasons).doesNotContain(FraudRuleEvaluator.STRUCTURING);
    }

    @Test
    @DisplayName("Structuring can co-fire with velocity on a repeated sub-threshold burst")
    void coFiresWithVelocity() {
        when(transfers.countByFromAccountAndCreatedAtAfter(anyLong(), any(OffsetDateTime.class)))
                .thenReturn(3L); // trips HIGH_VELOCITY
        when(transfers.countOutgoingInAmountRangeSince(eq(1L), any(), any(), any())).thenReturn(3L);

        List<String> reasons = evaluator.evaluate(1L, 2L, new BigDecimal("9300.00"));

        assertThat(reasons).contains(FraudRuleEvaluator.STRUCTURING, FraudRuleEvaluator.HIGH_VELOCITY);
    }
}
