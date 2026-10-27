package com.example.ledger.reconciliation;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Pure matching engine (spec 9.3 / 9.4). Deterministic: the same inputs always produce the same
 * findings in the same order, so a run is reproducible from its stored inputs.
 *
 * <p>Matching rules:
 * <ol>
 *   <li>Rows are matched to internal transfers by reference only (trimmed, case-insensitive).
 *       Amount and timestamp are never used as identity, because legitimate transfers can share them.</li>
 *   <li>Rows are processed in file order. The first valid row for a reference is the primary row;
 *       later rows with the same reference are DUPLICATE_EXTERNAL.</li>
 *   <li>Primary row with no internal transfer: MISSING_INTERNAL. With more than one internal
 *       transfer for the reference: DUPLICATE_INTERNAL (all of them are listed; none is chosen
 *       arbitrarily, so a person reviews it).</li>
 *   <li>Primary row with exactly one internal transfer: accounts, amount, currency and time
 *       (within the tolerance) are compared. No differences: MATCHED. Otherwise every difference is
 *       recorded, and the primary classification follows the precedence
 *       ACCOUNT &gt; AMOUNT &gt; CURRENCY &gt; TIME.</li>
 *   <li>In-scope internal transfers that no primary row accounted for: MISSING_EXTERNAL.</li>
 *   <li>Invalid rows: INVALID_EXTERNAL_RECORD; they take no part in matching.</li>
 * </ol>
 */
public final class ReconciliationMatcher {

    private ReconciliationMatcher() {
    }

    public record Result(List<ReconciliationFinding> findings, ReconciliationSummary summary) {
    }

    public static Result match(List<ExternalRecord> validRecords,
                               List<InvalidRow> invalidRows,
                               Collection<InternalTransferView> internalTransfers,
                               Duration timeTolerance) {
        List<ReconciliationFinding> findings = new ArrayList<>();

        for (InvalidRow row : invalidRows) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("errors", row.errors());
            details.put("rawLine", row.rawLine());
            findings.add(finding(ReconciliationResultType.INVALID_EXTERNAL_RECORD, List.of(), row.rowNumber(),
                    row.externalReference(), null, details));
        }

        // Deduplicate internal transfers by id (a transfer may be loaded both by scope and by reference).
        Map<UUID, InternalTransferView> byId = new LinkedHashMap<>();
        for (InternalTransferView t : internalTransfers) {
            byId.merge(t.transferId(), t, (a, b) -> a.inScope() ? a : b);
        }
        Comparator<InternalTransferView> internalOrder = Comparator
                .comparing(InternalTransferView::createdAt)
                .thenComparing(InternalTransferView::transferId);
        Map<String, List<InternalTransferView>> internalByReference = new HashMap<>();
        for (InternalTransferView t : byId.values()) {
            if (t.referenceKey() != null && !t.referenceKey().isEmpty()) {
                internalByReference.computeIfAbsent(t.referenceKey(), k -> new ArrayList<>()).add(t);
            }
        }
        internalByReference.values().forEach(list -> list.sort(internalOrder));

        Set<UUID> accounted = new HashSet<>();
        Map<String, ExternalRecord> primaryByReference = new HashMap<>();
        List<ExternalRecord> ordered = validRecords.stream()
                .sorted(Comparator.comparingInt(ExternalRecord::rowNumber))
                .toList();

        for (ExternalRecord row : ordered) {
            String key = row.referenceKey();
            List<InternalTransferView> candidates = internalByReference.getOrDefault(key, List.of());
            ExternalRecord primary = primaryByReference.putIfAbsent(key, row);

            if (primary != null) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("firstRowNumber", primary.rowNumber());
                details.put("external", describe(row));
                UUID related = candidates.size() == 1 ? candidates.get(0).transferId() : null;
                findings.add(finding(ReconciliationResultType.DUPLICATE_EXTERNAL, List.of(), row.rowNumber(),
                        row.externalReference(), related, details));
                continue;
            }

            if (candidates.isEmpty()) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("external", describe(row));
                findings.add(finding(ReconciliationResultType.MISSING_INTERNAL, List.of(), row.rowNumber(),
                        row.externalReference(), null, details));
                continue;
            }

            if (candidates.size() > 1) {
                candidates.forEach(t -> accounted.add(t.transferId()));
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("transferIds", candidates.stream().map(t -> t.transferId().toString()).toList());
                details.put("external", describe(row));
                findings.add(finding(ReconciliationResultType.DUPLICATE_INTERNAL, List.of(), row.rowNumber(),
                        row.externalReference(), candidates.get(0).transferId(), details));
                continue;
            }

            InternalTransferView internal = candidates.get(0);
            accounted.add(internal.transferId());
            List<ReconciliationResultType> codes = compare(row, internal, timeTolerance);
            Map<String, Object> details = new LinkedHashMap<>();
            if (!codes.isEmpty()) {
                details.put("external", describe(row));
                details.put("internal", describe(internal));
                if (codes.contains(ReconciliationResultType.TIME_MISMATCH)) {
                    details.put("timeDifferenceSeconds",
                            Math.abs(Duration.between(internal.createdAt(), row.transactionTime()).toSeconds()));
                    details.put("toleranceSeconds", timeTolerance.toSeconds());
                }
            }
            ReconciliationResultType primaryType = codes.isEmpty() ? ReconciliationResultType.MATCHED : codes.get(0);
            findings.add(finding(primaryType, codes, row.rowNumber(), row.externalReference(),
                    internal.transferId(), details));
        }

        byId.values().stream()
                .filter(InternalTransferView::inScope)
                .filter(t -> !accounted.contains(t.transferId()))
                .sorted(internalOrder)
                .forEach(t -> {
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("internal", describe(t));
                    findings.add(finding(ReconciliationResultType.MISSING_EXTERNAL, List.of(), null,
                            t.reference(), t.transferId(), details));
                });

        findings.sort(Comparator.comparing(ReconciliationFinding::rowNumber,
                Comparator.nullsLast(Comparator.naturalOrder())));

        int totalRows = validRecords.size() + invalidRows.size();
        return new Result(findings, ReconciliationSummary.from(totalRows, validRecords.size(), findings));
    }

    /** All discrepancies between a row and its counterpart, in precedence order. */
    static List<ReconciliationResultType> compare(ExternalRecord row, InternalTransferView internal, Duration tolerance) {
        List<ReconciliationResultType> codes = new ArrayList<>();
        if (!row.sourceAccount().equals(internal.sourceAccountNumber())
                || !row.destinationAccount().equals(internal.destinationAccountNumber())) {
            codes.add(ReconciliationResultType.ACCOUNT_MISMATCH);
        }
        if (row.amount().compareTo(internal.amount()) != 0) {
            codes.add(ReconciliationResultType.AMOUNT_MISMATCH);
        }
        if (!row.currency().equals(internal.currency())) {
            codes.add(ReconciliationResultType.CURRENCY_MISMATCH);
        }
        Duration difference = Duration.between(internal.createdAt(), row.transactionTime()).abs();
        if (difference.compareTo(tolerance) > 0) {
            codes.add(ReconciliationResultType.TIME_MISMATCH);
        }
        return codes;
    }

    private static Map<String, Object> describe(ExternalRecord row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sourceAccount", row.sourceAccount());
        m.put("destinationAccount", row.destinationAccount());
        m.put("amount", row.amount().toPlainString());
        m.put("currency", row.currency());
        m.put("transactionTime", row.transactionTime().toString());
        return m;
    }

    private static Map<String, Object> describe(InternalTransferView t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("transferId", t.transferId().toString());
        m.put("sourceAccount", t.sourceAccountNumber());
        m.put("destinationAccount", t.destinationAccountNumber());
        m.put("amount", t.amount().toPlainString());
        m.put("currency", t.currency());
        m.put("createdAt", t.createdAt().toString());
        return m;
    }

    private static ReconciliationFinding finding(ReconciliationResultType type,
                                                 List<ReconciliationResultType> codes,
                                                 Integer rowNumber,
                                                 String reference,
                                                 UUID transferId,
                                                 Map<String, Object> details) {
        details.values().removeIf(v -> v == null);
        return new ReconciliationFinding(type, codes, rowNumber, reference, transferId, details);
    }
}
