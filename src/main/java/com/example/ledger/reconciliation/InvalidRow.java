package com.example.ledger.reconciliation;

import java.util.List;

/**
 * A row that violates the input contract (spec 9.1). Captured with its row number and every
 * error found, so the whole file can be fixed in one pass.
 *
 * @param externalReference the reference if one could be read, otherwise null
 */
public record InvalidRow(int rowNumber, String externalReference, String rawLine, List<String> errors) {

    public InvalidRow {
        errors = List.copyOf(errors);
    }
}
