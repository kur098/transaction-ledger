package com.example.ledger.transfer.exception;

import com.example.ledger.common.DomainException;
import com.example.ledger.common.ErrorCategory;

/** Maps to 409 Conflict (spec 7.1). */
public class IdempotencyConflictException extends DomainException {

    private IdempotencyConflictException(String code, String message) {
        super(code, ErrorCategory.CONFLICT, message);
    }

    public static IdempotencyConflictException keyReused() {
        return new IdempotencyConflictException("IDEMPOTENCY_KEY_REUSED",
                "This Idempotency-Key was already used with a different request.");
    }

    public static IdempotencyConflictException inProgress() {
        return new IdempotencyConflictException("IDEMPOTENCY_KEY_IN_PROGRESS",
                "A request with this Idempotency-Key is still being processed. Retry shortly.");
    }
}
