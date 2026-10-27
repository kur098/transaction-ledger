package com.example.ledger.account.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.example.ledger.account.Account;
import com.example.ledger.account.AccountStatus;
import com.fasterxml.jackson.annotation.JsonFormat;

/** Amounts are serialized as strings to preserve exact decimal representation. */
public record AccountResponse(
        UUID accountId,
        String accountNumber,
        String currency,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal openingBalance,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal balance,
        AccountStatus status,
        Instant createdAt) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(account.id(), account.accountNumber(), account.currency(),
                account.openingBalance(), account.balance(), account.status(), account.createdAt());
    }
}
