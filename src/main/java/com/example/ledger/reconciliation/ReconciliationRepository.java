package com.example.ledger.reconciliation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

/**
 * Persistence for runs, staged external rows and findings. Bulk writes use JDBC batches and the
 * internal side is loaded with set-based queries, never one query per CSV row (spec 9.6).
 */
@Repository
public class ReconciliationRepository {

    private static final int IN_CLAUSE_CHUNK = 1000;
    private static final String RUN_COLUMNS = """
            id, file_name, file_hash, file_size_bytes, mode, status, scope_from, scope_to, scope_currency,
            time_tolerance_seconds, total_rows, valid_rows, matched, mismatched, missing_internal,
            missing_external, duplicate_external, duplicate_internal, invalid_records, failure_reason,
            started_at, completed_at
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public ReconciliationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---- runs ----

    /**
     * Serializes "find existing run or create a new one" per file hash across all application
     * instances. The lock is released when the surrounding transaction ends.
     */
    public void lockFileHash(String fileHash) {
        jdbc.queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:hash, 0))) AS l",
                new MapSqlParameterSource("hash", fileHash), Integer.class);
    }

    /** Latest run for this exact file that should be reused instead of importing it again. */
    public Optional<ReconciliationRun> findReusableRun(String fileHash) {
        return jdbc.query("SELECT " + RUN_COLUMNS + """
                  FROM reconciliation_runs
                 WHERE file_hash = :hash AND status IN ('COMPLETED', 'REJECTED', 'PROCESSING')
                 ORDER BY started_at DESC
                 LIMIT 1
                """, new MapSqlParameterSource("hash", fileHash), ReconciliationRepository::mapRun)
                .stream().findFirst();
    }

    public void insertRun(ReconciliationRun run) {
        jdbc.update("""
                INSERT INTO reconciliation_runs
                    (id, file_name, file_hash, file_size_bytes, mode, status, scope_from, scope_to,
                     scope_currency, time_tolerance_seconds, started_at)
                VALUES
                    (:id, :fileName, :fileHash, :fileSize, :mode, :status, :scopeFrom, :scopeTo,
                     :scopeCurrency, :tolerance, :startedAt)
                """, new MapSqlParameterSource()
                .addValue("id", run.id())
                .addValue("fileName", run.fileName())
                .addValue("fileHash", run.fileHash())
                .addValue("fileSize", run.fileSizeBytes())
                .addValue("mode", run.mode().name())
                .addValue("status", run.status().name())
                .addValue("scopeFrom", timestamp(run.scopeFrom()))
                .addValue("scopeTo", timestamp(run.scopeTo()))
                .addValue("scopeCurrency", run.scopeCurrency())
                .addValue("tolerance", run.timeToleranceSeconds())
                .addValue("startedAt", timestamp(run.startedAt())));
    }

    /** Moves a PROCESSING run to a terminal status with its final counts and effective scope. */
    public void finishRun(UUID runId, ReconciliationRun.Status status, ReconciliationSummary summary,
                          Instant scopeFrom, Instant scopeTo, String failureReason, Instant completedAt) {
        int updated = jdbc.update("""
                UPDATE reconciliation_runs
                   SET status = :status,
                       scope_from = :scopeFrom,
                       scope_to = :scopeTo,
                       total_rows = :totalRows,
                       valid_rows = :validRows,
                       matched = :matched,
                       mismatched = :mismatched,
                       missing_internal = :missingInternal,
                       missing_external = :missingExternal,
                       duplicate_external = :duplicateExternal,
                       duplicate_internal = :duplicateInternal,
                       invalid_records = :invalidRecords,
                       failure_reason = :failureReason,
                       completed_at = :completedAt
                 WHERE id = :id AND status = 'PROCESSING'
                """, new MapSqlParameterSource()
                .addValue("id", runId)
                .addValue("status", status.name())
                .addValue("scopeFrom", timestamp(scopeFrom))
                .addValue("scopeTo", timestamp(scopeTo))
                .addValue("totalRows", summary.totalRows())
                .addValue("validRows", summary.validRows())
                .addValue("matched", summary.matched())
                .addValue("mismatched", summary.mismatched())
                .addValue("missingInternal", summary.missingInternal())
                .addValue("missingExternal", summary.missingExternal())
                .addValue("duplicateExternal", summary.duplicateExternal())
                .addValue("duplicateInternal", summary.duplicateInternal())
                .addValue("invalidRecords", summary.invalidRecords())
                .addValue("failureReason", failureReason)
                .addValue("completedAt", timestamp(completedAt)));
        if (updated != 1) {
            throw new IllegalStateException("Reconciliation run " + runId + " is not PROCESSING");
        }
    }

    public void markFailed(UUID runId, String reason, Instant completedAt) {
        jdbc.update("""
                UPDATE reconciliation_runs
                   SET status = 'FAILED', failure_reason = :reason, completed_at = :completedAt
                 WHERE id = :id AND status = 'PROCESSING'
                """, new MapSqlParameterSource()
                .addValue("id", runId)
                .addValue("reason", reason)
                .addValue("completedAt", timestamp(completedAt)));
    }

    public Optional<ReconciliationRun> findRun(UUID runId) {
        return jdbc.query("SELECT " + RUN_COLUMNS + " FROM reconciliation_runs WHERE id = :id",
                new MapSqlParameterSource("id", runId), ReconciliationRepository::mapRun).stream().findFirst();
    }

    // ---- staged external rows ----

    public void insertExternalTransactions(List<ExternalTransaction> rows) {
        if (rows.isEmpty()) {
            return;
        }
        SqlParameterSource[] batch = rows.stream()
                .map(r -> new MapSqlParameterSource()
                        .addValue("id", r.id())
                        .addValue("runId", r.runId())
                        .addValue("rowNumber", r.record().rowNumber())
                        .addValue("reference", r.record().externalReference())
                        .addValue("source", r.record().sourceAccount())
                        .addValue("destination", r.record().destinationAccount())
                        .addValue("amount", r.record().amount())
                        .addValue("currency", r.record().currency())
                        .addValue("time", timestamp(r.record().transactionTime())))
                .toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate("""
                INSERT INTO external_transactions
                    (id, run_id, row_number, external_reference, source_account, destination_account,
                     amount, currency, transaction_time)
                VALUES
                    (:id, :runId, :rowNumber, :reference, :source, :destination, :amount, :currency, :time)
                """, batch);
    }

    public long countExternalTransactions(UUID runId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM external_transactions WHERE run_id = :runId",
                new MapSqlParameterSource("runId", runId), Long.class);
        return count == null ? 0 : count;
    }

    // ---- findings ----

    public void insertItems(List<ReconciliationItem> items) {
        if (items.isEmpty()) {
            return;
        }
        SqlParameterSource[] batch = items.stream()
                .map(i -> new MapSqlParameterSource()
                        .addValue("id", i.id())
                        .addValue("runId", i.runId())
                        .addValue("transferId", i.internalTransferId())
                        .addValue("externalId", i.externalTransactionId())
                        .addValue("rowNumber", i.rowNumber())
                        .addValue("reference", truncate(i.externalReference(), 64))
                        .addValue("resultType", i.resultType().name())
                        .addValue("codes", i.discrepancyCodes().isEmpty() ? null
                                : i.discrepancyCodes().stream().map(Enum::name).collect(Collectors.joining(",")))
                        .addValue("details", i.detailsJson()))
                .toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate("""
                INSERT INTO reconciliation_items
                    (id, run_id, internal_transfer_id, external_transaction_id, row_number, external_reference,
                     result_type, discrepancy_codes, details)
                VALUES
                    (:id, :runId, :transferId, :externalId, :rowNumber, :reference,
                     :resultType, :codes, CAST(:details AS jsonb))
                """, batch);
    }

    public List<ReconciliationItem> findItems(UUID runId, ReconciliationResultType type, int limit, long offset) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("runId", runId)
                .addValue("limit", limit)
                .addValue("offset", offset);
        String filter = "";
        if (type != null) {
            filter = " AND result_type = :type";
            params.addValue("type", type.name());
        }
        return jdbc.query("""
                SELECT id, run_id, internal_transfer_id, external_transaction_id, row_number, external_reference,
                       result_type, discrepancy_codes, details::text AS details, created_at
                  FROM reconciliation_items
                 WHERE run_id = :runId""" + filter + """

                 ORDER BY row_number NULLS LAST, id
                 LIMIT :limit OFFSET :offset
                """, params, ReconciliationRepository::mapItem);
    }

    public long countItems(UUID runId, ReconciliationResultType type) {
        MapSqlParameterSource params = new MapSqlParameterSource("runId", runId);
        String filter = "";
        if (type != null) {
            filter = " AND result_type = :type";
            params.addValue("type", type.name());
        }
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_items WHERE run_id = :runId" + filter, params, Long.class);
        return count == null ? 0 : count;
    }

    // ---- internal side ----

    private static final String INTERNAL_SELECT = """
            SELECT t.id, t.reference, sa.account_number AS source_number, da.account_number AS destination_number,
                   t.amount, t.currency, t.created_at
              FROM transfers t
              JOIN accounts sa ON sa.id = t.source_account_id
              JOIN accounts da ON da.id = t.destination_account_id
             WHERE t.status = 'COMPLETED'
            """;

    /** Completed transfers inside the run's scope: candidates for MISSING_EXTERNAL. */
    public List<InternalTransferView> findInternalInScope(Instant from, Instant to, String currency) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("from", timestamp(from))
                .addValue("to", timestamp(to));
        String sql = INTERNAL_SELECT + " AND t.created_at >= :from AND t.created_at <= :to";
        if (currency != null) {
            sql += " AND t.currency = :currency";
            params.addValue("currency", currency);
        }
        return jdbc.query(sql, params, (rs, i) -> mapInternal(rs, true));
    }

    /** Completed transfers whose (normalized) reference appears in the file, regardless of scope. */
    public List<InternalTransferView> findInternalByReferenceKeys(Collection<String> referenceKeys) {
        List<String> keys = new ArrayList<>(referenceKeys);
        List<InternalTransferView> result = new ArrayList<>();
        for (int start = 0; start < keys.size(); start += IN_CLAUSE_CHUNK) {
            List<String> chunk = keys.subList(start, Math.min(keys.size(), start + IN_CLAUSE_CHUNK));
            result.addAll(jdbc.query(INTERNAL_SELECT + " AND UPPER(t.reference) IN (:keys)",
                    new MapSqlParameterSource("keys", chunk), (rs, i) -> mapInternal(rs, false)));
        }
        return result;
    }

    // ---- mapping ----

    private static InternalTransferView mapInternal(ResultSet rs, boolean inScope) throws SQLException {
        return new InternalTransferView(
                rs.getObject("id", UUID.class),
                rs.getString("reference"),
                rs.getString("source_number"),
                rs.getString("destination_number"),
                rs.getBigDecimal("amount"),
                rs.getString("currency"),
                rs.getTimestamp("created_at").toInstant(),
                inScope);
    }

    private static ReconciliationRun mapRun(ResultSet rs, int rowNum) throws SQLException {
        ReconciliationSummary summary = new ReconciliationSummary(
                rs.getInt("total_rows"),
                rs.getInt("valid_rows"),
                rs.getInt("matched"),
                rs.getInt("mismatched"),
                rs.getInt("missing_internal"),
                rs.getInt("missing_external"),
                rs.getInt("duplicate_external"),
                rs.getInt("duplicate_internal"),
                rs.getInt("invalid_records"));
        return new ReconciliationRun(
                rs.getObject("id", UUID.class),
                rs.getString("file_name"),
                rs.getString("file_hash"),
                rs.getLong("file_size_bytes"),
                ReconciliationRun.Mode.valueOf(rs.getString("mode")),
                ReconciliationRun.Status.valueOf(rs.getString("status")),
                instant(rs.getTimestamp("scope_from")),
                instant(rs.getTimestamp("scope_to")),
                rs.getString("scope_currency"),
                rs.getInt("time_tolerance_seconds"),
                summary,
                rs.getString("failure_reason"),
                instant(rs.getTimestamp("started_at")),
                instant(rs.getTimestamp("completed_at")));
    }

    private static ReconciliationItem mapItem(ResultSet rs, int rowNum) throws SQLException {
        String codes = rs.getString("discrepancy_codes");
        List<ReconciliationResultType> codeList = codes == null || codes.isBlank()
                ? List.of()
                : Arrays.stream(codes.split(",")).map(ReconciliationResultType::valueOf).toList();
        return new ReconciliationItem(
                rs.getObject("id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getObject("internal_transfer_id", UUID.class),
                rs.getObject("external_transaction_id", UUID.class),
                rs.getObject("row_number", Integer.class),
                rs.getString("external_reference"),
                ReconciliationResultType.valueOf(rs.getString("result_type")),
                codeList,
                rs.getString("details"),
                instant(rs.getTimestamp("created_at")));
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
