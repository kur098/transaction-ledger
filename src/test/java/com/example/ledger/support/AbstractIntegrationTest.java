package com.example.ledger.support;

import java.math.BigDecimal;
import java.util.UUID;

import com.example.ledger.account.Account;
import com.example.ledger.account.AccountService;
import com.example.ledger.account.dto.CreateAccountRequest;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base for database-backed tests. Runs against a REAL PostgreSQL (spec 11.2): by default a
 * Testcontainers postgres:16 container shared by all test classes; set the system property
 * {@code test.db.url} (plus optional {@code test.db.user} / {@code test.db.password}) to use an
 * existing database instead, e.g. when Docker is unavailable.
 *
 * <p>Tests deliberately do NOT use test-level {@code @Transactional}: that would hide commit-time
 * behaviour (deferred triggers) and break multi-threaded tests. Instead every test starts from
 * empty tables.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    private static final String EXTERNAL_URL = System.getProperty("test.db.url");
    private static final PostgreSQLContainer<?> POSTGRES;

    static {
        if (EXTERNAL_URL == null || EXTERNAL_URL.isBlank()) {
            POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("ledger")
                    .withUsername("ledger")
                    .withPassword("ledger");
            POSTGRES.start(); // shared for the JVM; Testcontainers' Ryuk cleans it up afterwards
        } else {
            POSTGRES = null;
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        if (POSTGRES != null) {
            registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
            registry.add("spring.datasource.username", POSTGRES::getUsername);
            registry.add("spring.datasource.password", POSTGRES::getPassword);
        } else {
            registry.add("spring.datasource.url", () -> EXTERNAL_URL);
            registry.add("spring.datasource.username", () -> System.getProperty("test.db.user", "postgres"));
            registry.add("spring.datasource.password", () -> System.getProperty("test.db.password", ""));
        }
    }

    @Autowired
    protected NamedParameterJdbcTemplate jdbc;

    @Autowired
    protected AccountService accountService;

    @BeforeEach
    void cleanDatabase() {
        // TRUNCATE does not fire the row-level immutability triggers, so tests can reset state.
        jdbc.getJdbcTemplate().execute("""
                TRUNCATE reconciliation_items, external_transactions, reconciliation_runs,
                         idempotency_records, ledger_entries, transfers, operation_errors, accounts CASCADE
                """);
    }

    protected UUID createAccount(String accountNumber, String openingBalance) {
        return createAccount(accountNumber, openingBalance, "USD");
    }

    protected UUID createAccount(String accountNumber, String openingBalance, String currency) {
        Account account = accountService.create(
                new CreateAccountRequest(accountNumber, currency, new BigDecimal(openingBalance)));
        return account.id();
    }

    protected BigDecimal balanceOf(UUID accountId) {
        return jdbc.queryForObject("SELECT balance FROM accounts WHERE id = :id",
                new MapSqlParameterSource("id", accountId), BigDecimal.class);
    }

    protected long count(String table) {
        Long n = jdbc.getJdbcTemplate().queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return n == null ? 0 : n;
    }

    protected static BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}
