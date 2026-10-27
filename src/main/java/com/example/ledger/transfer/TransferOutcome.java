package com.example.ledger.transfer;

/**
 * @param httpStatus the status of the ORIGINAL execution (201); a replay returns the same status
 * @param replayed   true when the response was served from the idempotency store
 */
public record TransferOutcome(TransferResponse response, int httpStatus, boolean replayed) {
}
