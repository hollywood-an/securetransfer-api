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
 * One money-movement record. Maps to the "transfers" table (V1__init.sql).
 *
 * The two accounts are referenced by their id (a plain Long), like the ledger —
 * the transfer is a flat record of "this much moved from X to Y at this time".
 */
@Entity
@Table(name = "transfers")
public class Transfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // The idempotency key that produced this transfer. Wired up in Phase 3;
    // left null for now (the column is nullable).
    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Column(name = "from_account", nullable = false)
    private Long fromAccount;

    @Column(name = "to_account", nullable = false)
    private Long toAccount;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING) // store the status as text ("COMPLETED", …)
    @Column(nullable = false, length = 20)
    private TransferStatus status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected Transfer() {
        // JPA requires a no-arg constructor.
    }

    public Transfer(String idempotencyKey, Long fromAccount, Long toAccount,
                    BigDecimal amount, TransferStatus status) {
        this.idempotencyKey = idempotencyKey;
        this.fromAccount = fromAccount;
        this.toAccount = toAccount;
        this.amount = amount;
        this.status = status;
    }

    public Long getId() {
        return id;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Long getFromAccount() {
        return fromAccount;
    }

    public Long getToAccount() {
        return toAccount;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public TransferStatus getStatus() {
        return status;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
