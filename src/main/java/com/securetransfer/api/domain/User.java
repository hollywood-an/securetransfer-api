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
 * A login account. Maps to the "users" table (V3__users.sql).
 *
 * The password is stored ONLY as a BCrypt hash — never in plain text.
 * customerId links a CUSTOMER user to their customer profile (null for staff),
 * which is how we enforce "a customer may only touch their own accounts".
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false)
    private String password; // BCrypt hash

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    // Plain FK column (not a JPA relationship) — null for staff users.
    @Column(name = "customer_id")
    private Long customerId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected User() {
        // JPA requires a no-arg constructor.
    }

    public User(String username, String passwordHash, Role role, Long customerId) {
        this.username = username;
        this.password = passwordHash;
        this.role = role;
        this.customerId = customerId;
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    /** The stored BCrypt hash (used only to verify a login password). */
    public String getPassword() {
        return password;
    }

    public Role getRole() {
        return role;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
