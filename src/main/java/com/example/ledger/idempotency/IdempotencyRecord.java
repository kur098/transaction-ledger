package com.example.ledger.idempotency;

import java.time.Instant;
import java.util.UUID;

public record IdempotencyRecord(
        UUID id,
        String clientId,
        String idempotencyKey,
        String requestHash,
        Status status,
        UUID transferId,
        Integer responseStatus,
        String responseBody,
        Instant createdAt,
        Instant completedAt) {

    public enum Status {
        IN_PROGRESS,
        COMPLETED
    }

    public boolean isCompleted() {
        return status == Status.COMPLETED;
    }

    public boolean matches(String otherRequestHash) {
        return requestHash.equals(otherRequestHash);
    }
}
