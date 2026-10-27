package com.example.ledger.common;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Persists failed transfers and imports for investigation (FR-12). Always runs in its own
 * REQUIRES_NEW transaction, after the business transaction has rolled back, so it can never
 * resurrect partial financial state. Failures to record are logged and swallowed so they never
 * mask the original error.
 */
@Component
public class OperationErrorRecorder {

    private static final Logger log = LoggerFactory.getLogger(OperationErrorRecorder.class);
    private static final int MAX_MESSAGE_LENGTH = 1000;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public OperationErrorRecorder(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void record(String operation, String errorCode, String message,
                       String clientId, String idempotencyKey, UUID relatedId) {
        try {
            String safeMessage = message == null ? "" : message;
            if (safeMessage.length() > MAX_MESSAGE_LENGTH) {
                safeMessage = safeMessage.substring(0, MAX_MESSAGE_LENGTH);
            }
            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("id", UUID.randomUUID())
                    .addValue("operation", operation)
                    .addValue("errorCode", errorCode)
                    .addValue("message", safeMessage)
                    .addValue("requestId", RequestIdFilter.currentRequestId())
                    .addValue("clientId", clientId)
                    .addValue("idempotencyKey", idempotencyKey)
                    .addValue("relatedId", relatedId);
            tx.executeWithoutResult(status -> jdbc.update("""
                    INSERT INTO operation_errors
                        (id, operation, error_code, message, request_id, client_id, idempotency_key, related_id)
                    VALUES
                        (:id, :operation, :errorCode, :message, :requestId, :clientId, :idempotencyKey, :relatedId)
                    """, params));
        } catch (RuntimeException e) {
            log.warn("Could not record operation error {} for {}: {}", errorCode, operation, e.toString());
        }
    }
}
