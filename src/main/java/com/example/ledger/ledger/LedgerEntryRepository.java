package com.example.ledger.ledger;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

import com.example.ledger.ledger.dto.LedgerEntryResponse;
import com.example.ledger.ledger.dto.LedgerIntegrityReport.AccountDrift;
import com.example.ledger.ledger.dto.LedgerIntegrityReport.DebitCreditTotals;
import com.example.ledger.ledger.dto.LedgerIntegrityReport.UnbalancedTransfer;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

/**
 * Insert-only access to ledger_entries. There are deliberately no update or delete methods;
 * the database also rejects them with a trigger (spec 4.4).
 */
@Repository
public class LedgerEntryRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public LedgerEntryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertAll(List<LedgerEntry> entries) {
        SqlParameterSource[] batch = entries.stream()
                .map(e -> new MapSqlParameterSource()
                        .addValue("id", e.id())
                        .addValue("transferId", e.transferId())
                        .addValue("accountId", e.accountId())
                        .addValue("entryType", e.entryType().name())
                        .addValue("amount", e.amount())
                        .addValue("createdAt", Timestamp.from(e.createdAt())))
                .toArray(SqlParameterSource[]::new);
        int[] counts = jdbc.batchUpdate("""
                INSERT INTO ledger_entries (id, transfer_id, account_id, entry_type, amount, created_at)
                VALUES (:id, :transferId, :accountId, :entryType, :amount, :createdAt)
                """, batch);
        for (int count : counts) {
            // Statement.SUCCESS_NO_INFO (-2) is a legal batch result meaning "succeeded, count unknown".
            if (count != 1 && count != java.sql.Statement.SUCCESS_NO_INFO) {
                throw new IllegalStateException("Ledger entry was not persisted");
            }
        }
    }

    public List<LedgerEntry> findByTransferId(UUID transferId) {
        return jdbc.query("""
                SELECT id, transfer_id, account_id, entry_type, amount, created_at
                  FROM ledger_entries WHERE transfer_id = :transferId ORDER BY entry_type DESC
                """, new MapSqlParameterSource("transferId", transferId),
                (rs, i) -> new LedgerEntry(
                        rs.getObject("id", UUID.class),
                        rs.getObject("transfer_id", UUID.class),
                        rs.getObject("account_id", UUID.class),
                        EntryType.valueOf(rs.getString("entry_type")),
                        rs.getBigDecimal("amount"),
                        rs.getTimestamp("created_at").toInstant()));
    }

    /** Newest first, with the counterparty account and the transfer reference for readability. */
    public List<LedgerEntryResponse> findByAccount(UUID accountId, int limit, long offset) {
        return jdbc.query("""
                SELECT le.id, le.transfer_id, le.entry_type, le.amount, le.created_at, t.currency, t.reference,
                       CASE WHEN le.entry_type = 'DEBIT' THEN t.destination_account_id
                            ELSE t.source_account_id END AS counterparty_account_id
                  FROM ledger_entries le
                  JOIN transfers t ON t.id = le.transfer_id
                 WHERE le.account_id = :accountId
                 ORDER BY le.created_at DESC, le.id DESC
                 LIMIT :limit OFFSET :offset
                """, new MapSqlParameterSource()
                        .addValue("accountId", accountId)
                        .addValue("limit", limit)
                        .addValue("offset", offset),
                (rs, i) -> new LedgerEntryResponse(
                        rs.getObject("id", UUID.class),
                        rs.getObject("transfer_id", UUID.class),
                        EntryType.valueOf(rs.getString("entry_type")),
                        rs.getBigDecimal("amount"),
                        rs.getString("currency"),
                        rs.getObject("counterparty_account_id", UUID.class),
                        rs.getString("reference"),
                        rs.getTimestamp("created_at").toInstant()));
    }

    public long countByAccount(UUID accountId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entries WHERE account_id = :accountId",
                new MapSqlParameterSource("accountId", accountId), Long.class);
        return count == null ? 0 : count;
    }

    // ---- integrity queries (spec 11.4) ----

    public DebitCreditTotals totalDebitsAndCredits() {
        return jdbc.getJdbcTemplate().queryForObject("""
                SELECT COALESCE(SUM(amount) FILTER (WHERE entry_type = 'DEBIT'), 0)  AS debits,
                       COALESCE(SUM(amount) FILTER (WHERE entry_type = 'CREDIT'), 0) AS credits
                  FROM ledger_entries
                """, (rs, i) -> new DebitCreditTotals(rs.getBigDecimal("debits"), rs.getBigDecimal("credits")));
    }

    public List<UnbalancedTransfer> findUnbalancedTransfers() {
        return jdbc.getJdbcTemplate().query("""
                SELECT t.id, t.amount,
                       COALESCE(SUM(le.amount) FILTER (WHERE le.entry_type = 'DEBIT'), 0)  AS debits,
                       COALESCE(SUM(le.amount) FILTER (WHERE le.entry_type = 'CREDIT'), 0) AS credits
                  FROM transfers t
                  LEFT JOIN ledger_entries le ON le.transfer_id = t.id
                 WHERE t.status = 'COMPLETED'
                 GROUP BY t.id, t.amount
                HAVING COALESCE(SUM(le.amount) FILTER (WHERE le.entry_type = 'DEBIT'), 0) <> t.amount
                    OR COALESCE(SUM(le.amount) FILTER (WHERE le.entry_type = 'CREDIT'), 0) <> t.amount
                """, (rs, i) -> new UnbalancedTransfer(
                rs.getObject("id", UUID.class),
                rs.getBigDecimal("amount"),
                rs.getBigDecimal("debits"),
                rs.getBigDecimal("credits")));
    }

    public List<AccountDrift> findAccountsWhoseBalanceDisagreesWithLedger() {
        return jdbc.getJdbcTemplate().query("""
                SELECT a.id, a.balance,
                       a.opening_balance + COALESCE(SUM(CASE WHEN le.entry_type = 'CREDIT' THEN le.amount
                                                             ELSE -le.amount END), 0) AS ledger_balance
                  FROM accounts a
                  LEFT JOIN ledger_entries le ON le.account_id = a.id
                 GROUP BY a.id, a.balance, a.opening_balance
                HAVING a.balance <> a.opening_balance + COALESCE(SUM(CASE WHEN le.entry_type = 'CREDIT'
                                                                         THEN le.amount ELSE -le.amount END), 0)
                """, (rs, i) -> new AccountDrift(
                rs.getObject("id", UUID.class),
                rs.getBigDecimal("balance"),
                rs.getBigDecimal("ledger_balance")));
    }
}
