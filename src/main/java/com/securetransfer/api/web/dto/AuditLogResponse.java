package com.securetransfer.api.web.dto;

import com.securetransfer.api.domain.AuditLog;

import java.time.OffsetDateTime;
import java.util.Map;

/** One audit-log entry as returned by GET /audit. */
public record AuditLogResponse(
        Long id,
        String actor,
        String action,
        String target,
        Map<String, Object> metadata,
        OffsetDateTime timestamp
) {
    public static AuditLogResponse from(AuditLog a) {
        return new AuditLogResponse(
                a.getId(), a.getActor(), a.getAction(), a.getTarget(), a.getMetadata(), a.getOccurredAt());
    }
}
