package com.securetransfer.api.repository;

import com.securetransfer.api.domain.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Data-layer access for ledger entries (read-only in Phase 1).
 */
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    // Derived query: all entries for one account, newest first (createdAt then
    // id, so entries created in the same instant still have a stable order).
    List<LedgerEntry> findByAccountIdOrderByCreatedAtDescIdDesc(Long accountId);
}
