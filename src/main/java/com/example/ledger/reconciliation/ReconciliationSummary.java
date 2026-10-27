package com.example.ledger.reconciliation;

import java.util.List;

/**
 * Aggregate counts for a run.
 *
 * <p><b>Counting model</b> (spec 9.5): counts are numbers of reconciliation items, and every
 * external file row produces exactly one item. Therefore
 *
 * <pre>
 * totalRows = matched + mismatched + missingInternal + duplicateExternal + duplicateInternal + invalidRecords
 * </pre>
 *
 * and {@code missingExternal} counts internal transfers (which have no row) separately. A row with
 * several discrepancies is counted once in {@code mismatched}; its individual codes are on the item.
 */
public record ReconciliationSummary(
        int totalRows,
        int validRows,
        int matched,
        int mismatched,
        int missingInternal,
        int missingExternal,
        int duplicateExternal,
        int duplicateInternal,
        int invalidRecords) {

    public static ReconciliationSummary from(int totalRows, int validRows, List<ReconciliationFinding> findings) {
        int matched = 0;
        int mismatched = 0;
        int missingInternal = 0;
        int missingExternal = 0;
        int duplicateExternal = 0;
        int duplicateInternal = 0;
        int invalid = 0;
        for (ReconciliationFinding f : findings) {
            switch (f.resultType()) {
                case MATCHED -> matched++;
                case MISSING_INTERNAL -> missingInternal++;
                case MISSING_EXTERNAL -> missingExternal++;
                case DUPLICATE_EXTERNAL -> duplicateExternal++;
                case DUPLICATE_INTERNAL -> duplicateInternal++;
                case INVALID_EXTERNAL_RECORD -> invalid++;
                case AMOUNT_MISMATCH, ACCOUNT_MISMATCH, CURRENCY_MISMATCH, TIME_MISMATCH -> mismatched++;
            }
        }
        return new ReconciliationSummary(totalRows, validRows, matched, mismatched, missingInternal,
                missingExternal, duplicateExternal, duplicateInternal, invalid);
    }

    /** True when the row-based counts add up to the number of rows in the file. */
    public boolean rowCountsReconcile() {
        return totalRows == matched + mismatched + missingInternal + duplicateExternal + duplicateInternal + invalidRecords;
    }
}
