package com.securetransfer.api.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * One line in the append-only, double-entry ledger. Maps to "ledger_entries"
 * (V1__init.sql).
 *
 * Phase 1 only READS these — they back the history shown by GET /accounts/{id}.
 * Phase 2 starts WRITING them: each transfer inserts a matching DEBIT + CREDIT.
 * (So in Phase 1 this list is always empty.)
 *
 * We reference the related transfer/account by their id (a plain Long) rather
 * than a JPA relationship, because the ledger is a flat, immutable log.
 */
@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transfer_id", nullable = false)
    private Long transferId;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Enumerated(EnumType.STRING) // store the enum's name ("DEBIT"/"CREDIT") as text
    @Column(nullable = false, length = 6)
    private LedgerDirection direction;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount; // always positive; `direction` carries the sign

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected LedgerEntry() {
        // JPA requires a no-arg constructor.
    }

    /** Create a new ledger line (written by TransferService in Phase 2). */
    public LedgerEntry(Long transferId, Long accountId, LedgerDirection direction, BigDecimal amount) {
        this.transferId = transferId;
        this.accountId = accountId;
        this.direction = direction;
        this.amount = amount;
    }

    public Long getId() {
        return id;
    }

    public Long getTransferId() {
        return transferId;
    }

    public Long getAccountId() {
        return accountId;
    }

    public LedgerDirection getDirection() {
        return direction;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
