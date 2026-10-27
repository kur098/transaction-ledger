package com.example.ledger.reconciliation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Read-only projection of a committed internal transfer used for matching.
 *
 * @param inScope true when the transfer falls inside the run's reconciliation scope (date range and
 *                currency); only in-scope transfers can be reported as MISSING_EXTERNAL. Transfers
 *                loaded only because the file references them are out of scope.
 */
public record InternalTransferView(
        UUID transferId,
        String reference,
        String sourceAccountNumber,
        String destinationAccountNumber,
        BigDecimal amount,
        String currency,
        Instant createdAt,
        boolean inScope) {

    public String referenceKey() {
        return ExternalRecord.normalizeReference(reference);
    }
}
