package com.example.ledger.account;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {

    private static final String COLUMNS =
            "id, account_number, currency, opening_balance, balance, status, created_at";

    static final RowMapper<Account> ROW_MAPPER = AccountRepository::mapRow;

    private final NamedParameterJdbcTemplate jdbc;

    public AccountRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Account account) {
        jdbc.update("""
                INSERT INTO accounts (id, account_number, currency, opening_balance, balance, status, created_at)
                VALUES (:id, :accountNumber, :currency, :openingBalance, :balance, :status, :createdAt)
                """, new MapSqlParameterSource()
                .addValue("id", account.id())
                .addValue("accountNumber", account.accountNumber())
                .addValue("currency", account.currency())
                .addValue("openingBalance", account.openingBalance())
                .addValue("balance", account.balance())
                .addValue("status", account.status().name())
                .addValue("createdAt", Timestamp.from(account.createdAt())));
    }

    public Optional<Account> findById(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM accounts WHERE id = :id",
                new MapSqlParameterSource("id", id), ROW_MAPPER).stream().findFirst();
    }

    /** Locks a single account row for the remainder of the current transaction. */
    public Optional<Account> findByIdForUpdate(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM accounts WHERE id = :id FOR UPDATE",
                new MapSqlParameterSource("id", id), ROW_MAPPER).stream().findFirst();
    }

    /**
     * Locks the given accounts with SELECT ... FOR UPDATE in a deterministic order (ascending id,
     * as PostgreSQL orders UUIDs) so that two transfers touching the same pair of accounts in
     * opposite directions acquire locks in the same order and cannot deadlock (spec 8.2).
     * Must be called inside a transaction; the locks are held until commit or rollback.
     */
    public List<Account> lockAccountsInStableOrder(Collection<UUID> ids) {
        return jdbc.query("SELECT " + COLUMNS + " FROM accounts WHERE id IN (:ids) ORDER BY id FOR UPDATE",
                new MapSqlParameterSource("ids", ids), ROW_MAPPER);
    }

    public void updateBalance(UUID id, BigDecimal newBalance) {
        int updated = jdbc.update("UPDATE accounts SET balance = :balance WHERE id = :id",
                new MapSqlParameterSource().addValue("id", id).addValue("balance", newBalance));
        if (updated != 1) {
            throw new IllegalStateException("Expected to update exactly one account balance, updated " + updated);
        }
    }

    public void updateStatus(UUID id, AccountStatus status) {
        jdbc.update("UPDATE accounts SET status = :status WHERE id = :id",
                new MapSqlParameterSource().addValue("id", id).addValue("status", status.name()));
    }

    private static Account mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Account(
                rs.getObject("id", UUID.class),
                rs.getString("account_number"),
                rs.getString("currency"),
                rs.getBigDecimal("opening_balance"),
                rs.getBigDecimal("balance"),
                AccountStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at").toInstant());
    }
}
