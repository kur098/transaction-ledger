package com.example.ledger.reconciliation;

import java.time.Instant;
import java.util.UUID;

public record ReconciliationRun(
        UUID id,
        String fileName,
        String fileHash,
        long fileSizeBytes,
        Mode mode,
        Status status,
        Instant scopeFrom,
        Instant scopeTo,
        String scopeCurrency,
        int timeToleranceSeconds,
        ReconciliationSummary summary,
        String failureReason,
        Instant startedAt,
        Instant completedAt) {

    /**
     * STRICT rejects the whole file if any row is invalid; PARTIAL reconciles the valid rows and
     * reports the invalid ones as INVALID_EXTERNAL_RECORD items (spec 9.1).
     */
    public enum Mode {
        STRICT,
        PARTIAL
    }

    /**
     * PROCESSING -> COMPLETED (reconciled), REJECTED (input violates the contract) or
     * FAILED (unexpected error; the file can be uploaded again safely).
     */
    public enum Status {
        PROCESSING,
        COMPLETED,
        REJECTED,
        FAILED
    }
}
