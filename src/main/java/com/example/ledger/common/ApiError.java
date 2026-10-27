package com.example.ledger.common;

import java.time.Instant;

/** Uniform error body (spec section 10). Never carries SQL, stack traces or credentials. */
public record ApiError(String code, String message, Instant timestamp, String requestId) {
}
