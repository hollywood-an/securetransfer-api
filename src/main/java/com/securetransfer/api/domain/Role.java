package com.securetransfer.api.domain;

/**
 * A user's role, which decides what they're allowed to do (authorization).
 * Matches the CHECK constraint on users.role in V3__users.sql.
 */
public enum Role {
    CUSTOMER, // a bank customer; may only touch their own accounts
    TELLER,   // staff; may act on any customer's accounts
    ADMIN     // staff with full access, including admin-only endpoints
}
