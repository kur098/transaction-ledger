-- V3: Idempotency records (spec section 7).
--
-- The (client_id, idempotency_key) unique constraint arbitrates concurrent claims.
-- A record is inserted in the SAME database transaction as the transfer it guards,
-- so it either commits together with the transfer (status COMPLETED, stored response)
-- or disappears with the rollback. A concurrent request with the same key blocks on
-- the unique index until the first transaction finishes, then reads the outcome.

CREATE TABLE idempotency_records (
    id               UUID PRIMARY KEY,
    client_id        VARCHAR(64)  NOT NULL,
    idempotency_key  VARCHAR(128) NOT NULL,
    request_hash     CHAR(64)     NOT NULL,
    status           VARCHAR(16)  NOT NULL,
    transfer_id      UUID REFERENCES transfers (id) ON DELETE RESTRICT,
    response_status  INTEGER,
    response_body    TEXT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    completed_at     TIMESTAMPTZ,

    CONSTRAINT uq_idempotency_client_key
        UNIQUE (client_id, idempotency_key),

    CONSTRAINT chk_idempotency_status
        CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),

    CONSTRAINT chk_idempotency_completed_has_response
        CHECK (status <> 'COMPLETED'
               OR (transfer_id IS NOT NULL
                   AND response_status IS NOT NULL
                   AND response_body IS NOT NULL
                   AND completed_at IS NOT NULL))
);

CREATE INDEX idx_idempotency_transfer ON idempotency_records (transfer_id);
