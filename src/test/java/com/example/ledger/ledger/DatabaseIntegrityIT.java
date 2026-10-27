package com.example.ledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import com.example.ledger.support.AbstractIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves the schema itself rejects invalid financial data (spec 11.2), independently of the
 * service layer: row constraints, immutability triggers and the deferred balanced-ledger and
 * balance-projection triggers that run at COMMIT.
 */
class DatabaseIntegrityIT extends AbstractIntegrationTest {

    @Autowired
    PlatformTransactionManager txManager;

    private UUID a;
    private UUID b;

    private void twoAccounts() {
        a = createAccount("ACC-A", "100.00");
        b = createAccount("ACC-B", "0.00");
    }

    private void insertTransfer(UUID id, UUID src, UUID dst, String amount) {
        jdbc.update("""
                INSERT INTO transfers (id, source_account_id, destination_account_id, amount, currency, status)
                VALUES (:id, :src, :dst, CAST(:amount AS NUMERIC), 'USD', 'COMPLETED')
                """, new MapSqlParameterSource().addValue("id", id).addValue("src", src)
                .addValue("dst", dst).addValue("amount", amount));
    }

    private void insertEntry(UUID transferId, UUID account, String type, String amount) {
        jdbc.update("""
                INSERT INTO ledger_entries (id, transfer_id, account_id, entry_type, amount)
                VALUES (:id, :t, :a, :type, CAST(:amount AS NUMERIC))
                """, new MapSqlParameterSource().addValue("id", UUID.randomUUID()).addValue("t", transferId)
                .addValue("a", account).addValue("type", type).addValue("amount", amount));
    }

    private void addToBalance(UUID account, String delta) {
        jdbc.update("UPDATE accounts SET balance = balance + CAST(:d AS NUMERIC) WHERE id = :id",
                new MapSqlParameterSource().addValue("id", account).addValue("d", delta));
    }

    private void inTransaction(Runnable work) {
        new TransactionTemplate(txManager).executeWithoutResult(status -> work.run());
    }

    @Test
    void allMigrationsApplied() {
        Integer applied = jdbc.getJdbcTemplate().queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success", Integer.class);
        assertThat(applied).isEqualTo(5);
    }

    @Test
    void balancedPostingWithMatchingBalancesCommits() {
        twoAccounts();
        UUID t = UUID.randomUUID();
        inTransaction(() -> {
            insertTransfer(t, a, b, "30.00");
            insertEntry(t, a, "DEBIT", "30.00");
            insertEntry(t, b, "CREDIT", "30.00");
            addToBalance(a, "-30.00");
            addToBalance(b, "30.00");
        });
        assertThat(balanceOf(a)).isEqualByComparingTo("70.00");
        assertThat(balanceOf(b)).isEqualByComparingTo("30.00");
    }

    @Test
    void oneSidedPostingCannotCommit() {
        twoAccounts();
        UUID t = UUID.randomUUID();
        assertThatThrownBy(() -> inTransaction(() -> {
            insertTransfer(t, a, b, "10.00");
            insertEntry(t, a, "DEBIT", "10.00");
            addToBalance(a, "-10.00");
        })).isInstanceOf(DataAccessException.class).hasStackTraceContaining("unbalanced ledger");
        assertThat(count("transfers")).isZero();
        assertThat(count("ledger_entries")).isZero();
        assertThat(balanceOf(a)).isEqualByComparingTo("100.00");
    }

    @Test
    void postingOnTheWrongAccountsCannotCommit() {
        twoAccounts();
        UUID t = UUID.randomUUID();
        assertThatThrownBy(() -> inTransaction(() -> {
            insertTransfer(t, a, b, "10.00");
            insertEntry(t, b, "DEBIT", "10.00");
            insertEntry(t, a, "CREDIT", "10.00");
            addToBalance(a, "10.00");
            addToBalance(b, "-10.00");
        })).hasStackTraceContaining("unbalanced ledger");
    }

    @Test
    void ledgerEntriesWithoutBalanceUpdateCannotCommit() {
        twoAccounts();
        UUID t = UUID.randomUUID();
        assertThatThrownBy(() -> inTransaction(() -> {
            insertTransfer(t, a, b, "10.00");
            insertEntry(t, a, "DEBIT", "10.00");
            insertEntry(t, b, "CREDIT", "10.00");
        })).hasStackTraceContaining("does not match ledger");
    }

    @Test
    void balanceCannotDriftFromTheLedger() {
        twoAccounts();
        assertThatThrownBy(() -> addToBalance(b, "5.00")).hasStackTraceContaining("does not match ledger");
        assertThat(balanceOf(b)).isEqualByComparingTo("0.00");
    }

    @Test
    void ledgerAndTransfersAreImmutable() {
        twoAccounts();
        UUID t = UUID.randomUUID();
        inTransaction(() -> {
            insertTransfer(t, a, b, "30.00");
            insertEntry(t, a, "DEBIT", "30.00");
            insertEntry(t, b, "CREDIT", "30.00");
            addToBalance(a, "-30.00");
            addToBalance(b, "30.00");
        });
        assertThatThrownBy(() -> jdbc.getJdbcTemplate().update("UPDATE ledger_entries SET amount = 1"))
                .hasStackTraceContaining("immutable");
        assertThatThrownBy(() -> jdbc.getJdbcTemplate().update("DELETE FROM ledger_entries"))
                .hasStackTraceContaining("immutable");
        assertThatThrownBy(() -> jdbc.getJdbcTemplate().update("UPDATE transfers SET amount = 1"))
                .hasStackTraceContaining("immutable");
        assertThatThrownBy(() -> jdbc.getJdbcTemplate().update("DELETE FROM transfers"))
                .hasStackTraceContaining("immutable");
        assertThatThrownBy(() -> jdbc.getJdbcTemplate().update("DELETE FROM accounts"))
                .hasStackTraceContaining("cannot be deleted");
        assertThatThrownBy(() -> jdbc.getJdbcTemplate().update("UPDATE accounts SET opening_balance = 999"))
                .hasStackTraceContaining("immutable");
        assertThat(count("ledger_entries")).isEqualTo(2);
    }

    @Test
    void rowConstraintsRejectInvalidRecords() {
        twoAccounts();
        assertThatThrownBy(() -> insertTransfer(UUID.randomUUID(), a, b, "0.00"))
                .isInstanceOf(DataIntegrityViolationException.class).hasStackTraceContaining("chk_transfer_amount");
        assertThatThrownBy(() -> insertTransfer(UUID.randomUUID(), a, a, "1.00"))
                .isInstanceOf(DataIntegrityViolationException.class).hasStackTraceContaining("chk_distinct_accounts");
        assertThatThrownBy(() -> insertTransfer(UUID.randomUUID(), a, UUID.randomUUID(), "1.00"))
                .isInstanceOf(DataIntegrityViolationException.class); // foreign key
        assertThatThrownBy(() -> jdbc.getJdbcTemplate().update("UPDATE accounts SET balance = -1"))
                .isInstanceOf(DataIntegrityViolationException.class).hasStackTraceContaining("chk_account_balance");
        assertThatThrownBy(() -> jdbc.getJdbcTemplate().update("UPDATE accounts SET status = 'DELETED'"))
                .isInstanceOf(DataIntegrityViolationException.class).hasStackTraceContaining("chk_account_status");
        assertThatThrownBy(() -> jdbc.getJdbcTemplate().update(
                "INSERT INTO accounts (id, account_number, currency) VALUES (gen_random_uuid(), 'ACC-A', 'USD')"))
                .isInstanceOf(DataIntegrityViolationException.class); // duplicate account number
    }

    @Test
    void idempotencyKeyIsUniquePerClient() {
        String sql = """
                INSERT INTO idempotency_records (id, client_id, idempotency_key, request_hash, status)
                VALUES (gen_random_uuid(), :client, 'k1', repeat('a', 64), 'IN_PROGRESS')
                """;
        jdbc.update(sql, new MapSqlParameterSource("client", "c1"));
        jdbc.update(sql, new MapSqlParameterSource("client", "c2")); // same key, other client: allowed
        assertThatThrownBy(() -> jdbc.update(sql, new MapSqlParameterSource("client", "c1")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
