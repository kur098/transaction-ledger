package com.example.ledger.reconciliation;

import static com.example.ledger.reconciliation.ReconciliationResultType.ACCOUNT_MISMATCH;
import static com.example.ledger.reconciliation.ReconciliationResultType.AMOUNT_MISMATCH;
import static com.example.ledger.reconciliation.ReconciliationResultType.CURRENCY_MISMATCH;
import static com.example.ledger.reconciliation.ReconciliationResultType.DUPLICATE_EXTERNAL;
import static com.example.ledger.reconciliation.ReconciliationResultType.DUPLICATE_INTERNAL;
import static com.example.ledger.reconciliation.ReconciliationResultType.INVALID_EXTERNAL_RECORD;
import static com.example.ledger.reconciliation.ReconciliationResultType.MATCHED;
import static com.example.ledger.reconciliation.ReconciliationResultType.MISSING_EXTERNAL;
import static com.example.ledger.reconciliation.ReconciliationResultType.MISSING_INTERNAL;
import static com.example.ledger.reconciliation.ReconciliationResultType.TIME_MISMATCH;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Unit tests for spec 9.3/9.4 matching and classification, plus the 9.5 counting model. */
class ReconciliationMatcherTest {

    private static final Instant T0 = Instant.parse("2026-10-22T08:00:00Z");
    private static final Duration TOLERANCE = Duration.ofMinutes(5);

    private static ExternalRecord ext(int row, String ref, String src, String dst, String amount, String ccy, Instant t) {
        return new ExternalRecord(row, ref, src, dst, new BigDecimal(amount), ccy, t);
    }

    private static ExternalRecord ext(int row, String ref) {
        return ext(row, ref, "ACC-001", "ACC-002", "150.00", "USD", T0);
    }

    private static InternalTransferView internal(String ref, String src, String dst, String amount, String ccy,
                                                 Instant t, boolean inScope) {
        return new InternalTransferView(UUID.randomUUID(), ref, src, dst, new BigDecimal(amount), ccy, t, inScope);
    }

    private static InternalTransferView internal(String ref) {
        return internal(ref, "ACC-001", "ACC-002", "150.00", "USD", T0, true);
    }

    private static ReconciliationMatcher.Result match(List<ExternalRecord> ext, List<InternalTransferView> internal) {
        return ReconciliationMatcher.match(ext, List.of(), internal, TOLERANCE);
    }

    @Test
    void exactCounterpartIsMatched() {
        InternalTransferView t = internal("EXT-1001");
        var result = match(List.of(ext(2, "EXT-1001")), List.of(t));

        assertThat(result.findings()).singleElement().satisfies(f -> {
            assertThat(f.resultType()).isEqualTo(MATCHED);
            assertThat(f.discrepancyCodes()).isEmpty();
            assertThat(f.internalTransferId()).isEqualTo(t.transferId());
            assertThat(f.rowNumber()).isEqualTo(2);
        });
        assertThat(result.summary().matched()).isEqualTo(1);
    }

    @Test
    void referencesMatchCaseInsensitivelyAfterTrimming() {
        var result = match(List.of(ext(2, "  ext-1001 ")), List.of(internal("EXT-1001")));
        assertThat(result.findings()).singleElement().extracting(ReconciliationFinding::resultType).isEqualTo(MATCHED);
    }

    @Test
    void differentAmountIsAnAmountDiscrepancy() {
        var result = match(List.of(ext(2, "EXT-1", "ACC-001", "ACC-002", "155.00", "USD", T0)),
                List.of(internal("EXT-1")));

        ReconciliationFinding f = result.findings().get(0);
        assertThat(f.resultType()).isEqualTo(AMOUNT_MISMATCH);
        assertThat(f.discrepancyCodes()).containsExactly(AMOUNT_MISMATCH);
        assertThat(f.details()).containsKeys("external", "internal");
        assertThat(result.summary().mismatched()).isEqualTo(1);
    }

    @Test
    void amountComparisonIgnoresScale() {
        var result = match(List.of(ext(2, "EXT-1", "ACC-001", "ACC-002", "150.0", "USD", T0)),
                List.of(internal("EXT-1")));
        assertThat(result.findings().get(0).resultType()).isEqualTo(MATCHED);
    }

    @Test
    void wrongDestinationIsAnAccountDiscrepancy() {
        var result = match(List.of(ext(2, "EXT-1", "ACC-001", "ACC-999", "150.00", "USD", T0)),
                List.of(internal("EXT-1")));
        assertThat(result.findings().get(0).resultType()).isEqualTo(ACCOUNT_MISMATCH);
    }

    @Test
    void differentCurrencyIsACurrencyDiscrepancy() {
        var result = match(List.of(ext(2, "EXT-1", "ACC-001", "ACC-002", "150.00", "EUR", T0)),
                List.of(internal("EXT-1")));
        assertThat(result.findings().get(0).resultType()).isEqualTo(CURRENCY_MISMATCH);
    }

    @Test
    void timeOutsideToleranceIsATimeDiscrepancyAndInsideIsAMatch() {
        var outside = match(List.of(ext(2, "EXT-1", "ACC-001", "ACC-002", "150.00", "USD", T0.plusSeconds(301))),
                List.of(internal("EXT-1")));
        assertThat(outside.findings().get(0).resultType()).isEqualTo(TIME_MISMATCH);
        assertThat(outside.findings().get(0).details()).containsEntry("timeDifferenceSeconds", 301L);

        var boundary = match(List.of(ext(2, "EXT-1", "ACC-001", "ACC-002", "150.00", "USD", T0.minusSeconds(300))),
                List.of(internal("EXT-1")));
        assertThat(boundary.findings().get(0).resultType()).isEqualTo(MATCHED);
    }

    @Test
    void multipleDiscrepanciesAreAllRecordedWithPrimaryByPrecedence() {
        var result = match(List.of(ext(2, "EXT-1", "ACC-001", "ACC-999", "155.00", "EUR", T0.plus(Duration.ofDays(1)))),
                List.of(internal("EXT-1")));

        ReconciliationFinding f = result.findings().get(0);
        assertThat(f.resultType()).isEqualTo(ACCOUNT_MISMATCH);
        assertThat(f.discrepancyCodes())
                .containsExactly(ACCOUNT_MISMATCH, AMOUNT_MISMATCH, CURRENCY_MISMATCH, TIME_MISMATCH);
        assertThat(result.summary().mismatched()).isEqualTo(1); // one row, counted once
    }

    @Test
    void externalRecordWithoutInternalCounterpartIsMissingInternal() {
        var result = match(List.of(ext(2, "EXT-404")), List.of());
        assertThat(result.findings()).singleElement().satisfies(f -> {
            assertThat(f.resultType()).isEqualTo(MISSING_INTERNAL);
            assertThat(f.internalTransferId()).isNull();
        });
        assertThat(result.summary().missingInternal()).isEqualTo(1);
    }

    @Test
    void inScopeInternalTransferWithoutRowIsMissingExternal() {
        InternalTransferView orphan = internal("EXT-INT-ONLY");
        InternalTransferView outOfScope = internal("EXT-OLD", "ACC-001", "ACC-002", "1.00", "USD", T0, false);
        var result = match(List.of(), List.of(orphan, outOfScope));

        assertThat(result.findings()).singleElement().satisfies(f -> {
            assertThat(f.resultType()).isEqualTo(MISSING_EXTERNAL);
            assertThat(f.rowNumber()).isNull();
            assertThat(f.internalTransferId()).isEqualTo(orphan.transferId());
        });
    }

    @Test
    void internalTransferWithoutReferenceInScopeIsMissingExternal() {
        var result = match(List.of(), List.of(internal(null)));
        assertThat(result.findings()).singleElement()
                .extracting(ReconciliationFinding::resultType).isEqualTo(MISSING_EXTERNAL);
    }

    @Test
    void repeatedExternalReferenceIsDetectedAsDuplicateWithoutDoubleCounting() {
        InternalTransferView t = internal("EXT-1");
        var result = match(List.of(ext(2, "EXT-1"), ext(3, "EXT-1"), ext(4, "ext-1")), List.of(t));

        assertThat(result.findings()).extracting(ReconciliationFinding::resultType)
                .containsExactly(MATCHED, DUPLICATE_EXTERNAL, DUPLICATE_EXTERNAL);
        assertThat(result.findings().get(1).details()).containsEntry("firstRowNumber", 2);
        assertThat(result.findings().get(1).internalTransferId()).isEqualTo(t.transferId());
        assertThat(result.summary().matched()).isEqualTo(1);
        assertThat(result.summary().duplicateExternal()).isEqualTo(2);
        assertThat(result.summary().missingExternal()).isZero();
    }

    @Test
    void rowsAreProcessedInFileOrderRegardlessOfInputOrder() {
        var result = match(List.of(ext(5, "EXT-1"), ext(3, "EXT-1")), List.of(internal("EXT-1")));
        assertThat(result.findings().get(0).rowNumber()).isEqualTo(3);
        assertThat(result.findings().get(0).resultType()).isEqualTo(MATCHED);
        assertThat(result.findings().get(1).resultType()).isEqualTo(DUPLICATE_EXTERNAL);
    }

    @Test
    void multipleInternalTransfersForOneReferenceAreDuplicateInternalAndNotArbitrarilyMatched() {
        InternalTransferView first = internal("EXT-1", "ACC-001", "ACC-002", "150.00", "USD", T0, true);
        InternalTransferView second = internal("EXT-1", "ACC-001", "ACC-002", "150.00", "USD", T0.plusSeconds(2), true);
        var result = match(List.of(ext(2, "EXT-1")), List.of(second, first));

        assertThat(result.findings()).singleElement().satisfies(f -> {
            assertThat(f.resultType()).isEqualTo(DUPLICATE_INTERNAL);
            assertThat(f.internalTransferId()).isEqualTo(first.transferId());
            @SuppressWarnings("unchecked")
            List<String> ids = (List<String>) f.details().get("transferIds");
            assertThat(ids).containsExactly(first.transferId().toString(), second.transferId().toString());
        });
        // Neither duplicate is additionally reported as MISSING_EXTERNAL.
        assertThat(result.summary().missingExternal()).isZero();
    }

    @Test
    void sameTransferLoadedTwiceIsNotADuplicateInternal() {
        InternalTransferView inScope = internal("EXT-1");
        InternalTransferView sameByReference = new InternalTransferView(inScope.transferId(), "EXT-1", "ACC-001",
                "ACC-002", new BigDecimal("150.00"), "USD", T0, false);
        var result = match(List.of(ext(2, "EXT-1")), List.of(inScope, sameByReference));
        assertThat(result.findings()).singleElement().extracting(ReconciliationFinding::resultType).isEqualTo(MATCHED);
    }

    @Test
    void amountAndTimeAloneAreNeverUsedAsIdentity() {
        // Same amount and timestamp, different references: no cross-matching.
        var result = match(List.of(ext(2, "EXT-A")), List.of(internal("EXT-B")));
        assertThat(result.findings()).extracting(ReconciliationFinding::resultType)
                .containsExactly(MISSING_INTERNAL, MISSING_EXTERNAL);
    }

    @Test
    void invalidRowsAreReportedAndExcludedFromMatching() {
        var invalid = new InvalidRow(3, "EXT-2", "EXT-2,ACC-1,ACC-2,abc,USD,x", List.of("amount is invalid"));
        var result = ReconciliationMatcher.match(List.of(ext(2, "EXT-1")), List.of(invalid),
                List.of(internal("EXT-1")), TOLERANCE);

        assertThat(result.findings()).extracting(ReconciliationFinding::resultType)
                .containsExactly(MATCHED, INVALID_EXTERNAL_RECORD);
        assertThat(result.findings().get(1).details()).containsKeys("errors", "rawLine");
        assertThat(result.summary().invalidRecords()).isEqualTo(1);
        assertThat(result.summary().totalRows()).isEqualTo(2);
    }

    @Test
    void summaryReconcilesToRowCountsForAMixedFile() {
        List<ExternalRecord> rows = new ArrayList<>();
        List<InternalTransferView> internal = new ArrayList<>();
        rows.add(ext(2, "M-1"));
        internal.add(internal("M-1"));                                                        // matched
        rows.add(ext(3, "AM-1", "ACC-001", "ACC-002", "1.00", "USD", T0));
        internal.add(internal("AM-1"));                                                       // mismatch
        rows.add(ext(4, "MI-1"));                                                             // missing internal
        rows.add(ext(5, "M-1"));                                                              // duplicate external
        rows.add(ext(6, "DI-1"));
        internal.add(internal("DI-1"));
        internal.add(internal("DI-1"));                                                       // duplicate internal
        internal.add(internal("ME-1"));                                                       // missing external
        var invalid = List.of(new InvalidRow(7, null, "x", List.of("bad")));                  // invalid

        var result = ReconciliationMatcher.match(rows, invalid, internal, TOLERANCE);
        ReconciliationSummary s = result.summary();

        assertThat(s.totalRows()).isEqualTo(6);
        assertThat(s.validRows()).isEqualTo(5);
        assertThat(s.matched()).isEqualTo(1);
        assertThat(s.mismatched()).isEqualTo(1);
        assertThat(s.missingInternal()).isEqualTo(1);
        assertThat(s.duplicateExternal()).isEqualTo(1);
        assertThat(s.duplicateInternal()).isEqualTo(1);
        assertThat(s.invalidRecords()).isEqualTo(1);
        assertThat(s.missingExternal()).isEqualTo(1);
        assertThat(s.rowCountsReconcile()).isTrue();
        assertThat(result.findings()).hasSize(7);
    }

    @Test
    void matchingIsDeterministic() {
        List<ExternalRecord> rows = List.of(ext(2, "A"), ext(3, "B"), ext(4, "A"));
        List<InternalTransferView> internal = List.of(internal("A"), internal("C"));
        var first = ReconciliationMatcher.match(rows, List.of(), internal, TOLERANCE);
        var second = ReconciliationMatcher.match(rows, List.of(), internal, TOLERANCE);
        assertThat(second.findings()).isEqualTo(first.findings());
    }
}
