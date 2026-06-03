package com.securetransfer.api.error;

/**
 * Thrown for a semantically invalid request that bean-validation can't express
 * as a single-field rule — e.g. transferring to the same account, or between
 * accounts of different currencies. Maps to HTTP 400 Bad Request.
 */
public class BadRequestException extends RuntimeException {
    public BadRequestException(String message) {
        super(message);
    }
}
