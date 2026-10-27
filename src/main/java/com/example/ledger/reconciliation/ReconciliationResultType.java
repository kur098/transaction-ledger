package com.example.ledger.reconciliation;

/** Classification of a reconciliation finding (spec 9.4). */
public enum ReconciliationResultType {
    MATCHED,
    MISSING_EXTERNAL,
    MISSING_INTERNAL,
    DUPLICATE_EXTERNAL,
    DUPLICATE_INTERNAL,
    AMOUNT_MISMATCH,
    ACCOUNT_MISMATCH,
    CURRENCY_MISMATCH,
    TIME_MISMATCH,
    INVALID_EXTERNAL_RECORD;

    public boolean isMismatch() {
        return this == AMOUNT_MISMATCH || this == ACCOUNT_MISMATCH
                || this == CURRENCY_MISMATCH || this == TIME_MISMATCH;
    }
}
