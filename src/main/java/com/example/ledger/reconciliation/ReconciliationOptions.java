package com.example.ledger.reconciliation;

import java.time.Instant;

/**
 * Per-run parameters.
 *
 * @param scopeFrom / scopeTo  window of internal created_at values considered for MISSING_EXTERNAL.
 *                             Both or neither. When omitted, the window is the span of the file's
 *                             transaction times widened by the tolerance on each side.
 * @param scopeCurrency        optional currency restriction for MISSING_EXTERNAL candidates
 * @param timeToleranceSeconds maximum allowed |internal.created_at - external.transaction_time|
 * @param rerun                false (default): an identical file returns its existing run instead
 *                             of importing the rows again; true: always create a new run, e.g. to
 *                             re-check the same statement after ledger corrections
 */
public record ReconciliationOptions(
        ReconciliationRun.Mode mode,
        Instant scopeFrom,
        Instant scopeTo,
        String scopeCurrency,
        int timeToleranceSeconds,
        boolean rerun) {
}
