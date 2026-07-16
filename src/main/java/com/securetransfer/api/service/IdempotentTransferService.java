package com.securetransfer.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securetransfer.api.domain.IdempotencyStatus;
import com.securetransfer.api.domain.Tenant;
import com.securetransfer.api.error.ConflictException;
import com.securetransfer.api.service.IdempotencyService.Claim;
import com.securetransfer.api.web.dto.CreateTransferRequest;
import com.securetransfer.api.web.dto.TransferResponse;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Wraps a transfer in the idempotency protocol so that a repeated request with
 * the same Idempotency-Key replays the first result instead of moving money
 * again.
 *
 * This method is intentionally NOT @Transactional: it coordinates SEVERAL
 * transactions — a short one to claim the key, the transfer itself, and (only on
 * failure) one to release the key. Wrapping them in a single transaction would
 * defeat the point (the IN_PROGRESS claim must commit before the transfer runs).
 */
@Service
public class IdempotentTransferService {

    private final IdempotencyService idempotency;
    private final TransferService transferService;
    private final ObjectMapper objectMapper;

    public IdempotentTransferService(IdempotencyService idempotency,
                                     TransferService transferService,
                                     ObjectMapper objectMapper) {
        this.idempotency = idempotency;
        this.transferService = transferService;
        this.objectMapper = objectMapper;
    }

    public TransferResponse transfer(String idempotencyKey, CreateTransferRequest request, Tenant tenant) {
        String requestHash = hash(request);

        Claim claim = idempotency.claim(idempotencyKey, requestHash);

        if (claim.claimed()) {
            // First time we've seen this key: run the transfer (which also marks
            // the key COMPLETED in its own transaction). If it fails, release the
            // key so the client can retry with the same key.
            try {
                return transferService.execute(idempotencyKey, request, requestHash, tenant);
            } catch (RuntimeException e) {
                idempotency.release(idempotencyKey);
                throw e;
            }
        }

        // The key already exists → this is a repeat request.
        if (claim.existingStatus() == IdempotencyStatus.COMPLETED) {
            // Guard against the same key being reused for a DIFFERENT request.
            if (!requestHash.equals(claim.existingRequestHash())) {
                throw new ConflictException(
                        "Idempotency-Key was already used with a different request");
            }
            // Replay the stored response — do NOT run the transfer again.
            return deserialize(idempotency.snapshot(idempotencyKey));
        }

        // Status is IN_PROGRESS: the first request with this key is still running.
        throw new ConflictException(
                "A request with this Idempotency-Key is already in progress");
    }

    /**
     * SHA-256 of a canonical form of the request → 64 hex chars (request_hash).
     * The amount is normalised (30.00 and 30 hash the same) so trivially
     * different encodings of the same request aren't treated as a mismatch.
     */
    private static String hash(CreateTransferRequest r) {
        String canonical = r.fromAccount() + ":" + r.toAccount() + ":"
                + r.amount().stripTrailingZeros().toPlainString();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private TransferResponse deserialize(String json) {
        try {
            return objectMapper.readValue(json, TransferResponse.class);
        } catch (Exception e) {
            throw new IllegalStateException("Could not read stored transfer response", e);
        }
    }
}
