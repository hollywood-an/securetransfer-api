package com.securetransfer.api.domain;

/**
 * Which side of the ledger an entry is on:
 *   DEBIT  = money leaving an account
 *   CREDIT = money arriving in an account
 *
 * Stored in the database as the text "DEBIT"/"CREDIT" (see LedgerEntry).
 */
public enum LedgerDirection {
    DEBIT,
    CREDIT
}
