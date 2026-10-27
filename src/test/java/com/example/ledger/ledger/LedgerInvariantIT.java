package com.example.ledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import com.example.ledger.common.DomainException;
import com.example.ledger.ledger.dto.LedgerIntegrityReport;
import com.example.ledger.support.AbstractIntegrationTest;
import com.example.ledger.transfer.TransferOutcome;
import com.example.ledger.transfer.TransferRequest;
import com.example.ledger.transfer.TransferService;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * Property-style ledger invariant test (spec 11.4): random sequences of valid, rejected and
 * replayed transfers, checked against an in-memory model and against every invariant:
 * debits = credits, each completed transfer balanced, balance = opening + movements,
 * total money conserved, no entries for rejected transfers. Seeds are fixed so failures reproduce.
 */
class LedgerInvariantIT extends AbstractIntegrationTest {

    @Autowired
    TransferService transferService;

    @Autowired
    LedgerService ledgerService;

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {1L, 42L, 20261022L})
    void randomTransferSequencesPreserveAllInvariants(long seed) {
        Random random = new Random(seed);
        int accountCount = 6;
        List<UUID> accounts = new ArrayList<>();
        Map<UUID, BigDecimal> model = new HashMap<>();
        BigDecimal totalMoney = BigDecimal.ZERO;
        for (int i = 0; i < accountCount; i++) {
            BigDecimal opening = BigDecimal.valueOf(random.nextInt(50_000), 2); // 0.00 .. 499.99
            UUID id = createAccount("ACC-" + seed + "-" + i, opening.toPlainString());
            accounts.add(id);
            model.put(id, opening);
            totalMoney = totalMoney.add(opening);
        }

        int completed = 0;
        int rejected = 0;
        List<String> usedKeys = new ArrayList<>();
        Map<String, TransferRequest> requestsByKey = new HashMap<>();

        for (int step = 0; step < 150; step++) {
            // ~10% of steps replay an earlier key: must never post again.
            if (!usedKeys.isEmpty() && random.nextInt(10) == 0) {
                String key = usedKeys.get(random.nextInt(usedKeys.size()));
                TransferOutcome replay = transferService.transfer(requestsByKey.get(key), key, null);
                assertThat(replay.replayed()).isTrue();
                continue;
            }
            UUID src = accounts.get(random.nextInt(accountCount));
            UUID dst = accounts.get(random.nextInt(accountCount));
            if (src.equals(dst)) {
                continue;
            }
            BigDecimal amount = BigDecimal.valueOf(1 + random.nextInt(30_000), 2); // 0.01 .. 300.00
            String key = "seed-" + seed + "-step-" + step;
            TransferRequest request = new TransferRequest(src, dst, amount, "USD", null);
            try {
                transferService.transfer(request, key, null);
                model.put(src, model.get(src).subtract(amount));
                model.put(dst, model.get(dst).add(amount));
                usedKeys.add(key);
                requestsByKey.put(key, request);
                completed++;
            } catch (DomainException e) {
                assertThat(e.getCode()).isEqualTo("INSUFFICIENT_FUNDS");
                assertThat(model.get(src)).isLessThan(amount);
                rejected++;
            }
        }

        // Every balance equals the model, and none is negative.
        for (UUID id : accounts) {
            assertThat(balanceOf(id)).as("balance of %s", id).isEqualByComparingTo(model.get(id));
            assertThat(balanceOf(id).signum()).isGreaterThanOrEqualTo(0);
        }

        // Money is conserved.
        BigDecimal dbTotal = jdbc.queryForObject("SELECT SUM(balance) FROM accounts", new MapSqlParameterSource(),
                BigDecimal.class);
        assertThat(dbTotal).isEqualByComparingTo(totalMoney);

        // One transfer and two entries per completed operation, none for rejections or replays.
        assertThat(count("transfers")).isEqualTo(completed);
        assertThat(count("ledger_entries")).isEqualTo(2L * completed);
        assertThat(rejected + completed).isPositive();

        // Global audit: debits = credits, every transfer balanced, balance = opening + movements.
        LedgerIntegrityReport report = ledgerService.verifyIntegrity();
        assertThat(report.unbalancedTransfers()).isEmpty();
        assertThat(report.accountsOutOfBalance()).isEmpty();
        assertThat(report.totalDebits()).isEqualByComparingTo(report.totalCredits());
        assertThat(report.consistent()).isTrue();
    }
}
