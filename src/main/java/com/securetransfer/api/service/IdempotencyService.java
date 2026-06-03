package com.securetransfer.api.service;

import com.securetransfer.api.domain.IdempotencyKey;
import com.securetransfer.api.domain.IdempotencyStatus;
import com.securetransfer.api.repository.IdempotencyKeyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages the lifecycle of an idempotency key: claim it, mark it complete, or
 * release it. The transaction annotations here are the subtle, important part —
 * see each method.
 */
@Service
public class IdempotencyService {

    private final IdempotencyKeyRepository keys;

    public IdempotencyService(IdempotencyKeyRepository keys) {
        this.keys = keys;
    }

    /** The result of trying to claim a key. */
    public record Claim(boolean claimed, IdempotencyStatus existingStatus, String existingRequestHash) {
        static Claim won() {
            return new Claim(true, null, null);
        }

        static Claim existing(IdempotencyStatus status, String requestHash) {
            return new Claim(false, status, requestHash);
        }
    }

    /**
     * Atomically claim the key by inserting an IN_PROGRESS row.
     *
     * REQUIRES_NEW runs this in its OWN, short transaction that commits before we
     * start the (longer) transfer — so a concurrent duplicate request can see the
     * IN_PROGRESS row and get a 409 instead of blocking on the whole transfer.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Claim claim(String key, String requestHash) {
        int inserted = keys.tryInsert(key, requestHash);
        if (inserted == 1) {
            return Claim.won();
        }
        // Conflict: the key already exists. Load it to see its state.
        IdempotencyKey existing = keys.findById(key).orElseThrow(
                () -> new IllegalStateException("Idempotency key vanished after conflict: " + key));
        return Claim.existing(existing.getStatus(), existing.getRequestHash());
    }

    /**
     * Mark a claimed key COMPLETED and store the response snapshot.
     *
     * Intentionally has NO @Transactional of its own: it runs inside the CALLER's
     * transfer transaction (TransferService.execute), so the key flips to
     * COMPLETED only if the transfer itself commits. That's what stops a key from
     * ever being marked "done" with no transfer behind it.
     */
    public void complete(String key, String responseSnapshotJson) {
        keys.markCompleted(key, responseSnapshotJson);
    }

    /** The stored response snapshot (JSON text) for a completed key. */
    public String snapshot(String key) {
        return keys.findSnapshotJson(key);
    }

    /**
     * Remove a claimed key after its transfer failed, so the client can retry
     * with the same key. REQUIRES_NEW because the transfer's transaction has
     * already rolled back by the time we get here.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(String key) {
        keys.deleteById(key);
    }
}
