-- V4: Reconciliation runs, imported external records and findings (spec section 9).
-- Imported evidence and findings are stored separately from the financial ledger
-- and never modify accounts, transfers or ledger entries.

CREATE TABLE reconciliation_runs (
    id                      UUID PRIMARY KEY,
    file_name               VARCHAR(255) NOT NULL,
    file_hash               CHAR(64)     NOT NULL,   -- SHA-256 of the uploaded bytes
    file_size_bytes         BIGINT       NOT NULL,
    mode                    VARCHAR(8)   NOT NULL,
    status                  VARCHAR(16)  NOT NULL,
    scope_from              TIMESTAMPTZ,
    scope_to                TIMESTAMPTZ,
    scope_currency          CHAR(3),
    time_tolerance_seconds  INTEGER      NOT NULL,
    total_rows              INTEGER      NOT NULL DEFAULT 0,
    valid_rows              INTEGER      NOT NULL DEFAULT 0,
    matched                 INTEGER      NOT NULL DEFAULT 0,
    mismatched              INTEGER      NOT NULL DEFAULT 0,
    missing_internal        INTEGER      NOT NULL DEFAULT 0,
    missing_external        INTEGER      NOT NULL DEFAULT 0,
    duplicate_external      INTEGER      NOT NULL DEFAULT 0,
    duplicate_internal      INTEGER      NOT NULL DEFAULT 0,
    invalid_records         INTEGER      NOT NULL DEFAULT 0,
    failure_reason          TEXT,
    started_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    completed_at            TIMESTAMPTZ,

    CONSTRAINT chk_run_mode
        CHECK (mode IN ('STRICT', 'PARTIAL')),

    CONSTRAINT chk_run_status
        CHECK (status IN ('PROCESSING', 'COMPLETED', 'REJECTED', 'FAILED')),

    CONSTRAINT chk_run_tolerance
        CHECK (time_tolerance_seconds >= 0),

    CONSTRAINT chk_run_file_hash
        CHECK (file_hash ~ '^[0-9a-f]{64}$'),

    CONSTRAINT chk_run_terminal_has_completed_at
        CHECK (status = 'PROCESSING' OR completed_at IS NOT NULL)
);

CREATE INDEX idx_reconciliation_runs_file_hash ON reconciliation_runs (file_hash, status);

CREATE TABLE external_transactions (
    id                   UUID PRIMARY KEY,
    run_id               UUID           NOT NULL REFERENCES reconciliation_runs (id) ON DELETE RESTRICT,
    row_number           INTEGER        NOT NULL,
    external_reference   VARCHAR(64)    NOT NULL,
    source_account       VARCHAR(64)    NOT NULL,
    destination_account  VARCHAR(64)    NOT NULL,
    amount               NUMERIC(19, 2) NOT NULL,
    currency             CHAR(3)        NOT NULL,
    transaction_time     TIMESTAMPTZ    NOT NULL,

    CONSTRAINT uq_external_run_row
        UNIQUE (run_id, row_number),

    CONSTRAINT chk_external_amount
        CHECK (amount > 0),

    CONSTRAINT chk_external_currency
        CHECK (currency ~ '^[A-Z]{3}$'),

    CONSTRAINT chk_external_row_number
        CHECK (row_number >= 2)   -- row 1 is the header
);

CREATE INDEX idx_external_transactions_reference ON external_transactions (run_id, external_reference);

CREATE TABLE reconciliation_items (
    id                       UUID PRIMARY KEY,
    run_id                   UUID         NOT NULL REFERENCES reconciliation_runs (id) ON DELETE RESTRICT,
    internal_transfer_id     UUID         REFERENCES transfers (id) ON DELETE RESTRICT,
    external_transaction_id  UUID         REFERENCES external_transactions (id) ON DELETE RESTRICT,
    row_number               INTEGER,
    external_reference       VARCHAR(64),
    result_type              VARCHAR(32)  NOT NULL,
    -- All discrepancy codes found for this pair; result_type is the primary classification.
    discrepancy_codes        VARCHAR(256),
    details                  JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_item_result_type
        CHECK (result_type IN (
            'MATCHED',
            'MISSING_EXTERNAL',
            'MISSING_INTERNAL',
            'DUPLICATE_EXTERNAL',
            'DUPLICATE_INTERNAL',
            'AMOUNT_MISMATCH',
            'ACCOUNT_MISMATCH',
            'CURRENCY_MISMATCH',
            'TIME_MISMATCH',
            'INVALID_EXTERNAL_RECORD')),

    -- Every finding except MISSING_EXTERNAL originates from exactly one file row.
    CONSTRAINT chk_item_row_origin
        CHECK ((result_type = 'MISSING_EXTERNAL') = (row_number IS NULL)),

    CONSTRAINT chk_item_missing_external_has_transfer
        CHECK (result_type <> 'MISSING_EXTERNAL' OR internal_transfer_id IS NOT NULL)
);

CREATE INDEX idx_reconciliation_items_run ON reconciliation_items (run_id, result_type);
CREATE UNIQUE INDEX uq_reconciliation_items_run_row
    ON reconciliation_items (run_id, row_number) WHERE row_number IS NOT NULL;
