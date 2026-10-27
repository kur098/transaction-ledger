package com.example.ledger.account;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable account snapshot. {@link #debit} and {@link #credit} return a new snapshot with the
 * projected balance; persisting it is the caller's job, inside the same transaction as the
 * ledger entries that justify the change (spec 4.3).
 */
public record Account(
        UUID id,
        String accountNumber,
        String currency,
        BigDecimal openingBalance,
        BigDecimal balance,
        AccountStatus status,
        Instant createdAt) {

    public Account {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(accountNumber, "accountNumber");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(balance, "balance");
        Objects.requireNonNull(status, "status");
    }

    public boolean isActive() {
        return status == AccountStatus.ACTIVE;
    }

    public boolean canCover(BigDecimal amount) {
        return balance.compareTo(amount) >= 0;
    }

    public Account debit(BigDecimal amount) {
        requirePositive(amount);
        BigDecimal newBalance = balance.subtract(amount);
        if (newBalance.signum() < 0) {
            throw new IllegalStateException("Debit would make balance negative for account " + id);
        }
        return withBalance(newBalance);
    }

    public Account credit(BigDecimal amount) {
        requirePositive(amount);
        return withBalance(balance.add(amount));
    }

    private Account withBalance(BigDecimal newBalance) {
        return new Account(id, accountNumber, currency, openingBalance, newBalance, status, createdAt);
    }

    private static void requirePositive(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
    }
}
