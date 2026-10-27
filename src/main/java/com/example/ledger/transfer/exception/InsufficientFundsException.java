package com.example.ledger.transfer.exception;

import com.example.ledger.common.DomainException;
import com.example.ledger.common.ErrorCategory;

public class InsufficientFundsException extends DomainException {

    public InsufficientFundsException() {
        super("INSUFFICIENT_FUNDS", ErrorCategory.BUSINESS_RULE, "The source account has insufficient funds.");
    }
}
