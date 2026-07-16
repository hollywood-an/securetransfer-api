package com.securetransfer.api.repository;

import com.securetransfer.api.domain.Tenant;
import com.securetransfer.api.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Data-layer access for login accounts.
 */
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);

    /** All logins in ONE tenant — used by the ADMIN-only user list. */
    List<User> findByTenant(Tenant tenant);
}
