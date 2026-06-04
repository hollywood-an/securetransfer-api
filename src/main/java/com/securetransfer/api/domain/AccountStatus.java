package com.securetransfer.api.domain;

/**
 * Lifecycle status of an account. Matches the CHECK constraint on
 * accounts.status (V4__account_status.sql).
 */
public enum AccountStatus {
    ACTIVE,  // normal — can send and receive transfers
    FROZEN   // frozen by an admin — transfers in or out are rejected
}
