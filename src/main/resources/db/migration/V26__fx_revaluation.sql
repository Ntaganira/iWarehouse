-- =====================================================================
-- V26: month-end revaluation of open foreign balances (ACC-08, unrealised
-- FX). What is owed in a foreign currency on Accounts Payable, Goods
-- Received Not Invoiced and Accrued Import Charges at a month's end is
-- revalued at that day's rate: the difference with the RWF it was booked
-- at goes to Unrealised FX Gain/Loss, and the journal is reversed the
-- next day, so the items keep their booked RWF and the gain or loss is
-- realised when they are paid. One revaluation per month (FXR).
-- =====================================================================

INSERT INTO accounts (code, name, account_type, system_key, description, created_by) VALUES
('5070', 'Unrealised FX Gain/Loss', 'EXPENSE', 'FX_UNREALISED',
 'Open foreign balances revalued at month end, reversed the next day (ACC-08)', 'system');

CREATE TABLE fx_revaluations (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version           BIGINT         NOT NULL DEFAULT 0,
    number            VARCHAR(30)    NOT NULL,          -- FXR-WH-2026-000001
    period_end        DATE           NOT NULL,          -- the month's last day: the journal's date
    gain_loss         NUMERIC(18,2)  NOT NULL,          -- the lines' total: positive a gain, negative a loss
    posted_at         TIMESTAMP      NOT NULL,
    posted_by         VARCHAR(50)    NOT NULL,
    created_at        TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by        VARCHAR(50),
    updated_at        TIMESTAMP,
    updated_by        VARCHAR(50),
    CONSTRAINT uk_fx_revaluations_number UNIQUE (number),
    CONSTRAINT uk_fx_revaluations_period UNIQUE (period_end),
    CONSTRAINT chk_fx_revaluations_period CHECK (period_end = (date_trunc('month', period_end) + INTERVAL '1 month - 1 day')::date)
);

-- What was revalued: per account, supplier and currency, what was owed in it and in RWF as booked, the rate
-- applied (kept: rate, date, source) and the RWF at that rate. Owed amounts are credits less debits.
CREATE TABLE fx_revaluation_lines (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    revaluation_id    UUID           NOT NULL REFERENCES fx_revaluations(id),
    line_no           INTEGER        NOT NULL,
    account_id        UUID           NOT NULL REFERENCES accounts(id),
    supplier_id       UUID           REFERENCES suppliers(id),
    currency_code     VARCHAR(3)     NOT NULL REFERENCES currencies(code),
    fx_owed           NUMERIC(18,2)  NOT NULL,          -- in the currency
    base_owed         NUMERIC(18,2)  NOT NULL,          -- RWF as booked
    rate              NUMERIC(18,6)  NOT NULL,
    rate_date         DATE           NOT NULL,
    rate_source       VARCHAR(10)    NOT NULL,
    revalued_owed     NUMERIC(18,2)  NOT NULL,          -- RWF at the rate
    gain_loss         NUMERIC(18,2)  NOT NULL,          -- booked - revalued: positive a gain
    CONSTRAINT uk_fx_revaluation_lines_no UNIQUE (revaluation_id, line_no),
    CONSTRAINT chk_fx_revaluation_lines_gain CHECK (gain_loss = base_owed - revalued_owed)
);
CREATE INDEX idx_fx_revaluation_lines_revaluation ON fx_revaluation_lines (revaluation_id);
CREATE TRIGGER trg_fx_revaluation_lines_append_only BEFORE UPDATE OR DELETE ON fx_revaluation_lines
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_modification();

-- Reversing journals (the revaluation's, then manual journals): what reverses a journal
CREATE INDEX idx_journal_entries_reverses ON journal_entries (reverses_id) WHERE reverses_id IS NOT NULL;

ALTER TABLE journal_entries DROP CONSTRAINT chk_journal_entries_source;
ALTER TABLE journal_entries ADD CONSTRAINT chk_journal_entries_source CHECK (source_type IN ('GOODS_RECEIPT', 'SHIPMENT',
    'CLAIM_OPENED', 'CLAIM_SETTLED', 'CLAIM_REJECTED', 'CUTTING_JOB', 'ADJUSTMENT', 'OPENING_STOCK',
    'SALES_INVOICE', 'TILL_OPENED', 'TILL_CLOSED', 'SALES_DELIVERY', 'SALES_BALANCE', 'CREDIT_NOTE', 'CUSTOMER_PAYMENT',
    'SUPPLIER_INVOICE', 'SUPPLIER_PAYMENT', 'FX_REVALUATION'));

INSERT INTO number_sequences (doc_type, branch_code, prefix, created_by) VALUES
('FX_REVALUATION', 'WH', 'FXR', 'system');

-- ---------------------------------------------------------------------
-- Pages, permissions, grants (SRS 2.2: the accountant revalues at month
-- end; the owner and auditor read the revaluations)
-- ---------------------------------------------------------------------
INSERT INTO pages (code, name, module, path, icon, sort_order) VALUES
('FX_REVALUATIONS', 'FX Revaluations', 'Accounting', '/accounting/fx-revaluations', 'refresh-cw', 6);

INSERT INTO permissions (code, name, module, action, description) VALUES
('REVALUE_FX', 'Revalue Foreign Balances', 'Accounting', 'POST',
 'Revalue open foreign balances at a month''s closing rate (unrealised FX, reversed the next day)');

INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE p.code = 'FX_REVALUATIONS' AND r.code IN ('ADMIN', 'ACCOUNTANT', 'OWNER', 'AUDITOR')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE p.code = 'REVALUE_FX' AND r.code IN ('ADMIN', 'ACCOUNTANT')
ON CONFLICT DO NOTHING;
