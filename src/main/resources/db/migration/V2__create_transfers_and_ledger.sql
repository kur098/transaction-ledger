-- V2: Transfers, ledger entries and the database-enforced accounting invariants.

CREATE TABLE transfers (
    id                      UUID PRIMARY KEY,
    source_account_id       UUID           NOT NULL REFERENCES accounts (id) ON DELETE RESTRICT,
    destination_account_id  UUID           NOT NULL REFERENCES accounts (id) ON DELETE RESTRICT,
    amount                  NUMERIC(19, 2) NOT NULL,
    currency                CHAR(3)        NOT NULL,
    -- Business reference shared with external statements; used by reconciliation.
    -- Deliberately NOT unique: duplicate prevention is the job of idempotency keys,
    -- and reconciliation must be able to *detect* duplicate internal postings
    -- (e.g. a client that retried with a fresh idempotency key).
    reference               VARCHAR(64),
    status                  VARCHAR(16)    NOT NULL,
    created_at              TIMESTAMPTZ    NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_transfer_amount
        CHECK (amount > 0),

    CONSTRAINT chk_distinct_accounts
        CHECK (source_account_id <> destination_account_id),

    CONSTRAINT chk_transfer_currency
        CHECK (currency ~ '^[A-Z]{3}$'),

    CONSTRAINT chk_transfer_status
        CHECK (status IN ('COMPLETED', 'FAILED'))
);

-- Reconciliation matches references case-insensitively after trimming.
CREATE INDEX idx_transfers_reference ON transfers (UPPER(reference)) WHERE reference IS NOT NULL;
CREATE INDEX idx_transfers_created_at ON transfers (created_at);
CREATE INDEX idx_transfers_source ON transfers (source_account_id);
CREATE INDEX idx_transfers_destination ON transfers (destination_account_id);

CREATE TABLE ledger_entries (
    id           UUID PRIMARY KEY,
    transfer_id  UUID           NOT NULL REFERENCES transfers (id) ON DELETE RESTRICT,
    account_id   UUID           NOT NULL REFERENCES accounts (id) ON DELETE RESTRICT,
    entry_type   VARCHAR(8)     NOT NULL,
    amount       NUMERIC(19, 2) NOT NULL,
    created_at   TIMESTAMPTZ    NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_entry_amount
        CHECK (amount > 0),

    CONSTRAINT chk_entry_type
        CHECK (entry_type IN ('DEBIT', 'CREDIT')),

    CONSTRAINT uq_transfer_account_entry
        UNIQUE (transfer_id, account_id, entry_type)
);

CREATE INDEX idx_ledger_entries_account ON ledger_entries (account_id, created_at, id);
CREATE INDEX idx_ledger_entries_transfer ON ledger_entries (transfer_id);

-- ---------------------------------------------------------------------------
-- Immutability (spec 4.4): committed transfers and ledger entries are never
-- edited or deleted. Corrections must be made with compensating transfers.
-- ---------------------------------------------------------------------------

CREATE FUNCTION ledger_forbid_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% on % is not allowed: ledger records are immutable', TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER trg_ledger_entries_immutable
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION ledger_forbid_mutation();

CREATE TRIGGER trg_transfers_immutable
    BEFORE UPDATE OR DELETE ON transfers
    FOR EACH ROW EXECUTE FUNCTION ledger_forbid_mutation();

-- ---------------------------------------------------------------------------
-- Balanced double entry (spec 4.1 / 5.3 "Important implementation detail").
--
-- Row-level CHECK constraints cannot compare rows, so a DEFERRABLE INITIALLY
-- DEFERRED constraint trigger re-validates every touched transfer at COMMIT:
--   * a COMPLETED transfer has exactly two entries:
--       DEBIT  of `amount` on the source account
--       CREDIT of `amount` on the destination account
--   * a FAILED transfer has no entries.
-- A transaction that leaves a one-sided or unbalanced posting cannot commit.
-- ---------------------------------------------------------------------------

CREATE FUNCTION ledger_assert_transfer_balanced() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    v_transfer_id  UUID;
    v_transfer     transfers%ROWTYPE;
    v_entry_count  INTEGER;
    v_debits       NUMERIC(19, 2);
    v_credits      NUMERIC(19, 2);
    v_debit_ok     BOOLEAN;
    v_credit_ok    BOOLEAN;
BEGIN
    IF TG_TABLE_NAME = 'transfers' THEN
        v_transfer_id := NEW.id;
    ELSE
        v_transfer_id := NEW.transfer_id;
    END IF;

    SELECT * INTO v_transfer FROM transfers WHERE id = v_transfer_id;
    IF NOT FOUND THEN
        RETURN NULL; -- unreachable because of the foreign key, kept for safety
    END IF;

    SELECT COUNT(*),
           COALESCE(SUM(amount) FILTER (WHERE entry_type = 'DEBIT'), 0),
           COALESCE(SUM(amount) FILTER (WHERE entry_type = 'CREDIT'), 0),
           COALESCE(BOOL_OR(entry_type = 'DEBIT'  AND account_id = v_transfer.source_account_id), FALSE),
           COALESCE(BOOL_OR(entry_type = 'CREDIT' AND account_id = v_transfer.destination_account_id), FALSE)
      INTO v_entry_count, v_debits, v_credits, v_debit_ok, v_credit_ok
      FROM ledger_entries
     WHERE transfer_id = v_transfer_id;

    IF v_transfer.status = 'COMPLETED' THEN
        IF v_entry_count <> 2
           OR v_debits <> v_transfer.amount
           OR v_credits <> v_transfer.amount
           OR NOT v_debit_ok
           OR NOT v_credit_ok THEN
            RAISE EXCEPTION 'unbalanced ledger for transfer %: entries=%, debits=%, credits=%, amount=%',
                v_transfer_id, v_entry_count, v_debits, v_credits, v_transfer.amount
                USING ERRCODE = 'check_violation';
        END IF;
    ELSIF v_entry_count <> 0 THEN
        RAISE EXCEPTION 'transfer % has status % but % ledger entries',
            v_transfer_id, v_transfer.status, v_entry_count
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_transfers_balanced
    AFTER INSERT ON transfers
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_assert_transfer_balanced();

CREATE CONSTRAINT TRIGGER trg_ledger_entries_balanced
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_assert_transfer_balanced();

-- ---------------------------------------------------------------------------
-- Balance projection integrity (spec 4.3):
--     accounts.balance = opening_balance + SUM(credits) - SUM(debits)
-- Checked at COMMIT for every account whose balance was inserted or changed,
-- so a balance can never drift from the ledger that justifies it.
-- ---------------------------------------------------------------------------

CREATE FUNCTION ledger_assert_balance_matches_ledger() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    v_account_id  UUID;
    v_current     accounts%ROWTYPE;
    v_movement    NUMERIC(19, 2);
BEGIN
    IF TG_TABLE_NAME = 'ledger_entries' THEN
        v_account_id := NEW.account_id;   -- a posting without a balance update is also caught
    ELSE
        v_account_id := NEW.id;
    END IF;

    -- Re-read the final row version: the trigger may fire for an intermediate version.
    SELECT * INTO v_current FROM accounts WHERE id = v_account_id;
    IF NOT FOUND THEN
        RETURN NULL;
    END IF;

    SELECT COALESCE(SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE -amount END), 0)
      INTO v_movement
      FROM ledger_entries
     WHERE account_id = v_account_id;

    IF v_current.balance <> v_current.opening_balance + v_movement THEN
        RAISE EXCEPTION 'balance of account % (%) does not match ledger (opening % + movements %)',
            v_account_id, v_current.balance, v_current.opening_balance, v_movement
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_accounts_balance_matches_ledger
    AFTER INSERT OR UPDATE OF balance ON accounts
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_assert_balance_matches_ledger();

CREATE CONSTRAINT TRIGGER trg_ledger_entries_balance_matches_ledger
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_assert_balance_matches_ledger();
