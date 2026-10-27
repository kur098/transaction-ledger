package com.example.ledger.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.example.ledger.account.Account;
import com.example.ledger.account.AccountRepository;
import com.example.ledger.account.AccountStatus;
import com.example.ledger.common.LedgerProperties;
import com.example.ledger.common.OperationErrorRecorder;
import com.example.ledger.common.TransientFailureException;
import com.example.ledger.idempotency.ClaimResult;
import com.example.ledger.idempotency.IdempotencyRecord;
import com.example.ledger.idempotency.IdempotencyService;
import com.example.ledger.ledger.LedgerService;
import com.example.ledger.transfer.exception.IdempotencyConflictException;
import com.example.ledger.transfer.exception.InsufficientFundsException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * Orchestration rules of {@link TransferService} with collaborators mocked. These complement,
 * but do not replace, the PostgreSQL-backed tests: financial integrity itself is proven in *IT classes.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TransferServiceTest {

    private static final UUID SRC = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DST = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant NOW = Instant.parse("2026-10-22T08:00:00Z");

    @Mock AccountRepository accounts;
    @Mock TransferRepository transfers;
    @Mock LedgerService ledger;
    @Mock IdempotencyService idempotency;
    @Mock OperationErrorRecorder errorRecorder;
    @Mock PlatformTransactionManager txManager;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private TransferService service;

    @BeforeEach
    void setUp() {
        when(txManager.getTransaction(any())).thenAnswer(inv -> new SimpleTransactionStatus());
        LedgerProperties properties = new LedgerProperties(Set.of("USD"),
                new LedgerProperties.Transfer(3, 1, 10), new LedgerProperties.Reconciliation(3600, 1000));
        service = new TransferService(accounts, transfers, ledger, idempotency, errorRecorder, properties,
                objectMapper, Clock.fixed(NOW, ZoneOffset.UTC), txManager);
    }

    private static TransferRequest request(String amount) {
        return new TransferRequest(SRC, DST, new BigDecimal(amount), "USD", null);
    }

    private static Account account(UUID id, String balance) {
        return new Account(id, "ACC-" + id.toString().charAt(0), "USD", new BigDecimal(balance),
                new BigDecimal(balance), AccountStatus.ACTIVE, NOW);
    }

    @Test
    void newKeyExecutesTransferOnceWithBothEntriesAndBalances() {
        when(idempotency.claimOrFind(anyString(), anyString(), anyString())).thenReturn(new ClaimResult.Claimed());
        when(accounts.lockAccountsInStableOrder(anyList())).thenReturn(List.of(account(SRC, "100.00"), account(DST, "5.00")));

        TransferOutcome outcome = service.transfer(request("30"), "key-1", null);

        assertThat(outcome.replayed()).isFalse();
        assertThat(outcome.httpStatus()).isEqualTo(201);
        assertThat(outcome.response().amount()).isEqualByComparingTo("30.00");
        verify(transfers).insert(any(Transfer.class));
        verify(ledger).postTransfer(any(Transfer.class));
        verify(accounts).updateBalance(SRC, new BigDecimal("70.00"));
        verify(accounts).updateBalance(DST, new BigDecimal("35.00"));
        verify(idempotency).complete(eq("default"), eq("key-1"), eq(outcome.response().transferId()),
                eq(201), anyString(), eq(NOW));
        verify(txManager).commit(any());
    }

    @Test
    void sameKeyAndSameRequestReturnsOriginalTransferWithoutExecuting() throws Exception {
        TransferResponse original = new TransferResponse(UUID.randomUUID(), TransferStatus.COMPLETED, SRC, DST,
                new BigDecimal("30.00"), "USD", null, NOW);
        IdempotencyRecord record = new IdempotencyRecord(UUID.randomUUID(), "default", "key-1", "hash",
                IdempotencyRecord.Status.COMPLETED, original.transferId(), 201,
                objectMapper.writeValueAsString(original), NOW, NOW);
        when(idempotency.claimOrFind(anyString(), anyString(), anyString())).thenReturn(new ClaimResult.Replay(record));

        TransferOutcome outcome = service.transfer(request("30.00"), "key-1", null);

        assertThat(outcome.replayed()).isTrue();
        assertThat(outcome.httpStatus()).isEqualTo(201);
        assertThat(outcome.response()).isEqualTo(original);
        verifyNoInteractions(accounts, transfers, ledger);
    }

    @Test
    void sameKeyWithDifferentRequestIsAConflict() {
        IdempotencyRecord record = new IdempotencyRecord(UUID.randomUUID(), "default", "key-1", "other",
                IdempotencyRecord.Status.COMPLETED, UUID.randomUUID(), 201, "{}", NOW, NOW);
        when(idempotency.claimOrFind(anyString(), anyString(), anyString())).thenReturn(new ClaimResult.Conflict(record));

        assertThatThrownBy(() -> service.transfer(request("31.00"), "key-1", null))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("different request");
        verifyNoInteractions(accounts, transfers, ledger);
        verify(txManager).rollback(any());
        verify(errorRecorder).record(eq("TRANSFER"), eq("IDEMPOTENCY_KEY_REUSED"), anyString(), eq("default"),
                eq("key-1"), any());
    }

    @Test
    void insufficientFundsRollsBackWithoutAnyWrites() {
        when(idempotency.claimOrFind(anyString(), anyString(), anyString())).thenReturn(new ClaimResult.Claimed());
        when(accounts.lockAccountsInStableOrder(anyList())).thenReturn(List.of(account(SRC, "10.00"), account(DST, "0.00")));

        assertThatThrownBy(() -> service.transfer(request("10.01"), "key-2", null))
                .isInstanceOf(InsufficientFundsException.class);

        verify(transfers, never()).insert(any());
        verify(ledger, never()).postTransfer(any());
        verify(accounts, never()).updateBalance(any(), any());
        verify(idempotency, never()).complete(any(), any(), any(), anyInt(), any(), any());
        verify(txManager).rollback(any());
        verify(txManager, never()).commit(any());
    }

    @Test
    void validationFailureNeverOpensATransaction() {
        assertThatThrownBy(() -> service.transfer(request("0"), "key-3", null))
                .hasMessageContaining("greater than zero");
        verifyNoInteractions(txManager, idempotency, accounts);
    }

    @Test
    void lockConflictsAreRetriedThenSurfaceAsTransient() {
        when(idempotency.claimOrFind(anyString(), anyString(), anyString())).thenReturn(new ClaimResult.Claimed());
        when(accounts.lockAccountsInStableOrder(anyList())).thenThrow(new CannotAcquireLockException("lock timeout"));

        assertThatThrownBy(() -> service.transfer(request("1.00"), "key-4", null))
                .isInstanceOf(TransientFailureException.class);
        verify(accounts, times(3)).lockAccountsInStableOrder(anyList());
        verify(txManager, times(3)).rollback(any());
    }

    @Test
    void transientConflictFollowedBySuccessCompletes() {
        when(idempotency.claimOrFind(anyString(), anyString(), anyString())).thenReturn(new ClaimResult.Claimed());
        when(accounts.lockAccountsInStableOrder(anyList()))
                .thenThrow(new CannotAcquireLockException("deadlock"))
                .thenReturn(List.of(account(SRC, "100.00"), account(DST, "0.00")));

        TransferOutcome outcome = service.transfer(request("1.00"), "key-5", null);

        assertThat(outcome.replayed()).isFalse();
        verify(transfers, times(1)).insert(any());
    }
}
