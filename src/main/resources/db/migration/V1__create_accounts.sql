-- V1: Accounts
--
-- Customer accounts are treated as ledger liabilities (spec 4.1).
-- `balance` is a materialized projection of the ledger:
--     balance = opening_balance + SUM(credits) - SUM(debits)
-- That equality is enforced at commit time by a deferred constraint trigger (V2).

CREATE TABLE accounts (
    id               UUID PRIMARY KEY,
    account_number   VARCHAR(64)    NOT NULL,
    currency         CHAR(3)        NOT NULL,
    opening_balance  NUMERIC(19, 2) NOT NULL DEFAULT 0,
    balance          NUMERIC(19, 2) NOT NULL DEFAULT 0,
    status           VARCHAR(16)    NOT NULL DEFAULT 'ACTIVE',
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_account_number
        UNIQUE (account_number),

    CONSTRAINT chk_account_currency
        CHECK (currency ~ '^[A-Z]{3}$'),

    CONSTRAINT chk_account_opening_balance
        CHECK (opening_balance >= 0),

    CONSTRAINT chk_account_balance
        CHECK (balance >= 0),

    CONSTRAINT chk_account_status
        CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED'))
);

-- The opening balance is part of the audit trail and must never change after creation.
CREATE FUNCTION accounts_protect_opening_balance() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.opening_balance IS DISTINCT FROM OLD.opening_balance
       OR NEW.currency IS DISTINCT FROM OLD.currency
       OR NEW.account_number IS DISTINCT FROM OLD.account_number THEN
        RAISE EXCEPTION 'accounts.opening_balance, currency and account_number are immutable (account %)', OLD.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_accounts_protect_opening_balance
    BEFORE UPDATE ON accounts
    FOR EACH ROW EXECUTE FUNCTION accounts_protect_opening_balance();

CREATE FUNCTION accounts_forbid_delete() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'accounts cannot be deleted; close the account instead (account %)', OLD.id
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER trg_accounts_forbid_delete
    BEFORE DELETE ON accounts
    FOR EACH ROW EXECUTE FUNCTION accounts_forbid_delete();
