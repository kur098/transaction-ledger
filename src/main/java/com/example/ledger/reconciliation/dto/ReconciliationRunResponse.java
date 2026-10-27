package com.example.ledger.reconciliation.dto;

import java.time.Instant;
import java.util.UUID;

import com.example.ledger.reconciliation.ReconciliationRun;
import com.example.ledger.reconciliation.ReconciliationSummary;

/**
 * Run summary (spec 9.5). Counting model: one item per file row, so
 * totalExternalRecords = matched + mismatched + missingInternal + duplicateExternal + duplicateInternal + invalidRecords;
 * missingExternal counts internal transfers with no row and is reported separately.
 */
public record ReconciliationRunResponse(
        UUID runId,
        ReconciliationRun.Status status,
        String fileName,
        String fileHash,
        long fileSizeBytes,
        ReconciliationRun.Mode mode,
        Instant scopeFrom,
        Instant scopeTo,
        String scopeCurrency,
        int timeToleranceSeconds,
        int totalExternalRecords,
        int validRecords,
        int matched,
        int mismatched,
        int missingInternal,
        int missingExternal,
        int duplicateExternal,
        int duplicateInternal,
        int invalidRecords,
        String failureReason,
        Instant startedAt,
        Instant completedAt) {

    public static ReconciliationRunResponse from(ReconciliationRun run) {
        ReconciliationSummary s = run.summary();
        return new ReconciliationRunResponse(run.id(), run.status(), run.fileName(), run.fileHash(),
                run.fileSizeBytes(), run.mode(), run.scopeFrom(), run.scopeTo(), run.scopeCurrency(),
                run.timeToleranceSeconds(), s.totalRows(), s.validRows(), s.matched(), s.mismatched(),
                s.missingInternal(), s.missingExternal(), s.duplicateExternal(), s.duplicateInternal(),
                s.invalidRecords(), run.failureReason(), run.startedAt(), run.completedAt());
    }
}
