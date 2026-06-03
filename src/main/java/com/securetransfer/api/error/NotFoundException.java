package com.securetransfer.api.error;

/**
 * Thrown when a requested resource (customer, account, …) doesn't exist.
 * GlobalExceptionHandler turns this into an HTTP 404 response.
 */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
