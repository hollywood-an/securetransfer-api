package com.securetransfer.api.domain;

/**
 * Lifecycle state of a transfer. The values match the CHECK constraint on
 * transfers.status in V1__init.sql.
 */
public enum TransferStatus {
    PENDING,    // created but not yet completed (used by later phases)
    COMPLETED,  // funds moved successfully
    FAILED,     // attempted but rolled back
    FLAGGED     // completed, but flagged for fraud review (Phase 6)
}
