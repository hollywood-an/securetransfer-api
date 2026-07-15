package com.securetransfer.api.fraud;

import java.math.BigDecimal;
import java.util.List;

/**
 * The facts about a flagged transfer handed to the triage agent. Plain data
 * (no JPA entities), so it's safe to pass across transaction/thread boundaries.
 *
 * <p>{@code fromAccountBalanceBeforeTransfer} is the sender's balance BEFORE this
 * transfer. It matters because money moves even when a transfer is flagged, so a
 * live balance lookup returns the POST-debit balance — which the agent used to
 * misread as an "overdraft". Supplying the pre-transfer balance lets the agent
 * reason correctly (e.g. "this moved 98% of the account"). May be {@code null}
 * when it isn't available.
 */
public record FraudContext(
        Long transferId,
        Long fromAccount,
        Long toAccount,
        BigDecimal amount,
        List<String> flagReasons,
        BigDecimal fromAccountBalanceBeforeTransfer
) {
    /**
     * Convenience constructor for callers without the sender's pre-transfer
     * balance (e.g. unit tests): it defaults to {@code null}, and the agent
     * prompt shows "unknown".
     */
    public FraudContext(Long transferId, Long fromAccount, Long toAccount,
                        BigDecimal amount, List<String> flagReasons) {
        this(transferId, fromAccount, toAccount, amount, flagReasons, null);
    }
}
