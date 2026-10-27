package com.example.ledger.reconciliation;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One reconciliation item.
 *
 * @param resultType        primary classification
 * @param discrepancyCodes  every mismatch found for the pair (empty unless resultType is a mismatch);
 *                          supports "a single transaction can have multiple discrepancies" (spec 9.4)
 * @param rowNumber         originating file row; null only for MISSING_EXTERNAL
 * @param internalTransferId related internal transfer, if any
 * @param details           investigation context (expected vs actual values, related ids)
 */
public record ReconciliationFinding(
        ReconciliationResultType resultType,
        List<ReconciliationResultType> discrepancyCodes,
        Integer rowNumber,
        String externalReference,
        UUID internalTransferId,
        Map<String, Object> details) {

    public ReconciliationFinding {
        discrepancyCodes = List.copyOf(discrepancyCodes);
        details = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(details));
    }
}
