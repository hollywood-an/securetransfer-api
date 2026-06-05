package com.securetransfer.api.repository;

import com.securetransfer.api.domain.Transfer;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Data-layer access for transfers. Phase 6 adds read-only queries used by the
 * fraud rules and the agent's read-only tools.
 */
public interface TransferRepository extends JpaRepository<Transfer, Long> {

    /** Velocity rule: how many transfers this account has SENT since a cutoff. */
    long countByFromAccountAndCreatedAtAfter(Long fromAccount, OffsetDateTime after);

    /** New-payee rule: has this exact sender→receiver pair ever transferred before? */
    boolean existsByFromAccountAndToAccount(Long fromAccount, Long toAccount);

    /** Recent activity for an account (as sender OR receiver), newest first. */
    List<Transfer> findByFromAccountOrToAccountOrderByCreatedAtDescIdDesc(
            Long fromAccount, Long toAccount, Pageable pageable);

    /** Total amount this account has SENT since a cutoff (0 if none). */
    @Query("""
            SELECT COALESCE(SUM(t.amount), 0) FROM Transfer t
            WHERE t.fromAccount = :accountId AND t.createdAt >= :after
            """)
    BigDecimal sumOutgoingSince(@Param("accountId") Long accountId,
                                @Param("after") OffsetDateTime after);
}
