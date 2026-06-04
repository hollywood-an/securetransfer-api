package com.securetransfer.api.repository;

import com.securetransfer.api.domain.AuditLog;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.Repository;

/**
 * Data-layer access for the audit log.
 *
 * The log is APPEND-ONLY: AuditService only ever calls {@link #save}, and no web
 * endpoint updates or deletes entries. We extend the minimal {@link Repository}
 * marker (not JpaRepository, so no bulk delete/CRUD surface) plus
 * {@link JpaSpecificationExecutor}, used ONLY for the dynamic, filtered READ
 * behind the admin GET /audit. Specifications add a WHERE predicate only for
 * each non-null filter, which avoids the "(:x IS NULL OR ...)" pattern that
 * PostgreSQL can't type-infer when the parameter is a null timestamp.
 */
public interface AuditLogRepository
        extends Repository<AuditLog, Long>, JpaSpecificationExecutor<AuditLog> {

    AuditLog save(AuditLog entry);
}
