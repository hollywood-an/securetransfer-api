package com.securetransfer.api.web;

import com.securetransfer.api.domain.AuditLog;
import com.securetransfer.api.service.AuditService;
import com.securetransfer.api.web.dto.AuditLogResponse;
import com.securetransfer.api.web.dto.PagedResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;

/**
 * ADMIN-only read access to the audit log. There is intentionally NO endpoint to
 * update or delete audit entries — the log is append-only.
 */
@RestController
@RequestMapping("/audit")
@PreAuthorize("hasRole('ADMIN')")
public class AuditController {

    private static final int MAX_PAGE_SIZE = 100;

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    // GET /audit?actor=&action=&from=&to=&page=&size=  — newest first.
    @GetMapping
    public PagedResponse<AuditLogResponse> list(
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        // Clamp paging to safe bounds: no negative page, page size capped so a
        // client can't request an unbounded result set.
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);

        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "occurredAt"));
        Page<AuditLog> result = auditService.search(actor, action, from, to, pageable);
        return PagedResponse.from(result.map(AuditLogResponse::from));
    }
}
