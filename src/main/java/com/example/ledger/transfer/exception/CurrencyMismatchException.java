package com.example.ledger.transfer.exception;

import com.example.ledger.common.DomainException;
import com.example.ledger.common.ErrorCategory;

public class CurrencyMismatchException extends DomainException {

    public CurrencyMismatchException(String message) {
        super("CURRENCY_MISMATCH", ErrorCategory.BUSINESS_RULE, message);
    }
}
