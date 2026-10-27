package com.example.ledger.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.util.UUID;

import com.example.ledger.idempotency.IdempotencyRepository;
import com.example.ledger.ledger.LedgerEntryRepository;
import com.example.ledger.support.AbstractIntegrationTest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;

/**
 * Failure injection (spec 6.4): a failure at the LAST step of a transfer (persisting the
 * idempotency outcome) or in the middle (persisting ledger entries) must roll back every
 * earlier write - transfer row, ledger entries, both balance updates and the key claim.
 */
class TransferRollbackIT extends AbstractIntegrationTest {

    @Autowired
    TransferService transferService;

    @SpyBean
    IdempotencyRepository idempotencyRepository;

    @SpyBean
    LedgerEntryRepository ledgerEntryRepository;

    @AfterEach
    void resetSpies() {
        reset(idempotencyRepository, ledgerEntryRepository);
    }

    private void assertNothingCommitted(UUID src, UUID dst) {
        assertThat(balanceOf(src)).isEqualByComparingTo("100.00");
        assertThat(balanceOf(dst)).isEqualByComparingTo("0.00");
        assertThat(count("transfers")).isZero();
        assertThat(count("ledger_entries")).isZero();
        assertThat(count("idempotency_records")).isZero();
    }

    @Test
    void failureToPersistIdempotencyOutcomeRollsBackTheWholeTransfer() {
        UUID src = createAccount("ACC-001", "100.00");
        UUID dst = createAccount("ACC-002", "0.00");
        doThrow(new IllegalStateException("simulated failure"))
                .when(idempotencyRepository).complete(anyString(), anyString(), any(), anyInt(), anyString(), any());

        assertThatThrownBy(() -> transferService.transfer(
                new TransferRequest(src, dst, money("40.00"), "USD", null), "key-fail", null))
                .hasMessageContaining("simulated failure");

        assertNothingCommitted(src, dst);
    }

    @Test
    void failureToPersistLedgerEntriesRollsBackTheWholeTransfer() {
        UUID src = createAccount("ACC-001", "100.00");
        UUID dst = createAccount("ACC-002", "0.00");
        doThrow(new IllegalStateException("simulated ledger failure"))
                .when(ledgerEntryRepository).insertAll(any());

        assertThatThrownBy(() -> transferService.transfer(
                new TransferRequest(src, dst, money("40.00"), "USD", null), "key-fail-ledger", null))
                .hasMessageContaining("simulated ledger failure");

        assertNothingCommitted(src, dst);
    }

    @Test
    void afterAFailedAttemptTheSameKeyCanBeRetriedSuccessfully() {
        UUID src = createAccount("ACC-001", "100.00");
        UUID dst = createAccount("ACC-002", "0.00");
        TransferRequest request = new TransferRequest(src, dst, money("40.00"), "USD", null);
        doThrow(new IllegalStateException("simulated failure"))
                .when(idempotencyRepository).complete(anyString(), anyString(), any(), anyInt(), anyString(), any());
        assertThatThrownBy(() -> transferService.transfer(request, "key-retry-after-crash", null));

        reset(idempotencyRepository);
        TransferOutcome outcome = transferService.transfer(request, "key-retry-after-crash", null);

        assertThat(outcome.replayed()).isFalse();
        assertThat(balanceOf(src)).isEqualByComparingTo("60.00");
        assertThat(count("transfers")).isEqualTo(1);
    }
}
