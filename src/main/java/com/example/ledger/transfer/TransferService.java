package com.example.ledger.transfer;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import com.example.ledger.account.Account;
import com.example.ledger.account.AccountRepository;
import com.example.ledger.common.DomainException;
import com.example.ledger.common.LedgerProperties;
import com.example.ledger.common.NotFoundException;
import com.example.ledger.common.OperationErrorRecorder;
import com.example.ledger.common.TransientFailureException;
import com.example.ledger.idempotency.ClaimResult;
import com.example.ledger.idempotency.IdempotencyRecord;
import com.example.ledger.idempotency.IdempotencyService;
import com.example.ledger.idempotency.RequestFingerprint;
import com.example.ledger.ledger.LedgerService;
import com.example.ledger.transfer.exception.IdempotencyConflictException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Executes transfers as a single atomic unit of work (spec 6.3):
 *
 * <ol>
 *   <li>validate request fields and amount (no database access)</li>
 *   <li>claim the idempotency key (unique constraint arbitrates concurrent duplicates)</li>
 *   <li>lock source and destination with SELECT ... FOR UPDATE in ascending-id order</li>
 *   <li>validate account status, currency and funds - only now, while holding the locks</li>
 *   <li>insert the transfer and its DEBIT + CREDIT ledger entries</li>
 *   <li>update both materialized balances</li>
 *   <li>store the response on the idempotency record</li>
 *   <li>commit; deferred triggers re-verify the double entry and balance projection</li>
 * </ol>
 *
 * <p>All of steps 2-8 run in one {@link TransactionTemplate} transaction. Any exception rolls
 * everything back, including the idempotency claim. Using a programmatic template (instead of
 * {@code @Transactional} on a private method) makes the boundary explicit and guarantees
 * rollback for every exception type. A deadlock, lock timeout or serialization failure retries
 * the WHOLE transaction a bounded number of times, then surfaces as 503 so the client retries
 * with the same Idempotency-Key.
 */
@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);
    static final int CREATED = 201;

    private final AccountRepository accounts;
    private final TransferRepository transfers;
    private final LedgerService ledger;
    private final IdempotencyService idempotency;
    private final OperationErrorRecorder errorRecorder;
    private final LedgerProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public TransferService(AccountRepository accounts,
                           TransferRepository transfers,
                           LedgerService ledger,
                           IdempotencyService idempotency,
                           OperationErrorRecorder errorRecorder,
                           LedgerProperties properties,
                           ObjectMapper objectMapper,
                           Clock clock,
                           PlatformTransactionManager transactionManager) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.ledger = ledger;
        this.idempotency = idempotency;
        this.errorRecorder = errorRecorder;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.transaction.setTimeout(properties.transfer().transactionTimeoutSeconds());
    }

    public TransferOutcome transfer(TransferRequest rawRequest, String rawIdempotencyKey, String rawClientId) {
        String clientId = null;
        String key = null;
        try {
            clientId = TransferValidator.normalizeClientId(rawClientId);
            key = TransferValidator.validateIdempotencyKey(rawIdempotencyKey);
            TransferRequest request = TransferValidator.validateAndNormalize(rawRequest, properties.supportedCurrencies());
            String requestHash = RequestFingerprint.of(request);
            return executeWithRetry(request, key, clientId, requestHash);
        } catch (DomainException e) {
            errorRecorder.record("TRANSFER", e.getCode(), e.getMessage(), clientId, key, null);
            throw e;
        }
    }

    public TransferResponse get(UUID transferId) {
        return transfers.findById(transferId)
                .map(TransferResponse::from)
                .orElseThrow(() -> NotFoundException.transfer(transferId));
    }

    private TransferOutcome executeWithRetry(TransferRequest request, String key, String clientId, String requestHash) {
        int maxAttempts = Math.max(1, properties.transfer().maxAttempts());
        for (int attempt = 1; ; attempt++) {
            try {
                return transaction.execute(status -> executeOnce(request, key, clientId, requestHash));
            } catch (ConcurrencyFailureException e) {
                // Deadlock (40P01), lock timeout (55P03) or serialization failure (40001):
                // the transaction was rolled back as a whole, so it is safe to run it again.
                if (attempt >= maxAttempts) {
                    throw new TransientFailureException(
                            "The transfer could not be completed due to contention. Retry with the same Idempotency-Key.", e);
                }
                log.info("Transient conflict on transfer attempt {}/{} (key={}): {}",
                        attempt, maxAttempts, key, e.getClass().getSimpleName());
                backoff(attempt);
            }
        }
    }

    /** Runs inside the transaction opened by {@link #executeWithRetry}. */
    private TransferOutcome executeOnce(TransferRequest request, String key, String clientId, String requestHash) {
        ClaimResult claim = idempotency.claimOrFind(clientId, key, requestHash);
        switch (claim) {
            case ClaimResult.Replay replay -> {
                return replay(replay.record());
            }
            case ClaimResult.Conflict conflict -> throw IdempotencyConflictException.keyReused();
            case ClaimResult.InProgress inProgress -> throw IdempotencyConflictException.inProgress();
            case ClaimResult.Claimed claimed -> {
                // fall through to execution
            }
        }

        List<Account> locked = accounts.lockAccountsInStableOrder(
                List.of(request.sourceAccountId(), request.destinationAccountId()));
        Account source = find(locked, request.sourceAccountId());
        Account destination = find(locked, request.destinationAccountId());

        TransferValidator.validateAccounts(source, destination, request);

        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS); // PostgreSQL precision
        Transfer transfer = new Transfer(UUID.randomUUID(), source.id(), destination.id(), request.amount(),
                request.currency(), request.reference(), TransferStatus.COMPLETED, now);

        transfers.insert(transfer);
        ledger.postTransfer(transfer);
        accounts.updateBalance(source.id(), source.debit(request.amount()).balance());
        accounts.updateBalance(destination.id(), destination.credit(request.amount()).balance());

        TransferResponse response = TransferResponse.from(transfer);
        idempotency.complete(clientId, key, transfer.id(), CREATED, toJson(response), now);
        log.info("Transfer {} completed: {} -> {} amount={} {}", transfer.id(), source.id(), destination.id(),
                transfer.amount(), transfer.currency());
        return new TransferOutcome(response, CREATED, false);
    }

    private TransferOutcome replay(IdempotencyRecord record) {
        try {
            TransferResponse stored = objectMapper.readValue(record.responseBody(), TransferResponse.class);
            return new TransferOutcome(stored, record.responseStatus(), true);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored idempotent response is unreadable for record " + record.id(), e);
        }
    }

    private static Account find(List<Account> locked, UUID id) {
        return locked.stream()
                .filter(a -> a.id().equals(id))
                .findFirst()
                .orElseThrow(() -> NotFoundException.account(id));
    }

    private String toJson(TransferResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize transfer response", e);
        }
    }

    private void backoff(int attempt) {
        long base = properties.transfer().retryBackoffMillis();
        long jitter = (long) (Math.random() * base);
        try {
            Thread.sleep(base * attempt + jitter);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TransientFailureException("Interrupted while retrying transfer", e);
        }
    }
}
