package com.example.ledger.ledger.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * Result of a full ledger audit (spec 11.4). {@code consistent} is true only when total debits
 * equal total credits, every completed transfer is balanced, and every account balance equals
 * its opening balance plus its ledger movements.
 */
public record LedgerIntegrityReport(
        boolean consistent,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalDebits,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalCredits,
        List<UnbalancedTransfer> unbalancedTransfers,
        List<AccountDrift> accountsOutOfBalance) {

    public record DebitCreditTotals(BigDecimal debits, BigDecimal credits) {
    }

    public record UnbalancedTransfer(
            UUID transferId,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal debits,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal credits) {
    }

    public record AccountDrift(
            UUID accountId,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal storedBalance,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal ledgerBalance) {
    }
}
