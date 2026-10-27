package com.example.ledger.ledger.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.example.ledger.ledger.EntryType;
import com.fasterxml.jackson.annotation.JsonFormat;

public record LedgerEntryResponse(
        UUID entryId,
        UUID transferId,
        EntryType entryType,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        String currency,
        UUID counterpartyAccountId,
        String reference,
        Instant createdAt) {
}
