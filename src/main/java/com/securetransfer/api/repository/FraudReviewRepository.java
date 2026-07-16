package com.securetransfer.api.repository;

import com.securetransfer.api.domain.FraudReview;
import com.securetransfer.api.domain.FraudReviewStatus;
import com.securetransfer.api.domain.Tenant;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Data-layer access for fraud reviews.
 */
public interface FraudReviewRepository extends JpaRepository<FraudReview, Long> {

    /** The review queue, optionally filtered by status, paginated. */
    Page<FraudReview> findByStatus(FraudReviewStatus status, Pageable pageable);

    /** The review queue for ONE tenant (the caller's bank), newest first. */
    Page<FraudReview> findByTenant(Tenant tenant, Pageable pageable);

    /** The review queue for one tenant, filtered by status. */
    Page<FraudReview> findByStatusAndTenant(FraudReviewStatus status, Tenant tenant, Pageable pageable);

    /**
     * Load a review FOR UPDATE (pessimistic write lock). Both writers — the async
     * agent verdict and the human decision — take this lock so they serialize on
     * the row: the second one in re-reads the committed status under the lock, so
     * neither can clobber the other (e.g. an in-flight agent verdict can't
     * overwrite a human's DECIDED state). Same row-locking idea as Account.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from FraudReview r where r.id = :id")
    Optional<FraudReview> findByIdForUpdate(@Param("id") Long id);
}
