# Transaction Ledger and Reconciliation System

A single-currency banking ledger backend built with **Java 21, Spring Boot 3.3, PostgreSQL 16, SQL and JUnit 5**.
It executes account-to-account transfers as atomic double-entry postings, prevents duplicate execution with
database-backed idempotency keys, prevents concurrent overspending with row-level locks, and reconciles the
ledger against external CSV statements.

The implementation follows `transaction_ledger_reconciliation_system_spec.md`; section references (§) below point into it.

---

## Quick start

Prerequisites: JDK 21, Maven 3.9+, Docker (for PostgreSQL and for Testcontainers).

```bash
docker compose up -d                 # PostgreSQL 16 on localhost:5432 (db/user/password: ledger)
mvn spring-boot:run                  # Flyway creates the schema on startup; API on :8080
./samples/demo.sh                    # end-to-end walkthrough with curl + jq
```

Configuration (environment variables): `LEDGER_DB_URL`, `LEDGER_DB_USER`, `LEDGER_DB_PASSWORD`, `LEDGER_DB_POOL_SIZE`.
Business settings live under `ledger.*` in `application.yml` (supported currencies, retry policy, reconciliation tolerance).

## Running the tests

```bash
mvn test      # unit tests (*Test): validation, fingerprinting, CSV parsing, matching, service orchestration
mvn verify    # + integration tests (*IT) against a real PostgreSQL started by Testcontainers
```

No Docker? Point the integration tests at any empty PostgreSQL database:

```bash
mvn verify -Dtest.db.url=jdbc:postgresql://localhost:5432/ledger_test -Dtest.db.user=postgres -Dtest.db.password=secret
```

Integration tests never use test-level `@Transactional` (it would hide commit-time trigger checks and break multi-threaded
tests, §11.2); each test truncates the tables and inspects committed state.

---

## Architecture

```
REST controllers ──► services ──► repositories (JdbcTemplate, explicit SQL) ──► PostgreSQL
  account/            AccountService        AccountRepository                    accounts
  transfer/           TransferService       TransferRepository                   transfers
  ledger/             LedgerService         LedgerEntryRepository                ledger_entries
  idempotency/        IdempotencyService    IdempotencyRepository                idempotency_records
  reconciliation/     ReconciliationService ReconciliationRepository             reconciliation_runs / external_transactions / reconciliation_items
                      CsvImportService ─► CsvTransactionParser (pure)
                      ReconciliationMatcher (pure)
  common/             GlobalExceptionHandler, RequestIdFilter, OperationErrorRecorder ─► operation_errors
```

Persistence uses **Spring JDBC rather than JPA** so every lock, `ON CONFLICT` clause and transaction boundary is visible
in the code (§13). Validation rules, CSV parsing and matching are plain Java classes with no Spring dependency so they
are unit-testable in isolation.

## How the core guarantees are enforced

| Guarantee | Mechanism | Where |
|---|---|---|
| **Double entry** (§4.1) | `LedgerService.postTransfer` always writes the DEBIT and CREDIT together. A `DEFERRABLE INITIALLY DEFERRED` constraint trigger re-checks every touched transfer **at COMMIT**: exactly one DEBIT of `amount` on the source and one CREDIT of `amount` on the destination. A one-sided posting cannot commit, even via raw SQL. | `V2__create_transfers_and_ledger.sql` |
| **Balance = ledger** (§4.3) | Accounts store an immutable `opening_balance`. A second deferred trigger verifies `balance = opening_balance + credits − debits` for every account whose balance changed or that received an entry. Balances can't drift from the ledger. | `V2__…sql` |
| **Immutability** (§4.4) | Triggers reject `UPDATE`/`DELETE` on `ledger_entries` and `transfers`, deletion of accounts and changes to `opening_balance`. Repositories expose no update/delete methods for ledger data. | `V1`, `V2` |
| **Atomicity** (§6.3, §6.4) | Claim → lock → validate → insert transfer → post entries → update balances → store idempotent response all run in **one** `TransactionTemplate` transaction. Every domain exception is unchecked, so any failure rolls back everything (including the key claim). Failure injection tests prove it. | `TransferService` |
| **Idempotency** (§7) | `INSERT … ON CONFLICT (client_id, idempotency_key) DO NOTHING` inside the transfer transaction. PostgreSQL makes a concurrent duplicate **wait** on the unique index until the first transaction ends, then it reads the committed response. Same key + same fingerprint → original response replayed (same status, `Idempotent-Replayed: true`); different fingerprint → `409`. No in-memory maps or JVM locks. | `IdempotencyRepository`, `V3` |
| **No overspending** (§8) | `SELECT … FOR UPDATE … ORDER BY id` locks both accounts in a deterministic order (no deadlocks between opposing transfers); funds are checked only while holding the locks. `lock_timeout = 5s`; deadlock / lock-timeout / serialization failures retry the **whole** transaction (bounded, with jitter) and then return `503`. `CHECK (balance >= 0)` is the last line of defence. | `AccountRepository`, `TransferService` |
| **Exact money** (§4.2, §5.2) | `BigDecimal` + `NUMERIC(19,2)`; amounts with more than 2 decimals are rejected, never rounded; API amounts are JSON strings. | `Money`, DTOs |

### Transfer flow

```
POST /api/v1/transfers  (Idempotency-Key, optional X-Client-Id)
  1. validate fields (no DB access)                    ──► 400 on failure
  BEGIN
  2. claim (client_id, key)                            ──► replay or 409 if already used
  3. SELECT both accounts FOR UPDATE ORDER BY id
  4. active? same currency? sufficient funds?          ──► 404 / 422 (ROLLBACK)
  5. INSERT transfer, INSERT debit + credit entries
  6. UPDATE both balances
  7. UPDATE idempotency record with stored response
  COMMIT   ← deferred triggers verify double entry and balances here
```

A rejected transfer leaves no transfer row, no entries and no idempotency record; it is logged to `operation_errors`
(in a separate transaction) for investigation (FR-12). Because the claim rolls back with the rejection, retrying the same
key after funds arrive executes normally.

---

## Reconciliation (§9)

`POST /api/v1/reconciliations` (multipart field `file`) with optional parameters:

| Parameter | Default | Meaning |
|---|---|---|
| `mode` | `PARTIAL` | `STRICT` rejects the whole file if any row is invalid; `PARTIAL` reconciles valid rows and reports invalid ones. |
| `from`, `to` | span of the file's times ± tolerance | Window of internal transfers that are expected to appear in the file (candidates for `MISSING_EXTERNAL`). Both or neither. |
| `currency` | all | Restricts `MISSING_EXTERNAL` candidates to one currency. |
| `toleranceSeconds` | `3600` | Allowed difference between internal `created_at` and external `transaction_time`. |
| `rerun` | `false` | An identical file (same SHA-256) returns its existing run (`200`, `Reconciliation-Reused: true`) without importing rows again. `true` forces a fresh run, e.g. after ledger corrections. |

**CSV contract:** UTF-8 (BOM allowed), RFC 4180 quoting, header containing
`external_reference, source_account, destination_account, amount, currency, transaction_time` in any order (extra columns
ignored). Accounts are referenced by **account number**. Timestamps must be ISO-8601 with an offset. Every invalid row is
reported with its line number and all of its errors. See `samples/external_statement_sample.csv`.

**Matching rules** (`ReconciliationMatcher`):

1. Rows match internal transfers by the transfer's `reference` (trimmed, case-insensitive). Amount and time are never used as identity.
2. In file order, the first row for a reference is primary; later rows → `DUPLICATE_EXTERNAL`.
3. No internal transfer → `MISSING_INTERNAL`. Several internal transfers for one reference → `DUPLICATE_INTERNAL` (all IDs listed for manual review, none picked arbitrarily).
4. Exactly one → compare accounts, amount, currency and time. Every difference is stored in `discrepancyCodes`; the primary `resultType` follows ACCOUNT > AMOUNT > CURRENCY > TIME. No differences → `MATCHED`.
5. In-scope internal transfers no row accounted for → `MISSING_EXTERNAL`.

Transfer references are intentionally **not unique** in the database: idempotency keys prevent duplicates, and
reconciliation must be able to *detect* a duplicate posting (e.g. a client that retried with a new key).

**Counting model** (§9.5): one item per file row, so
`totalExternalRecords = matched + mismatched + missingInternal + duplicateExternal + duplicateInternal + invalidRecords`.
`missingExternal` counts internal transfers (which have no row) separately. The service refuses to complete a run whose
counts don't add up.

**Reliability:** the run is committed as `PROCESSING` first; staging rows, matching and storing findings then happen in
one `REPEATABLE READ` transaction (one consistent ledger snapshot). An unexpected error rolls that back entirely and marks
the run `FAILED`, so the same file can simply be uploaded again. Reconciliation never writes to accounts, transfers or the ledger.

---

## API reference

| Method | Endpoint | Purpose |
|---|---|---|
| `POST` | `/api/v1/accounts` | Create account `{accountNumber, currency, openingBalance}` |
| `GET` | `/api/v1/accounts/{id}` | Account details and balance |
| `PATCH` | `/api/v1/accounts/{id}/status` | `{status: ACTIVE\|FROZEN\|CLOSED}`; closing requires zero balance, CLOSED is terminal |
| `GET` | `/api/v1/accounts/{id}/ledger?page=&size=` | Paginated ledger entries, newest first |
| `POST` | `/api/v1/transfers` | Execute a transfer (requires `Idempotency-Key`) |
| `GET` | `/api/v1/transfers/{id}` | Transfer details |
| `POST` | `/api/v1/reconciliations` | Upload a statement and reconcile |
| `GET` | `/api/v1/reconciliations/{runId}` | Run status and summary |
| `GET` | `/api/v1/reconciliations/{runId}/items?type=&page=&size=` | Findings, optionally filtered by result type |
| `GET` | `/api/v1/ledger/integrity` | Full audit: debits = credits, every transfer balanced, every balance = opening + movements |
| `GET` | `/actuator/health` | Health check |

Transfer request:

```http
POST /api/v1/transfers
Content-Type: application/json
Idempotency-Key: 6f1c0b2e-transfer-001
X-Client-Id: mobile-app          (optional; scopes the key, default "default")

{"sourceAccountId":"…","destinationAccountId":"…","amount":"150.00","currency":"USD","reference":"EXT-1001"}
```

### Error contract (§10)

```json
{"code":"INSUFFICIENT_FUNDS","message":"The source account has insufficient funds.","timestamp":"2026-10-22T08:00:00Z","requestId":"req-12345"}
```

| Status | When |
|---|---|
| `201` | Transfer/account/reconciliation run created (a replayed transfer also returns its original `201`) |
| `200` | Retrieval; reused reconciliation run |
| `400` | Malformed JSON or amount, missing field/header, zero/negative amount, >2 decimals, same source and destination, unsupported currency, invalid parameters |
| `404` | Unknown account, transfer or run |
| `409` | Idempotency key reused with a different request; duplicate account number |
| `422` | Insufficient funds, inactive account, currency mismatch, closing a non-zero account |
| `503` | Database unavailable or contention retries exhausted — retry with the same key |
| `500` | Unexpected error (details logged, never returned) |

Every response carries `X-Request-Id` (taken from the request when safe, generated otherwise); it also appears in logs and error bodies.

---

## Test suite

| Class | Kind | Covers |
|---|---|---|
| `TransferValidatorTest` | unit | §11.1 validity rules: amounts, scale, same account, currency, keys, account status, funds |
| `RequestFingerprintTest` | unit | Canonical request hashing |
| `AccountTest` | unit | Exact debit/credit arithmetic, no negative balance |
| `CsvTransactionParserTest` | unit | Header/encoding/row validation, quoting, row numbers |
| `ReconciliationMatcherTest` | unit | Every result type, multi-discrepancy rows, counting model, determinism |
| `TransferServiceTest` | unit (Mockito) | Replay without execution, conflict, no writes on rejection, bounded retry |
| `DatabaseIntegrityIT` | PostgreSQL | Migrations, CHECK/FK/unique constraints, immutability, deferred balanced-ledger and balance triggers |
| `TransferServiceIT` | PostgreSQL | Success, insufficient funds, unknown/inactive accounts, currency mismatch, idempotent retry/conflict, per-client keys |
| `TransferRollbackIT` | PostgreSQL | Injected failures mid-transfer roll back everything; same key retries cleanly |
| `TransferConcurrencyIT` | PostgreSQL, threads | $100 / 2×$80 → exactly one succeeds; 20 concurrent debits; 10 simultaneous same-key requests; opposing transfers without deadlock |
| `LedgerInvariantIT` | PostgreSQL, property-style | Random sequences of transfers, rejections and replays vs. an in-memory model; all §11.4 invariants |
| `TransferApiIT` | PostgreSQL + MockMvc | HTTP status mapping, error body, headers, pagination, account endpoints |
| `ReconciliationIT` | PostgreSQL + MockMvc | All discrepancy types end to end, reimport reuse, strict mode, bad header, scope/tolerance, failed-import recovery, ledger untouched |

## Scope and known limitations

Per §1, authentication/authorization, regulatory controls, settlement integrations and multi-currency conversion are out
of scope (`/ledger/integrity` would need admin authorization in production). Other deliberate simplifications:

- Idempotency records are kept indefinitely; a retention/cleanup job is not implemented.
- Business rejections (e.g. insufficient funds) are not cached against the key; a retry re-evaluates.
- The `FAILED` transfer status exists in the schema contract but is not written: rejected transfers leave no row.
- The balance-projection trigger sums an account's ledger entries on each change. That is fine at project scale; at high
  volume you would replace it with periodic audits or a running-total column checked incrementally.
- Reconciliation processes an upload synchronously (default limit 10 MB / 200,000 rows).
