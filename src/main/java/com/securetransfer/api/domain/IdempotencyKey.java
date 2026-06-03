package com.securetransfer.api.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A recorded idempotency key. Maps to the "idempotency_keys" table (V1__init.sql).
 *
 * We only READ this entity (to inspect a repeat request's status + hash). The
 * row is CREATED, COMPLETED, and the JSON snapshot is stored via small native
 * SQL statements in IdempotencyKeyRepository — so this entity deliberately maps
 * only the columns we read (the JSONB `response_snapshot` and `created_at` are
 * handled in SQL, not here).
 */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKey {

    @Id
    @Column(name = "key")
    private String key;

    @Column(name = "request_hash", nullable = false)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IdempotencyStatus status;

    protected IdempotencyKey() {
        // JPA requires a no-arg constructor.
    }

    public String getKey() {
        return key;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public IdempotencyStatus getStatus() {
        return status;
    }
}
