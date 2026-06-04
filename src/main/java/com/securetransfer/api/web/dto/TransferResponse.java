package com.securetransfer.api.web.dto;

import com.securetransfer.api.domain.Account;
import com.securetransfer.api.domain.Transfer;
import com.securetransfer.api.domain.TransferStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * What we send back after a transfer: the transfer record plus the SENDER's
 * resulting balance.
 *
 * We deliberately do NOT expose the recipient's balance. The caller owns (or is
 * authorized for) the source account, so showing its balance is fine; but
 * returning the destination account's balance would leak a third party's balance
 * to the sender — a customer could otherwise read any account's balance by
 * sending it a tiny amount.
 */
public record TransferResponse(
        Long id,
        Long fromAccount,
        Long toAccount,
        BigDecimal amount,
        TransferStatus status,
        OffsetDateTime createdAt,
        BigDecimal fromBalance
) {
    public static TransferResponse from(Transfer transfer, Account from) {
        return new TransferResponse(
                transfer.getId(),
                transfer.getFromAccount(),
                transfer.getToAccount(),
                transfer.getAmount(),
                transfer.getStatus(),
                transfer.getCreatedAt(),
                from.getBalance()
        );
    }
}
