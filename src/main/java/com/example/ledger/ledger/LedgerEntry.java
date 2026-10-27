package com.example.ledger.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.example.ledger.transfer.Transfer;

/** One immutable posting line. A completed transfer always has exactly one DEBIT and one CREDIT. */
public record LedgerEntry(
        UUID id,
        UUID transferId,
        UUID accountId,
        EntryType entryType,
        BigDecimal amount,
        Instant createdAt) {

    /** Debit on the source: reduces the liability owed to the source customer. */
    public static LedgerEntry debit(Transfer transfer) {
        return new LedgerEntry(UUID.randomUUID(), transfer.id(), transfer.sourceAccountId(),
                EntryType.DEBIT, transfer.amount(), transfer.createdAt());
    }

    /** Credit on the destination: increases the liability owed to the destination customer. */
    public static LedgerEntry credit(Transfer transfer) {
        return new LedgerEntry(UUID.randomUUID(), transfer.id(), transfer.destinationAccountId(),
                EntryType.CREDIT, transfer.amount(), transfer.createdAt());
    }

    /** Signed effect on the account balance. */
    public BigDecimal balanceDelta() {
        return entryType == EntryType.CREDIT ? amount : amount.negate();
    }
}
