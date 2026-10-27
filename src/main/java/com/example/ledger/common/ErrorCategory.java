package com.example.ledger.common;

/**
 * Broad failure categories. {@link GlobalExceptionHandler} maps each one to an HTTP status
 * so that the mapping is defined in exactly one place (spec section 10).
 */
public enum ErrorCategory {
    /** 400 - the request itself is malformed or violates a field rule. */
    VALIDATION,
    /** 404 - a referenced resource does not exist. */
    NOT_FOUND,
    /** 409 - conflicts with existing state (idempotency key reuse, duplicate account number). */
    CONFLICT,
    /** 422 - well-formed request that violates a business rule (insufficient funds, inactive account). */
    BUSINESS_RULE,
    /** 503 - transient infrastructure failure; the client may retry with the same idempotency key. */
    UNAVAILABLE
}
