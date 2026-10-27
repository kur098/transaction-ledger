package com.example.ledger.reconciliation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A persisted finding (table reconciliation_items). {@code detailsJson} is the raw JSONB text. */
public record ReconciliationItem(
        UUID id,
        UUID runId,
        UUID internalTransferId,
        UUID externalTransactionId,
        Integer rowNumber,
        String externalReference,
        ReconciliationResultType resultType,
        List<ReconciliationResultType> discrepancyCodes,
        String detailsJson,
        Instant createdAt) {
}
