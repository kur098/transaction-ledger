package com.example.ledger.transfer.exception;

import java.util.UUID;

import com.example.ledger.common.DomainException;
import com.example.ledger.common.ErrorCategory;

public class AccountNotActiveException extends DomainException {

    public AccountNotActiveException(UUID accountId) {
        super("ACCOUNT_NOT_ACTIVE", ErrorCategory.BUSINESS_RULE, "Account " + accountId + " is not active.");
    }
}
