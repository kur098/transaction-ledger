package com.example.ledger.transfer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransferResponse(
        UUID transferId,
        TransferStatus status,
        UUID sourceAccountId,
        UUID destinationAccountId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        String currency,
        String reference,
        Instant createdAt) {

    public static TransferResponse from(Transfer transfer) {
        return new TransferResponse(transfer.id(), transfer.status(), transfer.sourceAccountId(),
                transfer.destinationAccountId(), transfer.amount(), transfer.currency(), transfer.reference(),
                transfer.createdAt());
    }
}
