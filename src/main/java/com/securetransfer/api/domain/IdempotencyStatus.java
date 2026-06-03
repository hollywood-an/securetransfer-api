package com.securetransfer.api.domain;

/**
 * State of an idempotency key. Matches the CHECK constraint on
 * idempotency_keys.status in V1__init.sql.
 */
public enum IdempotencyStatus {
    IN_PROGRESS, // the first request is still being processed
    COMPLETED    // processing finished; the stored response can be replayed
}
