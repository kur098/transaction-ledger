-- V5: Operational error log (FR-12).
-- Written in its own transaction AFTER a business operation has rolled back, so a
-- rejected transfer or failed import leaves an investigable trace without leaving
-- any partial financial state. Never stores credentials or full request payloads.

CREATE TABLE operation_errors (
    id               UUID PRIMARY KEY,
    occurred_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    operation        VARCHAR(32)  NOT NULL,
    error_code       VARCHAR(64)  NOT NULL,
    message          TEXT         NOT NULL,
    request_id       VARCHAR(64),
    client_id        VARCHAR(64),
    idempotency_key  VARCHAR(128),
    related_id       UUID,

    CONSTRAINT chk_operation
        CHECK (operation IN ('TRANSFER', 'RECONCILIATION'))
);

CREATE INDEX idx_operation_errors_time ON operation_errors (occurred_at DESC);
CREATE INDEX idx_operation_errors_related ON operation_errors (related_id);
