package com.securetransfer.api.fraud;

import com.securetransfer.api.config.FraudProperties;
import com.securetransfer.api.repository.TransferRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Cheap, deterministic rules that FLAG (never block) a transfer for review.
 *
 * A flagged transfer still completes — flagging just queues it for the AI/human
 * review. Evaluated against PRIOR transfers (call this before saving the current
 * one), so the velocity and new-payee checks exclude the transfer being made.
 */
@Component
public class FraudRuleEvaluator {

    // Reason codes (also used by the fallback verdict scoring).
    public static final String LARGE_AMOUNT = "LARGE_AMOUNT";
    public static final String HIGH_VELOCITY = "HIGH_VELOCITY";
    public static final String NEW_PAYEE = "NEW_PAYEE";
    public static final String STRUCTURING = "STRUCTURING";

    private final TransferRepository transfers;
    private final FraudProperties props;

    public FraudRuleEvaluator(TransferRepository transfers, FraudProperties props) {
        this.transfers = transfers;
        this.props = props;
    }

    /** Returns the reason codes that fired (empty = not flagged). */
    public List<String> evaluate(Long fromAccount, Long toAccount, BigDecimal amount) {
        List<String> reasons = new ArrayList<>();

        // Rule 1: large amount.
        if (amount.compareTo(props.getLargeAmountThreshold()) >= 0) {
            reasons.add(LARGE_AMOUNT);
        }

        // Rule 2: high velocity — many sends from this account in a short window.
        OffsetDateTime windowStart =
                OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(props.getVelocityWindowMinutes());
        long recentSends = transfers.countByFromAccountAndCreatedAtAfter(fromAccount, windowStart);
        if (recentSends >= props.getVelocityMaxTransfers()) {
            reasons.add(HIGH_VELOCITY);
        }

        // Rule 3: brand-new payee — no prior transfer on this sender→receiver pair.
        if (!transfers.existsByFromAccountAndToAccount(fromAccount, toAccount)) {
            reasons.add(NEW_PAYEE);
        }

        // Rule 4: structuring ("smurfing") — several sends kept JUST under the
        // large-amount line in the window. A single such send isn't a large-amount
        // transfer (that's Rule 1); the red flag is the REPEATED near-threshold
        // pattern, which is how money is split to stay under a reporting threshold.
        BigDecimal threshold = props.getLargeAmountThreshold();
        if (amount.compareTo(threshold) < 0) { // a >= threshold send is LARGE_AMOUNT, not structuring
            BigDecimal nearThresholdFloor =
                    threshold.multiply(BigDecimal.ONE.subtract(props.getStructuringProximityRatio()));
            boolean currentIsNearThreshold = amount.compareTo(nearThresholdFloor) >= 0;
            if (currentIsNearThreshold) {
                // PRIOR near-threshold sends in the same window (excludes the current one).
                long priorNearThreshold = transfers.countOutgoingInAmountRangeSince(
                        fromAccount, windowStart, nearThresholdFloor, threshold);
                if (priorNearThreshold + 1 >= props.getStructuringMinTransfers()) {
                    reasons.add(STRUCTURING);
                }
            }
        }

        return reasons;
    }
}
