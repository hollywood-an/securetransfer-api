package com.securetransfer.api.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * One append-only entry in the audit log: WHO did a sensitive action, to WHAT,
 * and WHEN. Maps to "audit_log" (V1__init.sql).
 *
 * Separate from the ledger on purpose — the ledger records money movements; this
 * records accountability for actions that often move no money at all (freezing
 * an account, staff viewing a customer's data, a manual fraud override).
 *
 * Rows are only ever INSERTED — never updated or deleted. AuditLogRepository
 * deliberately exposes no update/delete methods.
 */
@Entity
@Table(name = "audit_log")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Who did it — always taken from the authenticated user, never from the client.
    @Column(nullable = false)
    private String actor;

    @Column(nullable = false, length = 100)
    private String action; // e.g. "ACCOUNT_FROZEN", "ACCOUNT_VIEWED"

    @Column(length = 255)
    private String target; // what was acted on, e.g. "account:44"

    // Free-form extra context, stored as JSONB.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata")
    private Map<String, Object> metadata;

    // The DB column is named "timestamp"; we call the Java field occurredAt to
    // avoid any clash with the SQL keyword in queries.
    @CreationTimestamp
    @Column(name = "timestamp", nullable = false, updatable = false)
    private OffsetDateTime occurredAt;

    protected AuditLog() {
        // JPA requires a no-arg constructor.
    }

    public AuditLog(String actor, String action, String target, Map<String, Object> metadata) {
        this.actor = actor;
        this.action = action;
        this.target = target;
        this.metadata = metadata;
    }

    public Long getId() {
        return id;
    }

    public String getActor() {
        return actor;
    }

    public String getAction() {
        return action;
    }

    public String getTarget() {
        return target;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public OffsetDateTime getOccurredAt() {
        return occurredAt;
    }
}
