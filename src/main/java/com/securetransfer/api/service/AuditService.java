package com.securetransfer.api.service;

import com.securetransfer.api.domain.AuditLog;
import com.securetransfer.api.repository.AuditLogRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Records and queries the append-only audit log.
 */
@Service
public class AuditService {

    private final AuditLogRepository auditLog;

    public AuditService(AuditLogRepository auditLog) {
        this.auditLog = auditLog;
    }

    /**
     * Append one sensitive-action record.
     *
     * IMPORTANT: {@code actor} must be the authenticated user's identity, never
     * a client-supplied value (which could be forged). Callers pass it from the
     * security context / @AuthenticationPrincipal.
     *
     * No special propagation: this joins the caller's transaction if there is one
     * (so a write action and its audit row commit together), or runs in its own
     * transaction when called from a non-transactional caller (e.g. logging a
     * read).
     */
    @Transactional
    public void record(String actor, String action, String target, Map<String, Object> metadata) {
        auditLog.save(new AuditLog(actor, action, target, metadata));
    }

    /**
     * Paginated, filtered read of the audit log (ADMIN-only at the web layer).
     * Builds the WHERE clause dynamically: a predicate is added only for each
     * non-null/non-blank filter, so there's no SQL-injection surface and no
     * untyped-null parameter problem.
     */
    @Transactional(readOnly = true)
    public Page<AuditLog> search(String actor, String action,
                                 OffsetDateTime from, OffsetDateTime to, Pageable pageable) {
        Specification<AuditLog> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (actor != null && !actor.isBlank()) {
                predicates.add(cb.equal(root.get("actor"), actor));
            }
            if (action != null && !action.isBlank()) {
                predicates.add(cb.equal(root.get("action"), action));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("occurredAt"), to));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return auditLog.findAll(spec, pageable);
    }
}
