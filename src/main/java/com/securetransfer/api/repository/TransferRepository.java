package com.securetransfer.api.repository;

import com.securetransfer.api.domain.Transfer;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Data-layer access for transfers. The inherited save(...) is all Phase 2 needs.
 */
public interface TransferRepository extends JpaRepository<Transfer, Long> {
}
