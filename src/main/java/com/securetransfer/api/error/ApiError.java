package com.securetransfer.api.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * A consistent JSON shape for every error response, e.g.
 * <pre>
 * { "timestamp": "2026-06-02T...", "status": 404, "error": "Not Found",
 *   "message": "Account 42 not found" }
 * </pre>
 *
 * {@code fieldErrors} is only present for validation failures (it maps each bad
 * field to its message); JsonInclude.NON_NULL hides it otherwise.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        OffsetDateTime timestamp,
        int status,
        String error,
        String message,
        Map<String, String> fieldErrors
) {
}
