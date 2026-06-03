package com.securetransfer.api.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * Request body for POST /transfers.
 *
 * @Positive enforces amount > 0, so a zero or negative amount is rejected with
 * HTTP 400 before the service runs.
 */
public record CreateTransferRequest(

        @NotNull(message = "fromAccount is required")
        Long fromAccount,

        @NotNull(message = "toAccount is required")
        Long toAccount,

        @NotNull(message = "amount is required")
        @Positive(message = "amount must be greater than zero")
        BigDecimal amount
) {
}
