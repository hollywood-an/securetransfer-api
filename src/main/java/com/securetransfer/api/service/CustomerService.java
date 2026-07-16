package com.securetransfer.api.service;

import com.securetransfer.api.domain.Customer;
import com.securetransfer.api.domain.Tenant;
import com.securetransfer.api.error.ConflictException;
import com.securetransfer.api.repository.CustomerRepository;
import com.securetransfer.api.web.dto.CreateCustomerRequest;
import com.securetransfer.api.web.dto.CustomerResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service = the business-logic layer. Controllers call services; services call
 * repositories. Keeping the rules here (not in the controller) keeps the web
 * layer thin and the logic easy to test and reuse.
 */
@Service
public class CustomerService {

    private final CustomerRepository customers;

    // Constructor injection: Spring sees this constructor and passes in the
    // repository bean automatically. (No @Autowired needed for a single ctor.)
    public CustomerService(CustomerRepository customers) {
        this.customers = customers;
    }

    /**
     * Create a customer. @Transactional wraps the whole method in one database
     * transaction: it commits if the method returns normally, or rolls back if
     * it throws.
     */
    @Transactional
    public CustomerResponse create(CreateCustomerRequest request, Tenant tenant) {
        // Friendly check for a duplicate email WITHIN the caller's bank. (The DB's
        // per-tenant UNIQUE constraint is the ultimate backstop, handled in
        // GlobalExceptionHandler.)
        if (customers.existsByTenantAndEmail(tenant, request.email())) {
            throw new ConflictException(
                    "A customer with email '" + request.email() + "' already exists");
        }

        Customer saved = customers.save(new Customer(request.name(), request.email(), tenant));
        return CustomerResponse.from(saved);
    }
}
