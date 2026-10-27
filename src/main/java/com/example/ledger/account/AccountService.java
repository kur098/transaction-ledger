package com.example.ledger.account;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.example.ledger.account.dto.CreateAccountRequest;
import com.example.ledger.common.DomainException;
import com.example.ledger.common.ErrorCategory;
import com.example.ledger.common.LedgerProperties;
import com.example.ledger.common.Money;
import com.example.ledger.common.NotFoundException;
import com.example.ledger.common.ValidationException;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    private final AccountRepository accounts;
    private final LedgerProperties properties;
    private final Clock clock;

    public AccountService(AccountRepository accounts, LedgerProperties properties, Clock clock) {
        this.accounts = accounts;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public Account create(CreateAccountRequest request) {
        String currency = request.currency().trim().toUpperCase();
        if (!properties.supportedCurrencies().contains(currency)) {
            throw new ValidationException("UNSUPPORTED_CURRENCY", "Currency " + currency + " is not supported.");
        }
        BigDecimal opening = request.openingBalance() == null ? BigDecimal.ZERO : request.openingBalance();
        if (opening.signum() < 0 || !Money.isRepresentable(opening)) {
            throw new ValidationException("INVALID_AMOUNT",
                    "openingBalance must be >= 0 with at most 2 decimal places.");
        }
        opening = Money.normalize(opening);

        Account account = new Account(UUID.randomUUID(), request.accountNumber().trim(), currency,
                opening, opening, AccountStatus.ACTIVE, Instant.now(clock).truncatedTo(ChronoUnit.MICROS));
        try {
            accounts.insert(account);
        } catch (DuplicateKeyException e) {
            throw new DomainException("ACCOUNT_NUMBER_TAKEN", ErrorCategory.CONFLICT,
                    "Account number " + account.accountNumber() + " already exists.");
        }
        return account;
    }

    @Transactional(readOnly = true)
    public Account get(UUID id) {
        return accounts.findById(id).orElseThrow(() -> NotFoundException.account(id));
    }

    /**
     * Status changes lock the account so they serialize with in-flight transfers.
     * CLOSED is terminal and requires a zero balance.
     */
    @Transactional
    public Account changeStatus(UUID id, AccountStatus newStatus) {
        Account account = accounts.findByIdForUpdate(id).orElseThrow(() -> NotFoundException.account(id));
        if (account.status() == newStatus) {
            return account;
        }
        if (account.status() == AccountStatus.CLOSED) {
            throw new DomainException("ACCOUNT_CLOSED", ErrorCategory.BUSINESS_RULE,
                    "A closed account cannot be reopened.");
        }
        if (newStatus == AccountStatus.CLOSED && account.balance().signum() != 0) {
            throw new DomainException("ACCOUNT_BALANCE_NOT_ZERO", ErrorCategory.BUSINESS_RULE,
                    "An account can only be closed when its balance is zero.");
        }
        accounts.updateStatus(id, newStatus);
        return new Account(account.id(), account.accountNumber(), account.currency(), account.openingBalance(),
                account.balance(), newStatus, account.createdAt());
    }
}
