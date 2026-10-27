package com.example.ledger.reconciliation.dto;

import java.util.List;
import java.util.UUID;

import com.example.ledger.reconciliation.ReconciliationItem;
import com.example.ledger.reconciliation.ReconciliationResultType;
import com.fasterxml.jackson.databind.JsonNode;

public record ReconciliationItemResponse(
        UUID itemId,
        ReconciliationResultType resultType,
        List<ReconciliationResultType> discrepancyCodes,
        Integer rowNumber,
        String externalReference,
        UUID internalTransferId,
        UUID externalTransactionId,
        JsonNode details) {

    public static ReconciliationItemResponse from(ReconciliationItem item, JsonNode details) {
        return new ReconciliationItemResponse(item.id(), item.resultType(), item.discrepancyCodes(),
                item.rowNumber(), item.externalReference(), item.internalTransferId(),
                item.externalTransactionId(), details);
    }
}
