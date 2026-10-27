package com.example.ledger.reconciliation;

import java.util.List;

/**
 * @param fileError non-null when the file as a whole is unusable (bad encoding, missing header
 *                  columns, too many rows); in that case no rows are returned
 * @param totalRows number of non-blank data rows (valid + invalid)
 */
public record CsvParseResult(String fileError, List<ExternalRecord> validRecords, List<InvalidRow> invalidRows) {

    public CsvParseResult {
        validRecords = List.copyOf(validRecords);
        invalidRows = List.copyOf(invalidRows);
    }

    public static CsvParseResult fileError(String error) {
        return new CsvParseResult(error, List.of(), List.of());
    }

    public boolean hasFileError() {
        return fileError != null;
    }

    public int totalRows() {
        return validRecords.size() + invalidRows.size();
    }
}
