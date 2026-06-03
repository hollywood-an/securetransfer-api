package com.securetransfer.api.error;

/**
 * Thrown when a request conflicts with existing data (e.g. a duplicate email).
 * GlobalExceptionHandler turns this into an HTTP 409 response.
 */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
