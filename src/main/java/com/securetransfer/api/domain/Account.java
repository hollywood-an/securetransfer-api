package com.securetransfer.api.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * A bank account with a balance, owned by a {@link Customer}.
 * Maps to the "accounts" table (created in V1__init.sql).
 *
 * Money is stored as {@link BigDecimal} (NUMERIC(19,4) in the DB) — never
 * double — so there are no floating-point rounding errors.
 */
@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Many accounts can belong to one customer. LAZY = don't fetch the customer
    // from the DB unless we actually use it. The "customer_id" column holds the
    // foreign key.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Column(nullable = false, length = 3)
    private String currency; // ISO 4217 code, e.g. "USD"

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal balance;

    // Lifecycle status. An admin can FREEZE an account (Phase 5), which blocks
    // transfers in or out of it. New accounts start ACTIVE.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountStatus status;

    // Optimistic-locking counter. Hibernate increments it on every update and
    // refuses to save if another transaction changed the row first.
    @Version
    private Long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected Account() {
        // JPA requires a no-arg constructor.
    }

    public Account(Customer customer, String currency, BigDecimal balance) {
        this.customer = customer;
        this.currency = currency;
        this.balance = balance;
        this.status = AccountStatus.ACTIVE;
    }

    /**
     * Subtract money from this account (the sender side of a transfer).
     * The caller must check there are sufficient funds first; the DB's
     * balance >= 0 CHECK is the last-line backstop.
     */
    public void debit(BigDecimal amount) {
        this.balance = this.balance.subtract(amount);
    }

    /** Add money to this account (the receiver side of a transfer). */
    public void credit(BigDecimal amount) {
        this.balance = this.balance.add(amount);
    }

    /** Freeze this account: an admin action that blocks transfers in or out. */
    public void freeze() {
        this.status = AccountStatus.FROZEN;
    }

    /** Unfreeze this account, returning it to normal ACTIVE service. */
    public void unfreeze() {
        this.status = AccountStatus.ACTIVE;
    }

    public boolean isFrozen() {
        return this.status == AccountStatus.FROZEN;
    }

    public Long getId() {
        return id;
    }

    public Customer getCustomer() {
        return customer;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public Long getVersion() {
        return version;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
