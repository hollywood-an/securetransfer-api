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
}
