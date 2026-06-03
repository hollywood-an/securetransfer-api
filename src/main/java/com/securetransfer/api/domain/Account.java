package com.securetransfer.api.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

    // Optimistic-locking counter. Hibernate increments it on every update and
    // refuses to save if another transaction changed the row first. We don't
    // update balances until Phase 2, but the column is here and ready.
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

    public Long getVersion() {
        return version;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
