package com.example.ledger.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.example.ledger.account.AccountStatus;
import com.example.ledger.common.NotFoundException;
import com.example.ledger.ledger.EntryType;
import com.example.ledger.ledger.LedgerEntry;
import com.example.ledger.ledger.LedgerService;
import com.example.ledger.support.AbstractIntegrationTest;
import com.example.ledger.transfer.exception.AccountNotActiveException;
import com.example.ledger.transfer.exception.CurrencyMismatchException;
import com.example.ledger.transfer.exception.IdempotencyConflictException;
import com.example.ledger.transfer.exception.InsufficientFundsException;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/** Transfer behaviour against real PostgreSQL: spec 6, 7 and acceptance criteria in 14. */
class TransferServiceIT extends AbstractIntegrationTest {

    @Autowired
    TransferService transferService;

    @Autowired
    LedgerService ledgerService;

    private TransferOutcome transfer(UUID src, UUID dst, String amount, String key) {
        return transferService.transfer(new TransferRequest(src, dst, money(amount), "USD", null), key, null);
    }

    @Test
    void successfulTransferUpdatesBothBalancesAndCreatesBalancedJournalInOneCommit() {
        UUID src = createAccount("ACC-001", "500.00");
        UUID dst = createAccount("ACC-002", "25.00");

        TransferOutcome outcome = transfer(src, dst, "150.00", "key-success");

        assertThat(outcome.replayed()).isFalse();
        TransferResponse response = outcome.response();
        assertThat(response.status()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(response.amount()).isEqualByComparingTo("150.00");

        assertThat(balanceOf(src)).isEqualByComparingTo("350.00");
        assertThat(balanceOf(dst)).isEqualByComparingTo("175.00");

        List<LedgerEntry> entries = ledgerService.entriesForTransfer(response.transferId());
        assertThat(entries).hasSize(2);
        LedgerEntry debit = entries.stream().filter(e -> e.entryType() == EntryType.DEBIT).findFirst().orElseThrow();
        LedgerEntry credit = entries.stream().filter(e -> e.entryType() == EntryType.CREDIT).findFirst().orElseThrow();
        assertThat(debit.accountId()).isEqualTo(src);
        assertThat(credit.accountId()).isEqualTo(dst);
        assertThat(debit.amount()).isEqualByComparingTo(credit.amount()).isEqualByComparingTo("150.00");

        assertThat(count("transfers")).isEqualTo(1);
        assertThat(ledgerService.verifyIntegrity().consistent()).isTrue();
        assertThat(transferService.get(response.transferId())).isEqualTo(response);
    }

    @Test
    void transferOfEntireBalanceLeavesZero() {
        UUID src = createAccount("ACC-001", "80.00");
        UUID dst = createAccount("ACC-002", "0.00");
        transfer(src, dst, "80.00", "key-all");
        assertThat(balanceOf(src)).isEqualByComparingTo("0.00");
    }

    @Test
    void insufficientFundsChangesNothingAndLeavesNoFinancialEntries() {
        UUID src = createAccount("ACC-001", "100.00");
        UUID dst = createAccount("ACC-002", "0.00");

        assertThatThrownBy(() -> transfer(src, dst, "100.01", "key-nsf"))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(balanceOf(src)).isEqualByComparingTo("100.00");
        assertThat(balanceOf(dst)).isEqualByComparingTo("0.00");
        assertThat(count("transfers")).isZero();
        assertThat(count("ledger_entries")).isZero();
        assertThat(count("idempotency_records")).as("claim is rolled back with the transfer").isZero();
        assertThat(jdbc.getJdbcTemplate().queryForObject(
                "SELECT error_code FROM operation_errors", String.class))
                .as("rejection is recorded for investigation").isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    void retryAfterBusinessRejectionCanSucceedOnceFundsArrive() {
        UUID src = createAccount("ACC-001", "10.00");
        UUID dst = createAccount("ACC-002", "0.00");
        UUID funder = createAccount("ACC-003", "100.00");

        assertThatThrownBy(() -> transfer(src, dst, "50.00", "key-later")).isInstanceOf(InsufficientFundsException.class);
        transfer(funder, src, "40.00", "key-fund");
        TransferOutcome retried = transfer(src, dst, "50.00", "key-later");

        assertThat(retried.replayed()).isFalse();
        assertThat(balanceOf(dst)).isEqualByComparingTo("50.00");
    }

    @Test
    void unknownAccountIsRejected() {
        UUID src = createAccount("ACC-001", "100.00");
        assertThatThrownBy(() -> transfer(src, UUID.randomUUID(), "1.00", "key-unknown"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("does not exist");
        assertThat(count("transfers")).isZero();
        assertThat(balanceOf(src)).isEqualByComparingTo("100.00");
    }

    @Test
    void inactiveAccountsAreRejected() {
        UUID src = createAccount("ACC-001", "100.00");
        UUID dst = createAccount("ACC-002", "0.00");
        accountService.changeStatus(dst, AccountStatus.FROZEN);

        assertThatThrownBy(() -> transfer(src, dst, "1.00", "key-frozen"))
                .isInstanceOf(AccountNotActiveException.class);

        accountService.changeStatus(dst, AccountStatus.ACTIVE);
        accountService.changeStatus(src, AccountStatus.FROZEN);
        assertThatThrownBy(() -> transfer(src, dst, "1.00", "key-frozen-2"))
                .isInstanceOf(AccountNotActiveException.class);
        assertThat(count("transfers")).isZero();
    }

    @Test
    void currencyMismatchIsRejected() {
        UUID usd = createAccount("ACC-USD", "100.00", "USD");
        UUID eur = createAccount("ACC-EUR", "100.00", "EUR");

        assertThatThrownBy(() -> transfer(usd, eur, "1.00", "key-ccy-1"))
                .isInstanceOf(CurrencyMismatchException.class);
        assertThatThrownBy(() -> transferService.transfer(
                new TransferRequest(usd, createAccount("ACC-USD-2", "0", "USD"), money("1.00"), "EUR", null),
                "key-ccy-2", null))
                .isInstanceOf(CurrencyMismatchException.class);
        assertThat(count("transfers")).isZero();
    }

    @Test
    void idempotentRetryReturnsOriginalTransferWithoutPostingAgain() {
        UUID src = createAccount("ACC-001", "500.00");
        UUID dst = createAccount("ACC-002", "0.00");

        TransferOutcome first = transfer(src, dst, "150.00", "key-retry");
        TransferOutcome second = transfer(src, dst, "150", "key-retry"); // same request, different notation

        assertThat(second.replayed()).isTrue();
        assertThat(second.httpStatus()).isEqualTo(first.httpStatus());
        assertThat(second.response()).isEqualTo(first.response());
        assertThat(count("transfers")).isEqualTo(1);
        assertThat(count("ledger_entries")).isEqualTo(2);
        assertThat(balanceOf(src)).isEqualByComparingTo("350.00");
    }

    @Test
    void reusingKeyWithDifferentParametersIsRejected() {
        UUID src = createAccount("ACC-001", "500.00");
        UUID dst = createAccount("ACC-002", "0.00");
        transfer(src, dst, "150.00", "key-conflict");

        assertThatThrownBy(() -> transfer(src, dst, "151.00", "key-conflict"))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(count("transfers")).isEqualTo(1);
        assertThat(balanceOf(src)).isEqualByComparingTo("350.00");
    }

    @Test
    void idempotencyKeysAreScopedPerClient() {
        UUID src = createAccount("ACC-001", "500.00");
        UUID dst = createAccount("ACC-002", "0.00");
        TransferRequest request = new TransferRequest(src, dst, money("10.00"), "USD", null);

        TransferOutcome a = transferService.transfer(request, "shared-key", "client-a");
        TransferOutcome b = transferService.transfer(request, "shared-key", "client-b");

        assertThat(b.replayed()).isFalse();
        assertThat(b.response().transferId()).isNotEqualTo(a.response().transferId());
        assertThat(count("transfers")).isEqualTo(2);
    }

    @Test
    void idempotencyRecordIsCommittedWithTheTransfer() {
        UUID src = createAccount("ACC-001", "500.00");
        UUID dst = createAccount("ACC-002", "0.00");
        TransferOutcome outcome = transfer(src, dst, "5.00", "key-record");

        var row = jdbc.queryForMap("""
                SELECT status, transfer_id, response_status FROM idempotency_records
                 WHERE client_id = 'default' AND idempotency_key = 'key-record'
                """, new MapSqlParameterSource());
        assertThat(row.get("status")).isEqualTo("COMPLETED");
        assertThat(row.get("transfer_id")).isEqualTo(outcome.response().transferId());
        assertThat(row.get("response_status")).isEqualTo(201);
    }

    @Test
    void referenceIsStored() {
        UUID src = createAccount("ACC-001", "500.00");
        UUID dst = createAccount("ACC-002", "0.00");
        TransferOutcome outcome = transferService.transfer(
                new TransferRequest(src, dst, new BigDecimal("1.00"), "USD", "EXT-1001"), "key-ref", null);
        assertThat(transferService.get(outcome.response().transferId()).reference()).isEqualTo("EXT-1001");
    }
}
