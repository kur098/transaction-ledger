package com.example.ledger.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.ledger.ledger.LedgerService;
import com.example.ledger.support.AbstractIntegrationTest;
import com.example.ledger.transfer.exception.InsufficientFundsException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * Real concurrency against PostgreSQL (spec 8.3, 11.3): separate threads, separate connections,
 * separate transactions, released simultaneously by a latch, with a timeout, asserting only on
 * the final COMMITTED database state.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class TransferConcurrencyIT extends AbstractIntegrationTest {

    @Autowired
    TransferService transferService;

    @Autowired
    LedgerService ledgerService;

    /** Runs every task at the same instant and returns each outcome (result or thrown exception). */
    private <T> List<Object> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                Callable<Object> wrapped = () -> {
                    ready.countDown();
                    start.await();
                    try {
                        return task.call();
                    } catch (Exception e) {
                        return e;
                    }
                };
                futures.add(pool.submit(wrapped));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> f : futures) {
                outcomes.add(f.get(30, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentTransfersCannotOverspend() throws Exception {
        // Given: source balance = 100.00, two destinations, each transfer requests 80.00
        UUID source = createAccount("ACC-SRC", "100.00");
        UUID dest1 = createAccount("ACC-D1", "0.00");
        UUID dest2 = createAccount("ACC-D2", "0.00");

        // When: both transfer requests execute concurrently
        List<Object> outcomes = runConcurrently(List.of(
                () -> transferService.transfer(new TransferRequest(source, dest1, money("80.00"), "USD", null), "k-1", null),
                () -> transferService.transfer(new TransferRequest(source, dest2, money("80.00"), "USD", null), "k-2", null)));

        // Then: exactly one succeeds, exactly one fails for insufficient funds
        assertThat(outcomes).filteredOn(o -> o instanceof TransferOutcome).hasSize(1);
        assertThat(outcomes).filteredOn(o -> o instanceof InsufficientFundsException).hasSize(1);

        // source = 20.00; the successful destination received 80.00
        assertThat(balanceOf(source)).isEqualByComparingTo("20.00");
        BigDecimal received = balanceOf(dest1).add(balanceOf(dest2));
        assertThat(received).isEqualByComparingTo("80.00");
        assertThat(List.of(balanceOf(dest1), balanceOf(dest2)))
                .anySatisfy(b -> assertThat(b).isEqualByComparingTo("0.00"));

        // exactly one balanced journal, no partial records
        assertThat(count("transfers")).isEqualTo(1);
        assertThat(count("ledger_entries")).isEqualTo(2);
        assertThat(ledgerService.verifyIntegrity().consistent()).isTrue();
    }

    @Test
    void manyConcurrentDebitsNeverDriveTheBalanceNegative() throws Exception {
        UUID source = createAccount("ACC-SRC", "100.00");
        UUID dest = createAccount("ACC-DST", "0.00");
        int requests = 20; // 20 x 7.00 = 140.00 requested from 100.00

        List<Callable<TransferOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            String key = "k-" + i;
            tasks.add(() -> transferService.transfer(new TransferRequest(source, dest, money("7.00"), "USD", null), key, null));
        }
        List<Object> outcomes = runConcurrently(tasks);

        long successes = outcomes.stream().filter(o -> o instanceof TransferOutcome).count();
        long rejected = outcomes.stream().filter(o -> o instanceof InsufficientFundsException).count();
        assertThat(successes).isEqualTo(14); // floor(100 / 7)
        assertThat(rejected).isEqualTo(6);
        assertThat(balanceOf(source)).isEqualByComparingTo("2.00");
        assertThat(balanceOf(dest)).isEqualByComparingTo("98.00");
        assertThat(ledgerService.verifyIntegrity().consistent()).isTrue();
    }

    @Test
    void simultaneousRequestsWithTheSameKeyExecuteAtMostOnce() throws Exception {
        UUID source = createAccount("ACC-SRC", "1000.00");
        UUID dest = createAccount("ACC-DST", "0.00");
        TransferRequest request = new TransferRequest(source, dest, money("25.00"), "USD", null);
        int requests = 10;

        List<Callable<TransferOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            tasks.add(() -> transferService.transfer(request, "same-key", null));
        }
        List<Object> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(o -> o instanceof TransferOutcome,
                "every caller receives the committed result");
        List<TransferOutcome> results = outcomes.stream().map(TransferOutcome.class::cast).toList();
        assertThat(results.stream().map(r -> r.response().transferId()).distinct()).hasSize(1);
        assertThat(results.stream().filter(r -> !r.replayed())).hasSize(1);

        assertThat(count("transfers")).isEqualTo(1);
        assertThat(count("ledger_entries")).isEqualTo(2);
        assertThat(balanceOf(source)).isEqualByComparingTo("975.00");
        assertThat(balanceOf(dest)).isEqualByComparingTo("25.00");
    }

    @Test
    void opposingTransfersBetweenTheSameAccountsDoNotDeadlockOrLoseMoney() throws Exception {
        UUID a = createAccount("ACC-A", "1000.00");
        UUID b = createAccount("ACC-B", "1000.00");

        List<Callable<TransferOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String key = "k-" + i;
            boolean aToB = i % 2 == 0;
            tasks.add(() -> transferService.transfer(
                    new TransferRequest(aToB ? a : b, aToB ? b : a, money("10.00"), "USD", null), key, null));
        }
        List<Object> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(o -> o instanceof TransferOutcome,
                "stable lock ordering prevents deadlocks, so every transfer succeeds");
        assertThat(balanceOf(a)).isEqualByComparingTo("1000.00");
        assertThat(balanceOf(b)).isEqualByComparingTo("1000.00");
        assertThat(count("transfers")).isEqualTo(20);
        assertThat(ledgerService.verifyIntegrity().consistent()).isTrue();
    }

    @Test
    void independentAccountsTransferInParallelAndTotalMoneyIsConserved() throws Exception {
        int pairs = 8;
        List<UUID> sources = new ArrayList<>();
        List<UUID> dests = new ArrayList<>();
        for (int i = 0; i < pairs; i++) {
            sources.add(createAccount("ACC-S" + i, "50.00"));
            dests.add(createAccount("ACC-D" + i, "0.00"));
        }
        List<Callable<TransferOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < pairs; i++) {
            UUID s = sources.get(i);
            UUID d = dests.get(i);
            String key = "k-" + i;
            tasks.add(() -> transferService.transfer(new TransferRequest(s, d, money("50.00"), "USD", null), key, null));
        }
        runConcurrently(tasks);

        BigDecimal total = jdbc.queryForObject("SELECT SUM(balance) FROM accounts", new MapSqlParameterSource(),
                BigDecimal.class);
        assertThat(total).isEqualByComparingTo("400.00");
        assertThat(count("transfers")).isEqualTo(pairs);
    }
}
