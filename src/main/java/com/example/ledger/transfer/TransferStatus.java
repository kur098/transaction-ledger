package com.example.ledger.transfer;

/**
 * COMPLETED is the only status this implementation writes: a rejected transfer rolls back
 * entirely and leaves no transfer row (spec 4.4). FAILED is kept in the schema contract for
 * a future "record failed attempts" feature and must never carry ledger entries.
 */
public enum TransferStatus {
    COMPLETED,
    FAILED
}
