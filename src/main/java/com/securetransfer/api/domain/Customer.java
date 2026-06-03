package com.securetransfer.api.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

    @Column(nullable = false, unique = true)
    private String email;

    @CreationTimestamp // Hibernate sets this once, when the row is first inserted
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected Customer() {
        // JPA requires a no-arg constructor; not meant for application code.
    }

    public Customer(String name, String email) {
        this.name = name;
        this.email = email;
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

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
