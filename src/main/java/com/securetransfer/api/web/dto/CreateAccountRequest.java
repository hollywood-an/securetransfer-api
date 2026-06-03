package com.securetransfer.api.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

/**
 * Request body for POST /accounts.
 *
 * `initialBalance` is optional: if omitted it defaults to 0 in the service.
 * When present it must be zero or positive (no negative starting balance).
 */
public record CreateAccountRequest(

        @NotNull(message = "customerId is required")
        Long customerId,

        @NotNull(message = "currency is required")
        @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be a 3-letter ISO code, e.g. USD")
        String currency,

        @PositiveOrZero(message = "initialBalance cannot be negative")
        BigDecimal initialBalance
) {
}
