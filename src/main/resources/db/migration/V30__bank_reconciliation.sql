-- =====================================================================
-- V30: bank and mobile-money reconciliation (ACC-12). The accountant
-- takes a statement of the Bank or Mobile Money account (its date and
-- closing balance) and ticks the ledger lines it shows: the previous
-- statement's balance plus the lines ticked must give the new balance.
-- A line is cleared once; what is not ticked is outstanding (deposits
-- and payments the bank has not shown yet). Saved reconciled, never
-- changed; the latest one of an account can be cancelled with a reason,
-- which clears its lines again.
-- =====================================================================

CREATE TABLE bank_reconciliations (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version               BIGINT         NOT NULL DEFAULT 0,
    number                VARCHAR(30)    NOT NULL,          -- REC-WH-2026-000001
    account_id            UUID           NOT NULL REFERENCES accounts(id),
    statement_date        DATE           NOT NULL,
    statement_balance     NUMERIC(18,2)  NOT NULL,          -- the statement's closing balance
    previous_balance      NUMERIC(18,2)  NOT NULL,          -- the previous statement's (0 for the first)
    cleared_amount        NUMERIC(18,2)  NOT NULL,          -- debits less credits of the lines ticked
    book_balance          NUMERIC(18,2)  NOT NULL,          -- the account's balance at the statement date
    outstanding_deposits  NUMERIC(18,2)  NOT NULL,          -- debits not on the statement
    outstanding_payments  NUMERIC(18,2)  NOT NULL,          -- credits not on the statement
    status                VARCHAR(12)    NOT NULL,          -- RECONCILED, CANCELLED
    notes                 VARCHAR(255),
    reconciled_by         VARCHAR(50)    NOT NULL,
    reconciled_at         TIMESTAMP      NOT NULL,
    cancelled_by          VARCHAR(50),
    cancelled_at          TIMESTAMP,
    cancel_reason         VARCHAR(255),
    created_at            TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by            VARCHAR(50),
    updated_at            TIMESTAMP,
    updated_by            VARCHAR(50),
    CONSTRAINT uk_bank_reconciliations_number UNIQUE (number),
    CONSTRAINT chk_bank_reconciliations_status CHECK (status IN ('RECONCILED', 'CANCELLED')),
    CONSTRAINT chk_bank_reconciliations_cleared CHECK (previous_balance + cleared_amount = statement_balance),
    CONSTRAINT chk_bank_reconciliations_book CHECK (book_balance - outstanding_deposits + outstanding_payments = statement_balance),
    CONSTRAINT chk_bank_reconciliations_outstanding CHECK (outstanding_deposits >= 0 AND outstanding_payments >= 0),
    CONSTRAINT chk_bank_reconciliations_cancelled CHECK ((status = 'CANCELLED') = (cancelled_by IS NOT NULL AND cancelled_at IS NOT NULL
        AND cancel_reason IS NOT NULL))
);
CREATE INDEX idx_bank_reconciliations_account ON bank_reconciliations (account_id, statement_date);
-- One reconciliation in force per account and statement date
CREATE UNIQUE INDEX uk_bank_reconciliations_account_date ON bank_reconciliations (account_id, statement_date) WHERE status = 'RECONCILED';

-- The ledger lines a reconciliation cleared
CREATE TABLE bank_reconciliation_lines (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reconciliation_id     UUID           NOT NULL REFERENCES bank_reconciliations(id),
    journal_line_id       UUID           NOT NULL REFERENCES journal_lines(id),
    debit                 NUMERIC(18,2)  NOT NULL,
    credit                NUMERIC(18,2)  NOT NULL,
    CONSTRAINT uk_bank_reconciliation_lines_line UNIQUE (reconciliation_id, journal_line_id)
);
CREATE INDEX idx_bank_reconciliation_lines_line ON bank_reconciliation_lines (journal_line_id);
CREATE TRIGGER trg_bank_reconciliation_lines_append_only BEFORE UPDATE OR DELETE ON bank_reconciliation_lines
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_modification();

INSERT INTO number_sequences (doc_type, branch_code, prefix, created_by) VALUES
('BANK_RECONCILIATION', 'WH', 'REC', 'system');

-- ---------------------------------------------------------------------
-- Pages, permissions, grants (SRS 2.2: the accountant reconciles; the
-- owner and auditor read the reconciliations)
-- ---------------------------------------------------------------------
INSERT INTO pages (code, name, module, path, icon, sort_order) VALUES
('RECONCILIATIONS', 'Reconciliations', 'Accounting', '/accounting/reconciliations', 'check-square', 8);

INSERT INTO permissions (code, name, module, action, description) VALUES
('RECONCILE_ACCOUNT', 'Reconcile Accounts', 'Accounting', 'RECONCILE',
 'Reconcile the bank and mobile-money accounts with their statements, or cancel the latest reconciliation');

INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE p.code = 'RECONCILIATIONS' AND r.code IN ('ADMIN', 'ACCOUNTANT', 'OWNER', 'AUDITOR')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE p.code = 'RECONCILE_ACCOUNT' AND r.code IN ('ADMIN', 'ACCOUNTANT')
ON CONFLICT DO NOTHING;
