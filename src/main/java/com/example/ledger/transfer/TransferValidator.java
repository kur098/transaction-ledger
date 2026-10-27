package com.example.ledger.transfer;

import java.math.BigDecimal;
import java.util.Set;
import java.util.regex.Pattern;

import com.example.ledger.account.Account;
import com.example.ledger.common.Money;
import com.example.ledger.transfer.exception.AccountNotActiveException;
import com.example.ledger.transfer.exception.CurrencyMismatchException;
import com.example.ledger.transfer.exception.InsufficientFundsException;
import com.example.ledger.transfer.exception.InvalidTransferException;

/**
 * Transfer validity rules from spec 4.2. Pure Java (no Spring) so every rule is unit-testable.
 *
 * <p>Request-level rules run before anything touches the database; account-level rules run only
 * after both accounts are locked, so the balance check cannot race with another transfer.
 */
public final class TransferValidator {

    public static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;
    public static final int MAX_REFERENCE_LENGTH = 64;
    public static final String DEFAULT_CLIENT_ID = "default";

    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");
    /** Visible ASCII only: keys are opaque but must be safe to store, log and compare. */
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[\\x21-\\x7E]{1,128}$");
    private static final Pattern CLIENT_ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
    private static final Pattern REFERENCE = Pattern.compile("^[\\x21-\\x7E]{1,64}$");

    private TransferValidator() {
    }

    /**
     * Validates field rules and returns a canonical copy (amount at scale 2, currency upper-case,
     * reference trimmed or null) used both for execution and for the idempotency fingerprint.
     */
    public static TransferRequest validateAndNormalize(TransferRequest request, Set<String> supportedCurrencies) {
        if (request == null) {
            throw new InvalidTransferException("VALIDATION_ERROR", "Request body is required.");
        }
        if (request.sourceAccountId() == null) {
            throw new InvalidTransferException("VALIDATION_ERROR", "sourceAccountId is required.");
        }
        if (request.destinationAccountId() == null) {
            throw new InvalidTransferException("VALIDATION_ERROR", "destinationAccountId is required.");
        }
        if (request.sourceAccountId().equals(request.destinationAccountId())) {
            throw new InvalidTransferException("SAME_ACCOUNT", "Source and destination accounts must be different.");
        }

        BigDecimal amount = request.amount();
        if (amount == null) {
            throw new InvalidTransferException("VALIDATION_ERROR", "amount is required.");
        }
        if (amount.signum() <= 0) {
            throw new InvalidTransferException("INVALID_AMOUNT", "amount must be greater than zero.");
        }
        if (!Money.isRepresentable(amount)) {
            throw new InvalidTransferException("INVALID_AMOUNT",
                    "amount must have at most " + Money.SCALE + " decimal places and "
                            + Money.MAX_INTEGER_DIGITS + " integer digits.");
        }

        if (request.currency() == null || request.currency().isBlank()) {
            throw new InvalidTransferException("VALIDATION_ERROR", "currency is required.");
        }
        String currency = request.currency().trim().toUpperCase();
        if (!CURRENCY.matcher(currency).matches()) {
            throw new InvalidTransferException("INVALID_CURRENCY", "currency must be a 3-letter ISO 4217 code.");
        }
        if (!supportedCurrencies.contains(currency)) {
            throw new InvalidTransferException("UNSUPPORTED_CURRENCY", "Currency " + currency + " is not supported.");
        }

        String reference = request.reference() == null ? null : request.reference().trim();
        if (reference != null && reference.isEmpty()) {
            reference = null;
        }
        if (reference != null && !REFERENCE.matcher(reference).matches()) {
            throw new InvalidTransferException("INVALID_REFERENCE",
                    "reference must be 1-" + MAX_REFERENCE_LENGTH + " visible ASCII characters without spaces.");
        }

        return new TransferRequest(request.sourceAccountId(), request.destinationAccountId(),
                Money.normalize(amount), currency, reference);
    }

    public static String validateIdempotencyKey(String key) {
        if (key == null || !IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw new InvalidTransferException("INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key must be 1-" + MAX_IDEMPOTENCY_KEY_LENGTH + " visible ASCII characters.");
        }
        return key;
    }

    public static String normalizeClientId(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            return DEFAULT_CLIENT_ID;
        }
        String trimmed = clientId.trim();
        if (!CLIENT_ID.matcher(trimmed).matches()) {
            throw new InvalidTransferException("INVALID_CLIENT_ID",
                    "X-Client-Id must be 1-64 characters of letters, digits, '.', '_' or '-'.");
        }
        return trimmed;
    }

    /** Account-level rules. Call only while both accounts are locked. */
    public static void validateAccounts(Account source, Account destination, TransferRequest request) {
        if (!source.isActive()) {
            throw new AccountNotActiveException(source.id());
        }
        if (!destination.isActive()) {
            throw new AccountNotActiveException(destination.id());
        }
        if (!source.currency().equals(destination.currency())) {
            throw new CurrencyMismatchException("Source and destination accounts use different currencies.");
        }
        if (!source.currency().equals(request.currency())) {
            throw new CurrencyMismatchException(
                    "Transfer currency " + request.currency() + " does not match account currency " + source.currency() + ".");
        }
        if (!source.canCover(request.amount())) {
            throw new InsufficientFundsException();
        }
    }
}
