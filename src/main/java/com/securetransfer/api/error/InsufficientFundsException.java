package com.securetransfer.api.error;

/**
 * Thrown when the sending account doesn't have enough balance for a transfer.
 * GlobalExceptionHandler maps this to HTTP 422 Unprocessable Entity: the request
 * was well-formed, but the account can't fund it.
 */
public class InsufficientFundsException extends RuntimeException {
    public InsufficientFundsException(String message) {
        super(message);
    }
}
