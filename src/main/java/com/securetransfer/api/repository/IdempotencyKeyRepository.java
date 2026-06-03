package com.securetransfer.api.repository;

import com.securetransfer.api.domain.IdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Data-layer access for idempotency keys. The claim/complete/snapshot operations
 * use small native SQL statements so we can lean on Postgres features (atomic
 * upsert and JSONB).
 */
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, String> {

    /**
     * Try to claim a key by inserting a fresh IN_PROGRESS row.
     *
     * "ON CONFLICT (key) DO NOTHING" makes this atomic and race-proof: if two
     * requests with the same key arrive together, exactly ONE insert succeeds
     * and the other does nothing — the DATABASE is the referee, not app code.
     * Returns 1 if we inserted the row (claimed it), 0 if the key already existed.
     */
    @Modifying
    @Query(value = "INSERT INTO idempotency_keys (key, request_hash, status, created_at) "
            + "VALUES (:key, :hash, 'IN_PROGRESS', now()) ON CONFLICT (key) DO NOTHING",
            nativeQuery = true)
    int tryInsert(@Param("key") String key, @Param("hash") String hash);

    /** Flip a claimed key to COMPLETED and store the response as a JSON snapshot. */
    @Modifying
    @Query(value = "UPDATE idempotency_keys SET status = 'COMPLETED', "
            + "response_snapshot = CAST(:snapshot AS jsonb) WHERE key = :key",
            nativeQuery = true)
    int markCompleted(@Param("key") String key, @Param("snapshot") String snapshot);

    /** The stored response snapshot as JSON text (null if not set). */
    @Query(value = "SELECT response_snapshot::text FROM idempotency_keys WHERE key = :key",
            nativeQuery = true)
    String findSnapshotJson(@Param("key") String key);
}
