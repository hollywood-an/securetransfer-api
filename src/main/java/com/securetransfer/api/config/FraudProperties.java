package com.securetransfer.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * Tunable thresholds for the rule-based fraud flagging (app.fraud.* in
 * application.yml). Defaults here keep the app working even if the properties
 * are unset (e.g. in tests).
 */
@ConfigurationProperties(prefix = "app.fraud")
public class FraudProperties {

    /** Flag a transfer whose amount is at or above this. */
    private BigDecimal largeAmountThreshold = new BigDecimal("10000.00");

    /** Window (minutes) used for the velocity rule and velocity stats tool. */
    private int velocityWindowMinutes = 60;

    /** Flag when the sender already made at least this many transfers in the window. */
    private int velocityMaxTransfers = 3;

    /**
     * Structuring ("smurfing"): a send counts as "near threshold" when its amount
     * is within this ratio BELOW the large-amount threshold. 0.20 with a $10,000
     * threshold means the band [$8,000, $10,000). Repeated sends in this band are
     * the classic tell of splitting a sum to stay under the reporting line.
     */
    private BigDecimal structuringProximityRatio = new BigDecimal("0.20");

    /**
     * Flag STRUCTURING once the sender has at least this many near-threshold sends
     * (counting the current one) within the velocity window.
     */
    private int structuringMinTransfers = 3;

    public BigDecimal getLargeAmountThreshold() {
        return largeAmountThreshold;
    }

    public void setLargeAmountThreshold(BigDecimal largeAmountThreshold) {
        this.largeAmountThreshold = largeAmountThreshold;
    }

    public int getVelocityWindowMinutes() {
        return velocityWindowMinutes;
    }

    public void setVelocityWindowMinutes(int velocityWindowMinutes) {
        this.velocityWindowMinutes = velocityWindowMinutes;
    }

    public int getVelocityMaxTransfers() {
        return velocityMaxTransfers;
    }

    public void setVelocityMaxTransfers(int velocityMaxTransfers) {
        this.velocityMaxTransfers = velocityMaxTransfers;
    }

    public BigDecimal getStructuringProximityRatio() {
        return structuringProximityRatio;
    }

    public void setStructuringProximityRatio(BigDecimal structuringProximityRatio) {
        this.structuringProximityRatio = structuringProximityRatio;
    }

    public int getStructuringMinTransfers() {
        return structuringMinTransfers;
    }

    public void setStructuringMinTransfers(int structuringMinTransfers) {
        this.structuringMinTransfers = structuringMinTransfers;
    }
}
