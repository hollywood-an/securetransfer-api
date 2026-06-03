package com.securetransfer.api.repository;

import com.securetransfer.api.domain.Account;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Data-layer access for accounts. The inherited save(...) and findById(...) are
 * all Phase 1 needs. (Phase 2 will add a locking query for safe transfers.)
 */
public interface AccountRepository extends JpaRepository<Account, Long> {
}
