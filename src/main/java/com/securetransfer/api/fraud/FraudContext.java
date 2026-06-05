package com.securetransfer.api.fraud;

import java.math.BigDecimal;
import java.util.List;

/**
 * The facts about a flagged transfer handed to the triage agent. Plain data
 * (no JPA entities), so it's safe to pass across transaction/thread boundaries.
 */
public record FraudContext(
        Long transferId,
        Long fromAccount,
        Long toAccount,
        BigDecimal amount,
        List<String> flagReasons
) {
}
