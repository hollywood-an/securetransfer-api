package com.securetransfer.api.web.dto;

import com.securetransfer.api.domain.Account;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * What we send back for an account: its balance plus its ledger history.
 * (In Phase 1 the ledger is always empty; Phase 2's transfers fill it in.)
 */
public record AccountResponse(
        Long id,
        Long customerId,
        String currency,
        BigDecimal balance,
        OffsetDateTime createdAt,
        List<LedgerEntryResponse> ledger
) {
    public static AccountResponse from(Account a, List<LedgerEntryResponse> ledger) {
        return new AccountResponse(
                a.getId(),
                a.getCustomer().getId(), // only the FK id is needed; the LAZY customer isn't fully loaded
                a.getCurrency(),
                a.getBalance(),
                a.getCreatedAt(),
                ledger
        );
    }
}
