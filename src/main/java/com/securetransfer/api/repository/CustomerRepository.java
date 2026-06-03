package com.securetransfer.api.repository;

import com.securetransfer.api.domain.Customer;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository = the "talks to the database" layer.
 *
 * By extending JpaRepository, Spring Data generates an implementation at
 * runtime with the common operations already written for us: save(...),
 * findById(...), findAll(), deleteById(...), etc. We never write that SQL.
 */
public interface CustomerRepository extends JpaRepository<Customer, Long> {

    // Spring Data turns this method NAME into a query ("does a row with this
    // email exist?") automatically — no implementation needed.
    boolean existsByEmail(String email);
}
