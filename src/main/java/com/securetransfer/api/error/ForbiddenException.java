package com.securetransfer.api.error;

/**
 * Thrown when an authenticated user tries to do something they're not allowed to
 * (e.g. a CUSTOMER touching another customer's account). Maps to HTTP 403.
 */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
