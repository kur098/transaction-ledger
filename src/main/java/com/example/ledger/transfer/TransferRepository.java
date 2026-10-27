package com.example.ledger.transfer;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Insert-only: transfers are immutable once committed (enforced by a database trigger too). */
@Repository
public class TransferRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public TransferRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Transfer transfer) {
        jdbc.update("""
                INSERT INTO transfers (id, source_account_id, destination_account_id, amount, currency,
                                       reference, status, created_at)
                VALUES (:id, :source, :destination, :amount, :currency, :reference, :status, :createdAt)
                """, new MapSqlParameterSource()
                .addValue("id", transfer.id())
                .addValue("source", transfer.sourceAccountId())
                .addValue("destination", transfer.destinationAccountId())
                .addValue("amount", transfer.amount())
                .addValue("currency", transfer.currency())
                .addValue("reference", transfer.reference())
                .addValue("status", transfer.status().name())
                .addValue("createdAt", Timestamp.from(transfer.createdAt())));
    }

    public Optional<Transfer> findById(UUID id) {
        return jdbc.query("""
                SELECT id, source_account_id, destination_account_id, amount, currency, reference, status, created_at
                  FROM transfers WHERE id = :id
                """, new MapSqlParameterSource("id", id), TransferRepository::mapRow).stream().findFirst();
    }

    static Transfer mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Transfer(
                rs.getObject("id", UUID.class),
                rs.getObject("source_account_id", UUID.class),
                rs.getObject("destination_account_id", UUID.class),
                rs.getBigDecimal("amount"),
                rs.getString("currency"),
                rs.getString("reference"),
                TransferStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at").toInstant());
    }
}
