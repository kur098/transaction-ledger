package com.example.ledger.idempotency;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Database-backed idempotency (spec section 7). Both methods require the caller's transaction:
 * the claim and the completed response are written in the same transaction as the transfer,
 * so a crash can never leave a committed transfer without a recoverable idempotency result,
 * and a rolled-back transfer releases its key automatically.
 */
@Service
public class IdempotencyService {

    private final IdempotencyRepository repository;

    public IdempotencyService(IdempotencyRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ClaimResult claimOrFind(String clientId, String key, String requestHash) {
        if (repository.tryClaim(clientId, key, requestHash)) {
            return new ClaimResult.Claimed();
        }
        // The INSERT above waited for any concurrent owner to finish, and under READ COMMITTED
        // this new statement sees its committed outcome.
        IdempotencyRecord existing = repository.find(clientId, key)
                .orElseThrow(() -> new IllegalStateException(
                        "Idempotency key conflicted but no record is visible; retry the transaction"));
        if (!existing.matches(requestHash)) {
            return new ClaimResult.Conflict(existing);
        }
        if (existing.isCompleted()) {
            return new ClaimResult.Replay(existing);
        }
        return new ClaimResult.InProgress(existing);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(String clientId, String key, UUID transferId, int responseStatus,
                         String responseBody, Instant completedAt) {
        repository.complete(clientId, key, transferId, responseStatus, responseBody, completedAt);
    }
}
