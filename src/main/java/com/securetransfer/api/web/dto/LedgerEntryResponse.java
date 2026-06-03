package com.securetransfer.api.web.dto;

import com.securetransfer.api.domain.LedgerDirection;
import com.securetransfer.api.domain.LedgerEntry;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * One ledger line in an account's history (part of AccountResponse).
 */
public record LedgerEntryResponse(
        Long id,
        Long transferId,
        LedgerDirection direction,
        BigDecimal amount,
        OffsetDateTime createdAt
) {
    public static LedgerEntryResponse from(LedgerEntry e) {
        return new LedgerEntryResponse(
                e.getId(), e.getTransferId(), e.getDirection(), e.getAmount(), e.getCreatedAt());
    }
}
