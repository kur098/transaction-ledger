package com.example.ledger.reconciliation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.example.ledger.common.NotFoundException;
import com.example.ledger.common.OperationErrorRecorder;
import com.example.ledger.common.PageRequestParams;
import com.example.ledger.common.PageResponse;
import com.example.ledger.common.ValidationException;
import com.example.ledger.reconciliation.dto.ReconciliationItemResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reconciliation workflow (spec 9.2):
 * <ol>
 *   <li>hash the upload; reuse the existing run for an identical file unless {@code rerun}</li>
 *   <li>create the run (committed as PROCESSING so it is visible and auditable)</li>
 *   <li>parse and validate rows</li>
 *   <li>in ONE transaction: stage valid rows, load the internal side, match, persist findings and counts</li>
 *   <li>on an unexpected error that transaction rolls back (no half-imported rows) and the run is
 *       marked FAILED in a separate transaction; uploading the file again is safe</li>
 * </ol>
 * The external file is evidence only: nothing here writes to accounts, transfers or ledger_entries.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");
    private static final int MAX_FILE_NAME = 255;
    private static final int MAX_TOLERANCE_SECONDS = 31 * 24 * 3600;

    private final ReconciliationRepository repository;
    private final CsvImportService importer;
    private final OperationErrorRecorder errorRecorder;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate writeTx;
    private final TransactionTemplate snapshotTx;

    public ReconciliationService(ReconciliationRepository repository,
                                 CsvImportService importer,
                                 OperationErrorRecorder errorRecorder,
                                 ObjectMapper objectMapper,
                                 Clock clock,
                                 PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.importer = importer;
        this.errorRecorder = errorRecorder;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.writeTx = new TransactionTemplate(transactionManager);
        // Matching sees one consistent snapshot of the ledger, so a run is reproducible.
        this.snapshotTx = new TransactionTemplate(transactionManager);
        this.snapshotTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public record Outcome(ReconciliationRun run, boolean reused) {
    }

    public Outcome reconcile(String originalFileName, byte[] content, ReconciliationOptions options) {
        validateOptions(options);
        String fileName = sanitizeFileName(originalFileName);
        String fileHash = importer.hash(content);

        CreateResult created = writeTx.execute(status -> {
            repository.lockFileHash(fileHash);
            if (!options.rerun()) {
                var existing = repository.findReusableRun(fileHash);
                if (existing.isPresent()) {
                    return new CreateResult(existing.get(), true);
                }
            }
            ReconciliationRun run = new ReconciliationRun(UUID.randomUUID(), fileName, fileHash, content.length,
                    options.mode(), ReconciliationRun.Status.PROCESSING, options.scopeFrom(), options.scopeTo(),
                    options.scopeCurrency(), options.timeToleranceSeconds(), null, null, now(), null);
            repository.insertRun(run);
            return new CreateResult(run, false);
        });
        if (created.reused()) {
            log.info("Identical file (sha256={}) already reconciled in run {}; reusing it", fileHash, created.run().id());
            return new Outcome(created.run(), true);
        }

        UUID runId = created.run().id();
        try {
            CsvParseResult parsed = importer.parse(content);
            snapshotTx.executeWithoutResult(status -> process(runId, parsed, options));
        } catch (RuntimeException e) {
            log.error("Reconciliation run {} failed", runId, e);
            writeTx.executeWithoutResult(status -> repository.markFailed(runId,
                    "Unexpected error during reconciliation; the file can be uploaded again.", now()));
            errorRecorder.record("RECONCILIATION", "RECONCILIATION_FAILED", e.getClass().getSimpleName(),
                    null, null, runId);
            throw e;
        }
        return new Outcome(getRun(runId), false);
    }

    public ReconciliationRun getRun(UUID runId) {
        return repository.findRun(runId).orElseThrow(() -> NotFoundException.reconciliationRun(runId));
    }

    public PageResponse<ReconciliationItemResponse> getItems(UUID runId, ReconciliationResultType type,
                                                             PageRequestParams page) {
        getRun(runId);
        List<ReconciliationItemResponse> content = repository.findItems(runId, type, page.size(), page.offset())
                .stream()
                .map(item -> ReconciliationItemResponse.from(item, readJson(item.detailsJson())))
                .toList();
        return PageResponse.of(content, page.page(), page.size(), repository.countItems(runId, type));
    }

    // ------------------------------------------------------------------------------------------

    private record CreateResult(ReconciliationRun run, boolean reused) {
    }

    private void process(UUID runId, CsvParseResult parsed, ReconciliationOptions options) {
        if (parsed.hasFileError()) {
            repository.finishRun(runId, ReconciliationRun.Status.REJECTED, ReconciliationSummary.from(0, 0, List.of()),
                    options.scopeFrom(), options.scopeTo(), parsed.fileError(), now());
            errorRecorder.record("RECONCILIATION", "INVALID_FILE", parsed.fileError(), null, null, runId);
            return;
        }

        Duration tolerance = Duration.ofSeconds(options.timeToleranceSeconds());

        if (options.mode() == ReconciliationRun.Mode.STRICT && !parsed.invalidRows().isEmpty()) {
            // Strict mode: report every invalid row, import nothing, match nothing.
            ReconciliationMatcher.Result invalidOnly =
                    ReconciliationMatcher.match(List.of(), parsed.invalidRows(), List.of(), tolerance);
            repository.insertItems(toItems(runId, invalidOnly.findings(), Map.of()));
            ReconciliationSummary summary = ReconciliationSummary.from(parsed.totalRows(),
                    parsed.validRecords().size(), invalidOnly.findings());
            String reason = "Strict mode: " + parsed.invalidRows().size() + " invalid row(s); file rejected.";
            repository.finishRun(runId, ReconciliationRun.Status.REJECTED, summary,
                    options.scopeFrom(), options.scopeTo(), reason, now());
            errorRecorder.record("RECONCILIATION", "INVALID_FILE", reason, null, null, runId);
            return;
        }

        // 1. Stage valid rows.
        List<ExternalTransaction> staged = parsed.validRecords().stream()
                .map(r -> new ExternalTransaction(UUID.randomUUID(), runId, r))
                .toList();
        repository.insertExternalTransactions(staged);
        Map<Integer, UUID> externalIdByRow = new HashMap<>();
        staged.forEach(s -> externalIdByRow.put(s.record().rowNumber(), s.id()));

        // 2. Effective scope.
        Instant scopeFrom = options.scopeFrom();
        Instant scopeTo = options.scopeTo();
        if (scopeFrom == null && !parsed.validRecords().isEmpty()) {
            scopeFrom = parsed.validRecords().stream().map(ExternalRecord::transactionTime)
                    .min(Comparator.naturalOrder()).orElseThrow().minus(tolerance);
            scopeTo = parsed.validRecords().stream().map(ExternalRecord::transactionTime)
                    .max(Comparator.naturalOrder()).orElseThrow().plus(tolerance);
        }

        // 3. Internal side: in-scope transfers plus any transfer the file references.
        List<InternalTransferView> internal = new ArrayList<>();
        if (scopeFrom != null) {
            internal.addAll(repository.findInternalInScope(scopeFrom, scopeTo, options.scopeCurrency()));
        }
        Set<String> referenceKeys = new LinkedHashSet<>();
        parsed.validRecords().forEach(r -> referenceKeys.add(r.referenceKey()));
        if (!referenceKeys.isEmpty()) {
            internal.addAll(repository.findInternalByReferenceKeys(referenceKeys));
        }

        // 4. Match and persist.
        ReconciliationMatcher.Result result =
                ReconciliationMatcher.match(parsed.validRecords(), parsed.invalidRows(), internal, tolerance);
        repository.insertItems(toItems(runId, result.findings(), externalIdByRow));
        if (!result.summary().rowCountsReconcile()) {
            throw new IllegalStateException("Reconciliation summary does not add up for run " + runId);
        }
        repository.finishRun(runId, ReconciliationRun.Status.COMPLETED, result.summary(),
                scopeFrom, scopeTo, null, now());
        log.info("Reconciliation run {} completed: {}", runId, result.summary());
    }

    private List<ReconciliationItem> toItems(UUID runId, List<ReconciliationFinding> findings,
                                             Map<Integer, UUID> externalIdByRow) {
        Instant now = now();
        return findings.stream()
                .map(f -> new ReconciliationItem(UUID.randomUUID(), runId, f.internalTransferId(),
                        f.rowNumber() == null ? null : externalIdByRow.get(f.rowNumber()),
                        f.rowNumber(), f.externalReference(), f.resultType(), f.discrepancyCodes(),
                        writeJson(f.details()), now))
                .toList();
    }

    private void validateOptions(ReconciliationOptions options) {
        if ((options.scopeFrom() == null) != (options.scopeTo() == null)) {
            throw new ValidationException("INVALID_SCOPE", "Provide both 'from' and 'to', or neither.");
        }
        if (options.scopeFrom() != null && !options.scopeFrom().isBefore(options.scopeTo())) {
            throw new ValidationException("INVALID_SCOPE", "'from' must be before 'to'.");
        }
        if (options.scopeCurrency() != null && !CURRENCY.matcher(options.scopeCurrency()).matches()) {
            throw new ValidationException("INVALID_CURRENCY", "currency must be a 3-letter ISO 4217 code.");
        }
        if (options.timeToleranceSeconds() < 0 || options.timeToleranceSeconds() > MAX_TOLERANCE_SECONDS) {
            throw new ValidationException("INVALID_TOLERANCE",
                    "toleranceSeconds must be between 0 and " + MAX_TOLERANCE_SECONDS + ".");
        }
    }

    private static String sanitizeFileName(String name) {
        String safe = name == null || name.isBlank() ? "upload.csv" : name.replaceAll("[\\p{Cntrl}/\\\\]", "_").trim();
        return safe.length() <= MAX_FILE_NAME ? safe : safe.substring(0, MAX_FILE_NAME);
    }

    private String writeJson(Map<String, Object> details) {
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize finding details", e);
        }
    }

    private com.fasterxml.jackson.databind.JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(json == null ? "{}" : json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored finding details are not valid JSON", e);
        }
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
