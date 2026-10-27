package com.example.ledger.reconciliation;

import java.util.UUID;

/** A valid external row as staged for a specific run (table external_transactions). */
public record ExternalTransaction(UUID id, UUID runId, ExternalRecord record) {
}
