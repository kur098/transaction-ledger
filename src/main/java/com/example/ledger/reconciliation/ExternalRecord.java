package com.example.ledger.reconciliation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;

/**
 * One valid row from the external file, already trimmed and normalized.
 *
 * @param rowNumber physical line number in the file where the row starts (header is usually row 1)
 */
public record ExternalRecord(
        int rowNumber,
        String externalReference,
        String sourceAccount,
        String destinationAccount,
        BigDecimal amount,
        String currency,
        Instant transactionTime) {

    /** References match case-insensitively after trimming (documented matching rule). */
    public String referenceKey() {
        return normalizeReference(externalReference);
    }

    public static String normalizeReference(String reference) {
        return reference == null ? null : reference.trim().toUpperCase(Locale.ROOT);
    }
}
