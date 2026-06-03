package com.securetransfer.api.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Request body for POST /customers.
 *
 * A Java "record" is a concise, immutable data carrier (the compiler generates
 * the constructor, getters, equals, etc.). The validation annotations are
 * checked when the controller marks the parameter @Valid: if they fail, the
 * request is rejected with HTTP 400 before any business logic runs.
 */
public record CreateCustomerRequest(

        @NotBlank(message = "name is required")
        String name,

        @NotBlank(message = "email is required")
        @Email(message = "email must be a valid email address")
        String email
) {
}
