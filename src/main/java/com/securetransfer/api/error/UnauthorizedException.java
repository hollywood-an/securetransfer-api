package com.securetransfer.api.error;

/**
 * Thrown when authentication fails (e.g. a bad username/password at login).
 * Maps to HTTP 401.
 */
public class UnauthorizedException extends RuntimeException {
    public UnauthorizedException(String message) {
        super(message);
    }
}
