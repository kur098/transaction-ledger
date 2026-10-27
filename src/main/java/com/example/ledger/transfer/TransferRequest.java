package com.example.ledger.transfer;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Body of POST /api/v1/transfers. The amount should be sent as a JSON string ("150.00") to
 * preserve exact decimal representation; JSON numbers are also accepted and parsed exactly.
 * Field rules are enforced by {@link TransferValidator} so they are unit-testable without Spring.
 *
 * @param reference optional business reference shared with external statements, used by reconciliation
 */
public record TransferRequest(
        UUID sourceAccountId,
        UUID destinationAccountId,
        BigDecimal amount,
        String currency,
        String reference) {
}
