package com.securetransfer.api.fraud;

/**
 * Published (inside the transfer transaction) when a transfer is flagged for
 * fraud review. A listener handles it AFTER the transaction commits, on a
 * background thread, so the transfer response isn't held up by the AI review.
 */
public record TransferFlaggedEvent(Long reviewId) {
}
