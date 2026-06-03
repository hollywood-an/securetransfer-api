package com.securetransfer.api.web.dto;

import com.securetransfer.api.domain.Account;
import com.securetransfer.api.domain.Transfer;
import com.securetransfer.api.domain.TransferStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * What we send back after a transfer: the transfer record plus the resulting
 * balances of both accounts, so the caller can see the effect immediately.
 */
public record TransferResponse(
        Long id,
        Long fromAccount,
        Long toAccount,
        BigDecimal amount,
        TransferStatus status,
        OffsetDateTime createdAt,
        BigDecimal fromBalance,
        BigDecimal toBalance
) {
    public static TransferResponse from(Transfer t, Account from, Account to) {
        return new TransferResponse(
                t.getId(),
                t.getFromAccount(),
                t.getToAccount(),
                t.getAmount(),
                t.getStatus(),
                t.getCreatedAt(),
                from.getBalance(),
                to.getBalance()
        );
    }
}
