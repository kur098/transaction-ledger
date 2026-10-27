package com.example.ledger.common;

public class NotFoundException extends DomainException {

    public NotFoundException(String code, String message) {
        super(code, ErrorCategory.NOT_FOUND, message);
    }

    public static NotFoundException account(Object id) {
        return new NotFoundException("ACCOUNT_NOT_FOUND", "Account " + id + " does not exist.");
    }

    public static NotFoundException transfer(Object id) {
        return new NotFoundException("TRANSFER_NOT_FOUND", "Transfer " + id + " does not exist.");
    }

    public static NotFoundException reconciliationRun(Object id) {
        return new NotFoundException("RECONCILIATION_RUN_NOT_FOUND", "Reconciliation run " + id + " does not exist.");
    }
}
