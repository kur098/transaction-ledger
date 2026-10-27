package com.example.ledger.idempotency;

/** Outcome of trying to claim an idempotency key inside the current transaction. */
public sealed interface ClaimResult {

    /** This transaction owns the key and must execute the transfer. */
    record Claimed() implements ClaimResult {
    }

    /** The same request already committed; replay its stored response without executing again. */
    record Replay(IdempotencyRecord record) implements ClaimResult {
    }

    /** The key was already used for a different request. */
    record Conflict(IdempotencyRecord record) implements ClaimResult {
    }

    /** Defensive: a record exists but is not completed (should not be observable; see V3 migration). */
    record InProgress(IdempotencyRecord record) implements ClaimResult {
    }
}
