package com.example.ledger.common;

/**
 * Base class for all expected, client-visible failures. Unchecked on purpose: Spring's
 * transaction infrastructure rolls back on unchecked exceptions by default, so throwing one
 * anywhere inside a transfer guarantees that no partial financial state commits.
 */
public class DomainException extends RuntimeException {

    private final String code;
    private final ErrorCategory category;

    public DomainException(String code, ErrorCategory category, String message) {
        super(message);
        this.code = code;
        this.category = category;
    }

    public String getCode() {
        return code;
    }

    public ErrorCategory getCategory() {
        return category;
    }
}
