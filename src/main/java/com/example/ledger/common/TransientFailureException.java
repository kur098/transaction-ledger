package com.example.ledger.common;

/** Raised after bounded retries are exhausted; maps to 503 so the client retries later. */
public class TransientFailureException extends DomainException {

    public TransientFailureException(String message, Throwable cause) {
        super("TEMPORARILY_UNAVAILABLE", ErrorCategory.UNAVAILABLE, message);
        initCause(cause);
    }
}
