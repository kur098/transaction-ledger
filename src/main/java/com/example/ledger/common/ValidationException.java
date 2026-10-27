package com.example.ledger.common;

public class ValidationException extends DomainException {

    public ValidationException(String code, String message) {
        super(code, ErrorCategory.VALIDATION, message);
    }
}
