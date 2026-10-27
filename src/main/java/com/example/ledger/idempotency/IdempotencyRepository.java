package com.example.ledger.idempotency;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class IdempotencyRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public IdempotencyRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Attempts to claim (client_id, idempotency_key) for the current transaction.
     *
     * <p>Arbitration is done by the unique constraint: if another, still-open transaction has
     * inserted the same key, PostgreSQL blocks this INSERT until that transaction ends. If it
     * committed, ON CONFLICT DO NOTHING returns 0 rows; if it rolled back, this insert succeeds.
     * No in-memory map or JVM lock is involved, so this works across instances and restarts.
     *
     * @return true if this transaction now owns the key
     */
    public boolean tryClaim(String clientId, String key, String requestHash) {
        int inserted = jdbc.update("""
                INSERT INTO idempotency_records (id, client_id, idempotency_key, request_hash, status)
                VALUES (:id, :clientId, :key, :hash, 'IN_PROGRESS')
                ON CONFLICT (client_id, idempotency_key) DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("clientId", clientId)
                .addValue("key", key)
                .addValue("hash", requestHash));
        return inserted == 1;
    }

    public Optional<IdempotencyRecord> find(String clientId, String key) {
        return jdbc.query("""
                SELECT id, client_id, idempotency_key, request_hash, status, transfer_id,
                       response_status, response_body, created_at, completed_at
                  FROM idempotency_records
                 WHERE client_id = :clientId AND idempotency_key = :key
                """, new MapSqlParameterSource().addValue("clientId", clientId).addValue("key", key),
                IdempotencyRepository::mapRow).stream().findFirst();
    }

    /** Stores the final response. Runs in the transfer's transaction so both commit atomically. */
    public void complete(String clientId, String key, UUID transferId, int responseStatus,
                         String responseBody, Instant completedAt) {
        int updated = jdbc.update("""
                UPDATE idempotency_records
                   SET status = 'COMPLETED',
                       transfer_id = :transferId,
                       response_status = :responseStatus,
                       response_body = :responseBody,
                       completed_at = :completedAt
                 WHERE client_id = :clientId AND idempotency_key = :key AND status = 'IN_PROGRESS'
                """, new MapSqlParameterSource()
                .addValue("clientId", clientId)
                .addValue("key", key)
                .addValue("transferId", transferId)
                .addValue("responseStatus", responseStatus)
                .addValue("responseBody", responseBody)
                .addValue("completedAt", Timestamp.from(completedAt)));
        if (updated != 1) {
            throw new IllegalStateException("Idempotency record was not in progress for this transaction");
        }
    }

    private static IdempotencyRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp completedAt = rs.getTimestamp("completed_at");
        return new IdempotencyRecord(
                rs.getObject("id", UUID.class),
                rs.getString("client_id"),
                rs.getString("idempotency_key"),
                rs.getString("request_hash"),
                IdempotencyRecord.Status.valueOf(rs.getString("status")),
                rs.getObject("transfer_id", UUID.class),
                rs.getObject("response_status", Integer.class),
                rs.getString("response_body"),
                rs.getTimestamp("created_at").toInstant(),
                completedAt == null ? null : completedAt.toInstant());
    }
}
