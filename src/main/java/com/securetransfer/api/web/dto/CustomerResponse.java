package com.securetransfer.api.web.dto;

import com.securetransfer.api.domain.Customer;

import java.time.OffsetDateTime;

/**
 * What we send back for a customer. Using a DTO (not the Customer entity) means
 * we control exactly which fields are exposed and never leak internal mapping.
 */
public record CustomerResponse(
        Long id,
        String name,
        String email,
        OffsetDateTime createdAt
) {
    /** Convert an entity into its response DTO. */
    public static CustomerResponse from(Customer c) {
        return new CustomerResponse(c.getId(), c.getName(), c.getEmail(), c.getCreatedAt());
    }
}
