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

import java.time.OffsetDateTime;

/**
 * A bank customer — the person who owns one or more accounts.
 *
 * An "entity" is a Java object mapped to a database table: one instance = one
 * row. This maps to the "customers" table created in V2__customers_*.sql.
 */
@Entity
@Table(name = "customers")
public class Customer {

    @Id // primary key
    @GeneratedValue(strategy = GenerationType.IDENTITY) // the DB assigns the id (IDENTITY column)
    private Long id;

    @Column(nullable = false)
    private String name;

    // Note: email is unique PER TENANT (see V6), not globally, so the STAFF and
    // DEMO banks can each independently hold a customer with the same email.
    @Column(nullable = false)
    private String email;

    // Which bank this customer belongs to (STAFF vs DEMO).
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Tenant tenant;

    @CreationTimestamp // Hibernate sets this once, when the row is first inserted
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected Customer() {
        // JPA requires a no-arg constructor; not meant for application code.
    }

    public Customer(String name, String email, Tenant tenant) {
        this.name = name;
        this.email = email;
        this.tenant = tenant;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    public Tenant getTenant() {
        return tenant;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
