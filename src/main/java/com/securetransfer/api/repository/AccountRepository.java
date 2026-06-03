package com.securetransfer.api.repository;

import com.securetransfer.api.domain.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Data-layer access for accounts.
 */
public interface AccountRepository extends JpaRepository<Account, Long> {

    /**
     * Load an account FOR UPDATE — a pessimistic write lock.
     *
     * @Lock(PESSIMISTIC_WRITE) makes Hibernate issue "SELECT ... FOR UPDATE",
     * which locks the account row in the database. While this transaction holds
     * that lock, any other transfer touching the same row must WAIT until we
     * commit. That's what stops two concurrent transfers from both reading a
     * stale balance and overdrawing the account.
     *
     * TransferService always locks the two accounts in ascending id order, so
     * two opposite transfers (A->B and B->A) can never deadlock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") Long id);
}
