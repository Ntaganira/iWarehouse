-- =====================================================================
-- V16: Accounting core (SRS 4.9: ACC-01, ACC-03, ACC-04; AT-10)
--
-- A chart of accounts seeded with the glass-business template. The
-- posting rules find their accounts by system key, so the accountant may
-- rename or renumber them; a system account stays active. Every business
-- event of the posting matrix (4.9.1) writes a journal in the same
-- transaction: lines in RWF (a foreign document's currency, amount and
-- rate kept on its line), append-only, and a journal that does not
-- balance is refused at commit. Stock lines carry their glass, so the
-- inventory account can be checked against the stock valuation per glass.
-- =====================================================================

CREATE TABLE accounts (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version       BIGINT       NOT NULL DEFAULT 0,
    code          VARCHAR(10)  NOT NULL,
    name          VARCHAR(80)  NOT NULL,
    account_type  VARCHAR(10)  NOT NULL,
    system_key    VARCHAR(30),                       -- what the posting rules call it (INVENTORY, GRNI...)
    description   VARCHAR(255),
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by    VARCHAR(50),
    updated_at    TIMESTAMP,
    updated_by    VARCHAR(50),
    CONSTRAINT uk_accounts_code UNIQUE (code),
    CONSTRAINT uk_accounts_system_key UNIQUE (system_key),
    CONSTRAINT chk_accounts_code CHECK (code ~ '^[0-9][0-9A-Z.-]{1,9}$'),
    CONSTRAINT chk_accounts_type CHECK (account_type IN ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE')),
    CONSTRAINT chk_accounts_system_enabled CHECK (system_key IS NULL OR enabled)
);

CREATE TABLE journal_entries (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    number         VARCHAR(30)   NOT NULL,           -- JV-WH-2026-000001
    entry_date     DATE          NOT NULL,
    source_type    VARCHAR(20)   NOT NULL,
    source_id      UUID,
    source_number  VARCHAR(30),
    description    VARCHAR(255)  NOT NULL,
    total          NUMERIC(18,2) NOT NULL,           -- the debits, equal to the credits
    reverses_id    UUID          REFERENCES journal_entries(id),
    posted_at      TIMESTAMP     NOT NULL,
    user_id        BIGINT,
    username       VARCHAR(50)   NOT NULL,
    CONSTRAINT uk_journal_entries_number UNIQUE (number),
    CONSTRAINT chk_journal_entries_source CHECK (source_type IN ('GOODS_RECEIPT', 'SHIPMENT', 'CLAIM_OPENED', 'CLAIM_SETTLED',
        'CLAIM_REJECTED', 'CUTTING_JOB', 'ADJUSTMENT', 'OPENING_STOCK')),
    CONSTRAINT chk_journal_entries_total CHECK (total > 0)
);
CREATE INDEX idx_journal_entries_date ON journal_entries (entry_date);
CREATE INDEX idx_journal_entries_source ON journal_entries (source_type, source_id);
-- The ledger starts once, from the stock value of that moment
CREATE UNIQUE INDEX uk_journal_entries_opening_stock ON journal_entries (source_type) WHERE source_type = 'OPENING_STOCK';

CREATE TABLE journal_lines (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entry_id       UUID          NOT NULL REFERENCES journal_entries(id),
    line_no        INTEGER       NOT NULL,
    account_id     UUID          NOT NULL REFERENCES accounts(id),
    debit          NUMERIC(18,2) NOT NULL DEFAULT 0,
    credit         NUMERIC(18,2) NOT NULL DEFAULT 0,
    memo           VARCHAR(255),
    product_id     UUID          REFERENCES products(id),   -- stock lines: the glass (AT-10 per glass)
    supplier_id    UUID          REFERENCES suppliers(id),  -- payable lines
    currency_code  VARCHAR(3),                              -- ACC-01: a foreign document's currency,
    fx_amount      NUMERIC(18,2),                           -- its amount
    rate           NUMERIC(18,6),                           -- and the rate it was posted at
    CONSTRAINT uk_journal_lines_no UNIQUE (entry_id, line_no),
    CONSTRAINT chk_journal_lines_amount CHECK (debit >= 0 AND credit >= 0 AND (debit = 0) <> (credit = 0)),
    CONSTRAINT chk_journal_lines_fx CHECK ((currency_code IS NULL) = (fx_amount IS NULL) AND (currency_code IS NULL) = (rate IS NULL))
);
CREATE INDEX idx_journal_lines_entry ON journal_lines (entry_id);
CREATE INDEX idx_journal_lines_account ON journal_lines (account_id);
CREATE INDEX idx_journal_lines_product ON journal_lines (product_id) WHERE product_id IS NOT NULL;

-- Posted journals are never changed: corrections are new journals
CREATE TRIGGER trg_journal_entries_append_only BEFORE UPDATE OR DELETE ON journal_entries
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_modification();
CREATE TRIGGER trg_journal_lines_append_only BEFORE UPDATE OR DELETE ON journal_lines
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_modification();

-- Debits = credits = the journal's total, at least two lines (checked at commit, once all lines are in)
CREATE OR REPLACE FUNCTION check_journal_balanced() RETURNS TRIGGER AS $$
DECLARE
    eid UUID;
    t   NUMERIC;
    d   NUMERIC;
    c   NUMERIC;
    n   INTEGER;
BEGIN
    IF TG_TABLE_NAME = 'journal_entries' THEN
        eid := NEW.id;
    ELSE
        eid := NEW.entry_id;
    END IF;
    SELECT total INTO t FROM journal_entries WHERE id = eid;
    SELECT COALESCE(SUM(debit), 0), COALESCE(SUM(credit), 0), COUNT(*) INTO d, c, n FROM journal_lines WHERE entry_id = eid;
    IF n < 2 OR d <> c OR d <> t THEN
        RAISE EXCEPTION 'Journal % does not balance: debits %, credits %, total %, % line(s)', eid, d, c, t, n;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_journal_entries_balanced AFTER INSERT ON journal_entries
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_journal_balanced();
CREATE CONSTRAINT TRIGGER trg_journal_lines_balanced AFTER INSERT ON journal_lines
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_journal_balanced();

-- Where the money of a settled claim went (PRC-06): it decides the account debited
ALTER TABLE shipments ADD COLUMN claim_received_into VARCHAR(20);
ALTER TABLE shipments ADD CONSTRAINT chk_shipments_claim_received_into
    CHECK (claim_received_into IS NULL OR claim_received_into IN ('BANK', 'CASH', 'MOBILE_MONEY', 'PAYABLE'));

-- ---------------------------------------------------------------------
-- Default chart of accounts for a glass business (ACC-03, SRS 4.9.1)
-- ---------------------------------------------------------------------
INSERT INTO accounts (code, name, account_type, system_key, description, created_by) VALUES
('1010', 'Cash on Hand',                 'ASSET',     'CASH',              'Counter tills', 'system'),
('1020', 'Main Cash Vault',              'ASSET',     'CASH_VAULT',        'Cash kept at the office; driver floats are cleared into it', 'system'),
('1030', 'Bank',                         'ASSET',     'BANK',              NULL, 'system'),
('1040', 'Mobile Money',                 'ASSET',     'MOBILE_MONEY',      NULL, 'system'),
('1050', 'Driver Float',                 'ASSET',     'DRIVER_FLOAT',      'Cash of mobile sales until the accountant clears it (ACC-06)', 'system'),
('1100', 'Accounts Receivable',          'ASSET',     'RECEIVABLE',        NULL, 'system'),
('1110', 'Claims Receivable',            'ASSET',     'CLAIMS',            'Claims for glass broken on arrival (PRC-06)', 'system'),
('1120', 'Driver Shortage Receivable',   'ASSET',     'DRIVER_SHORTAGE',   'Cash short at float clearance (ACC-07)', 'system'),
('1130', 'VAT Input',                    'ASSET',     'VAT_INPUT',         'VAT paid on purchases, recoverable', 'system'),
('1200', 'Inventory - Glass',            'ASSET',     'INVENTORY',         'Glass in stock at moving average cost; equals the stock valuation (AT-10)', 'system'),
('2010', 'Accounts Payable',             'LIABILITY', 'PAYABLE',           'Owed to suppliers; each line keeps its currency and rate', 'system'),
('2020', 'Goods Received Not Invoiced',  'LIABILITY', 'GRNI',              'Glass received, supplier invoice not entered yet', 'system'),
('2030', 'Accrued Import Charges',       'LIABILITY', 'IMPORT_ACCRUAL',    'Import bills without a supplier, until their payment is recorded', 'system'),
('2040', 'Customer Deposits',            'LIABILITY', 'CUSTOMER_DEPOSITS', NULL, 'system'),
('2050', 'VAT Output Payable',           'LIABILITY', 'VAT_OUTPUT',        NULL, 'system'),
('3010', 'Owner''s Capital',             'EQUITY',    NULL,                NULL, 'system'),
('3020', 'Retained Earnings',            'EQUITY',    'RETAINED_EARNINGS', NULL, 'system'),
('3030', 'Opening Balance Equity',       'EQUITY',    'OPENING_EQUITY',    'Balances brought in when the ledger starts', 'system'),
('4010', 'Sales Revenue',                'REVENUE',   'SALES',             NULL, 'system'),
('4020', 'Sales Returns',                'REVENUE',   'SALES_RETURNS',     'Reduces sales revenue', 'system'),
('5010', 'Cost of Goods Sold',           'EXPENSE',   'COGS',              'Glass sold at moving average cost, and import costs of glass already gone', 'system'),
('5020', 'Glass Spoilage Expense',       'EXPENSE',   'SPOILAGE',          'Cullet, breakage, glass broken on arrival less what claims recover', 'system'),
('5030', 'Inventory Adjustment Expense', 'EXPENSE',   'STOCK_ADJUSTMENT',  'Glass missing, found again, added or resized', 'system'),
('5040', 'Inventory Revaluation',        'EXPENSE',   'STOCK_REVALUATION', 'Rounding of the moving average cost and revaluations of stock held', 'system'),
('5050', 'Cash Over/Short',              'EXPENSE',   'CASH_OVER_SHORT',   NULL, 'system'),
('5060', 'FX Gain/Loss',                 'EXPENSE',   'FX_GAIN_LOSS',      'Realised exchange differences (ACC-08)', 'system');

-- ---------------------------------------------------------------------
-- Pages, permissions, grants (SRS 2.2: the accountant keeps the ledgers;
-- the owner and auditor read them)
-- ---------------------------------------------------------------------
UPDATE pages SET name = 'Journals', path = '/accounting/journals' WHERE code = 'ACCOUNTING';

INSERT INTO pages (code, name, module, path, icon, sort_order) VALUES
('ACCOUNTS',      'Chart of Accounts', 'Accounting', '/accounting/accounts',      'list',  9),
('TRIAL_BALANCE', 'Trial Balance',     'Accounting', '/accounting/trial-balance', 'scale', 9);

INSERT INTO permissions (code, name, module, action, description) VALUES
('VIEW_ACCOUNTING',       'View Accounting',       'Accounting', 'VIEW',   'View the chart of accounts, journals and trial balance'),
('MANAGE_ACCOUNTS',       'Manage Accounts',       'Accounting', 'MANAGE', 'Add accounts, rename them, activate and deactivate them'),
('POST_OPENING_BALANCES', 'Post Opening Balances', 'Accounting', 'POST',   'Start the ledger with the opening stock journal');

INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE r.code = 'ADMIN' AND p.code IN ('ACCOUNTS', 'TRIAL_BALANCE')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code = 'ADMIN' AND p.code IN ('VIEW_ACCOUNTING', 'MANAGE_ACCOUNTS', 'POST_OPENING_BALANCES')
ON CONFLICT DO NOTHING;

INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE p.code IN ('ACCOUNTING', 'ACCOUNTS', 'TRIAL_BALANCE') AND r.code IN ('ACCOUNTANT', 'OWNER', 'AUDITOR')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE (p.code = 'VIEW_ACCOUNTING' AND r.code IN ('ACCOUNTANT', 'OWNER', 'AUDITOR'))
   OR (p.code IN ('MANAGE_ACCOUNTS', 'POST_OPENING_BALANCES') AND r.code = 'ACCOUNTANT')
ON CONFLICT DO NOTHING;
