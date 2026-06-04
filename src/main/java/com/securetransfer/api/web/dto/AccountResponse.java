package com.securetransfer.api.web.dto;

import com.securetransfer.api.domain.Account;
import com.securetransfer.api.domain.AccountStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * What we send back for an account: its balance, status, and ledger history.
 */
public record AccountResponse(
        Long id,
        Long customerId,
        String currency,
        BigDecimal balance,
        AccountStatus status,
        OffsetDateTime createdAt,
        List<LedgerEntryResponse> ledger
) {
    public static AccountResponse from(Account a, List<LedgerEntryResponse> ledger) {
        return new AccountResponse(
                a.getId(),
                a.getCustomer().getId(), // only the FK id is needed; the LAZY customer isn't fully loaded
                a.getCurrency(),
                a.getBalance(),
                a.getStatus(),
                a.getCreatedAt(),
                ledger
        );
    }
}
