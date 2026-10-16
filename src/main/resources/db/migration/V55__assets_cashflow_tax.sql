-- =====================================================================
-- V55: fixed assets, the cash-flow forecast, withholding tax and the delivery schedule (M18 of the coverage plan, second
-- half: Cost Management & Expenditure Control Policy 05, 10; BP 5.1; F-15; IMP 5.8; not in SRS v2.1)
--   - the fixed-asset register, monthly depreciation and disposals (C.08, U.12)
--   - the rolling cash-flow forecast and the weekly cash report (P.11)
--   - withholding tax on expense bills, its monthly return and the income tax estimate (U.14)
--   - delivery notes planned for a day against the warehouse's daily capacity (G.23)
-- =====================================================================

INSERT INTO accounts (code, name, account_type, system_key, description, created_by) VALUES
('1510', 'Accumulated Depreciation',              'ASSET',     'ACCUMULATED_DEPRECIATION', 'Depreciation of the fixed assets to date (a credit balance)', 'system'),
('2150', 'Withholding Tax Payable',               'LIABILITY', 'WHT_PAYABLE',              'Tax withheld from suppliers, owed to RRA', 'system'),
('6500', 'Depreciation',                          'EXPENSE',   'DEPRECIATION',             'The month''s depreciation of the fixed assets', 'system'),
('6510', 'Loss (Gain) on Disposal of Assets',     'EXPENSE',   'ASSET_DISPOSAL',           'What a fixed asset fetched against its book value', 'system');

-- ---------------------------------------------------------------------
-- The fixed-asset register (C.08): from capital expense bills, or registered at their value to date
-- ---------------------------------------------------------------------
CREATE TABLE fixed_assets (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version                BIGINT        NOT NULL DEFAULT 0,
    number                 VARCHAR(30)   NOT NULL,           -- FA-WH-00001
    name                   VARCHAR(150)  NOT NULL,
    category               VARCHAR(15)   NOT NULL,
    source                 VARCHAR(12)   NOT NULL,
    expense_bill_line_id   UUID          REFERENCES expense_bill_lines(id),
    cost_centre_id         UUID          REFERENCES cost_centres(id),
    location_id            UUID          REFERENCES locations(id),
    serial_number          VARCHAR(60),
    acquired_on            DATE          NOT NULL,
    cost                   NUMERIC(18,2) NOT NULL,
    residual_value         NUMERIC(18,2) NOT NULL DEFAULT 0,
    useful_life_months     INTEGER       NOT NULL,
    depreciate_from        DATE          NOT NULL,           -- the first month depreciated
    opening_accumulated    NUMERIC(18,2) NOT NULL DEFAULT 0, -- depreciation before the register (opening assets)
    opening_journal_id     UUID          REFERENCES journal_entries(id),
    status                 VARCHAR(10)   NOT NULL DEFAULT 'ACTIVE',   -- CANCELLED: the bill it came from was reversed
    disposed_on            DATE,
    proceeds               NUMERIC(18,2),
    proceeds_method        VARCHAR(15),
    proceeds_reference     VARCHAR(60),
    disposal_reason        VARCHAR(500),
    disposed_by            VARCHAR(50),
    disposal_journal_id    UUID          REFERENCES journal_entries(id),
    created_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by             VARCHAR(50),
    updated_at             TIMESTAMP,
    updated_by             VARCHAR(50),
    CONSTRAINT uk_fixed_assets_number UNIQUE (number),
    CONSTRAINT uk_fixed_assets_bill_line UNIQUE (expense_bill_line_id),
    CONSTRAINT chk_fixed_assets_category CHECK (category IN ('BUILDINGS', 'VEHICLES', 'MACHINERY', 'FURNITURE', 'IT_EQUIPMENT', 'OTHER')),
    CONSTRAINT chk_fixed_assets_source CHECK (source IN ('EXPENSE_BILL', 'OPENING')),
    CONSTRAINT chk_fixed_assets_status CHECK (status IN ('ACTIVE', 'DISPOSED', 'CANCELLED')),
    CONSTRAINT chk_fixed_assets_amounts CHECK (cost > 0 AND residual_value >= 0 AND residual_value < cost AND opening_accumulated >= 0
        AND opening_accumulated <= cost - residual_value AND useful_life_months > 0),
    CONSTRAINT chk_fixed_assets_method CHECK (proceeds_method IS NULL OR proceeds_method IN ('CASH_VAULT', 'BANK', 'MOBILE_MONEY')),
    CONSTRAINT chk_fixed_assets_disposed CHECK (status <> 'DISPOSED' OR (disposed_on IS NOT NULL AND disposal_reason IS NOT NULL
        AND disposal_journal_id IS NOT NULL))
);
CREATE INDEX idx_fixed_assets_status ON fixed_assets (status);

-- A month's depreciation run (once per month) and each asset's share (append-only)
CREATE TABLE depreciation_runs (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version          BIGINT        NOT NULL DEFAULT 0,
    number           VARCHAR(30)   NOT NULL,               -- DEP-WH-2026-000001
    period           DATE          NOT NULL,               -- the month's first day
    total            NUMERIC(18,2) NOT NULL,
    assets           INTEGER       NOT NULL,
    status           VARCHAR(10)   NOT NULL DEFAULT 'POSTED',
    run_by           VARCHAR(50)   NOT NULL,
    run_at           TIMESTAMP     NOT NULL,
    journal_id       UUID          REFERENCES journal_entries(id),   -- set once its journal is posted, in the same transaction
    reversal_journal_id UUID       REFERENCES journal_entries(id),
    reversal_reason  VARCHAR(500),
    reversed_by      VARCHAR(50),
    reversed_at      TIMESTAMP,
    created_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by       VARCHAR(50),
    updated_at       TIMESTAMP,
    updated_by       VARCHAR(50),
    CONSTRAINT uk_depreciation_runs_number UNIQUE (number),
    CONSTRAINT chk_depreciation_runs_status CHECK (status IN ('POSTED', 'REVERSED')),
    CONSTRAINT chk_depreciation_runs_reversed CHECK (status <> 'REVERSED' OR (reversal_journal_id IS NOT NULL AND reversal_reason IS NOT NULL))
);
CREATE UNIQUE INDEX uk_depreciation_runs_period ON depreciation_runs (period) WHERE status = 'POSTED';

CREATE TABLE depreciation_lines (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version          BIGINT        NOT NULL DEFAULT 0,
    run_id           UUID          NOT NULL REFERENCES depreciation_runs(id),
    asset_id         UUID          NOT NULL REFERENCES fixed_assets(id),
    amount           NUMERIC(18,2) NOT NULL,
    accumulated_after NUMERIC(18,2) NOT NULL,
    created_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by       VARCHAR(50),
    updated_at       TIMESTAMP,
    updated_by       VARCHAR(50),
    CONSTRAINT uk_depreciation_lines UNIQUE (run_id, asset_id),
    CONSTRAINT chk_depreciation_lines_amount CHECK (amount > 0)
);
CREATE INDEX idx_depreciation_lines_asset ON depreciation_lines (asset_id);
CREATE TRIGGER trg_depreciation_lines_append_only BEFORE UPDATE OR DELETE ON depreciation_lines
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_modification();

-- ---------------------------------------------------------------------
-- The cash-flow forecast's planned items (P.11): money expected in or out that no document carries yet
-- ---------------------------------------------------------------------
CREATE TABLE cash_forecast_items (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version          BIGINT        NOT NULL DEFAULT 0,
    expected_on      DATE          NOT NULL,
    direction        VARCHAR(3)    NOT NULL,
    description      VARCHAR(200)  NOT NULL,
    amount           NUMERIC(18,2) NOT NULL,
    repeats          VARCHAR(10)   NOT NULL DEFAULT 'NONE',
    until            DATE,
    status           VARCHAR(10)   NOT NULL DEFAULT 'ACTIVE',
    created_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by       VARCHAR(50),
    updated_at       TIMESTAMP,
    updated_by       VARCHAR(50),
    CONSTRAINT chk_cash_forecast_items_direction CHECK (direction IN ('IN', 'OUT')),
    CONSTRAINT chk_cash_forecast_items_repeats CHECK (repeats IN ('NONE', 'WEEKLY', 'MONTHLY')),
    CONSTRAINT chk_cash_forecast_items_status CHECK (status IN ('ACTIVE', 'CANCELLED')),
    CONSTRAINT chk_cash_forecast_items_amount CHECK (amount > 0)
);

-- ---------------------------------------------------------------------
-- Withholding tax on expense bills (U.14): the supplier is owed the bill less the tax, RRA the tax
-- ---------------------------------------------------------------------
ALTER TABLE expense_bills
    ADD COLUMN wht_rate   NUMERIC(5,2)  NOT NULL DEFAULT 0,
    ADD COLUMN wht_amount NUMERIC(18,2) NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_expense_bills_wht CHECK (wht_rate >= 0 AND wht_rate <= 100 AND wht_amount >= 0 AND wht_amount <= net_amount);

-- ---------------------------------------------------------------------
-- Delivery notes planned for a day (G.23)
-- ---------------------------------------------------------------------
ALTER TABLE delivery_notes ADD COLUMN scheduled_on DATE;
UPDATE delivery_notes SET scheduled_on = prepared_at::date WHERE scheduled_on IS NULL;
CREATE INDEX idx_delivery_notes_scheduled ON delivery_notes (scheduled_on);

ALTER TABLE journal_entries DROP CONSTRAINT chk_journal_entries_source;
ALTER TABLE journal_entries ADD CONSTRAINT chk_journal_entries_source CHECK (source_type IN
    ('GOODS_RECEIPT', 'SHIPMENT', 'CLAIM_OPENED', 'CLAIM_SETTLED', 'CLAIM_REJECTED', 'CUTTING_JOB', 'ADJUSTMENT',
     'OPENING_STOCK', 'SALES_INVOICE', 'TILL_OPENED', 'TILL_CLOSED', 'SALES_DELIVERY', 'SALES_BALANCE', 'CREDIT_NOTE',
     'CUSTOMER_PAYMENT', 'SUPPLIER_INVOICE', 'SUPPLIER_PAYMENT', 'FX_REVALUATION', 'MANUAL_JOURNAL', 'MOBILE_SALE',
     'TRIP_RETURN', 'AUDIT_CASE', 'FLOAT_CLEARANCE', 'PAYROLL', 'PAYROLL_PAYMENT', 'VEHICLE_COST', 'RECEIVABLE_WRITE_OFF',
     'CASH_DEPOSIT', 'TRIP_DEPOSIT', 'DAMAGE_RECOVERY', 'EXPENSE_BILL', 'PETTY_CASH_REPLENISHMENT', 'PETTY_CASH_VOUCHER',
     'PETTY_CASH_COUNT', 'STAFF_ADVANCE', 'ADVANCE_RETIREMENT', 'ASSET_OPENING', 'DEPRECIATION', 'ASSET_DISPOSAL'));

INSERT INTO number_sequences (doc_type, branch_code, prefix, reset_policy, padding, created_by) VALUES
('FIXED_ASSET',      'WH', 'FA',  'NEVER',  5, 'system'),
('DEPRECIATION_RUN', 'WH', 'DEP', 'YEARLY', 6, 'system');

INSERT INTO settings (setting_key, setting_value, created_by) VALUES
('tax.wht-rate',                 '15',    'system'),
('tax.cit-rate',                 '30',    'system'),
('delivery.daily-capacity-kg',   '8000',  'system'),
('delivery.daily-capacity-notes', '12',   'system'),
('cashflow.weeks',               '13',    'system');

-- ---------------------------------------------------------------------
-- Security
-- ---------------------------------------------------------------------
INSERT INTO pages (code, name, module, path, icon, sort_order) VALUES
('FIXED_ASSETS', 'Fixed Assets',  'Accounting', '/fixed-assets',      'archive',      34),
('CASH_FLOW',    'Cash Flow',     'Reports',    '/reports/cash-flow', 'trending-up',  52),
('TAX_RETURNS',  'Tax Returns',   'Reports',    '/reports/tax',       'file-text',    53);

INSERT INTO permissions (code, name, module, action, description) VALUES
('VIEW_FIXED_ASSETS',     'View Fixed Assets',      'Accounting', 'VIEW',    'View the fixed-asset register and the depreciation runs'),
('MANAGE_FIXED_ASSETS',   'Manage Fixed Assets',    'Accounting', 'UPDATE',  'Register an asset at its value to date and change an asset''s details'),
('RUN_DEPRECIATION',      'Run Depreciation',       'Accounting', 'CREATE',  'Post a month''s depreciation, or reverse the latest run with a reason'),
('DISPOSE_ASSET',         'Dispose of Assets',      'Accounting', 'UPDATE',  'Record a fixed asset sold or scrapped, with a reason'),
('MANAGE_CASH_FORECAST',  'Manage the Cash Forecast', 'Accounting', 'UPDATE', 'Add and cancel the planned items of the cash-flow forecast'),
('SCHEDULE_DELIVERY',     'Schedule Deliveries',    'Sales',      'UPDATE',  'Plan delivery notes for a day against the warehouse''s capacity');

INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE (p.code IN ('FIXED_ASSETS', 'CASH_FLOW', 'TAX_RETURNS') AND r.code IN ('ADMIN', 'OWNER', 'COO', 'DIRECTOR_FINANCE', 'REPORTING_TAX_BUDGET',
        'ACCOUNTANT', 'INTERNAL_CONTROLLER', 'AUDITOR'))
   OR (p.code = 'CASH_FLOW' AND r.code = 'TREASURY')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE (r.code = 'ADMIN' AND p.code IN ('VIEW_FIXED_ASSETS', 'MANAGE_FIXED_ASSETS', 'RUN_DEPRECIATION', 'DISPOSE_ASSET', 'MANAGE_CASH_FORECAST',
        'SCHEDULE_DELIVERY'))
   OR (r.code IN ('OWNER', 'COO', 'INTERNAL_CONTROLLER', 'AUDITOR', 'REPORTING_TAX_BUDGET') AND p.code = 'VIEW_FIXED_ASSETS')
   OR (r.code = 'DIRECTOR_FINANCE' AND p.code IN ('VIEW_FIXED_ASSETS', 'MANAGE_FIXED_ASSETS', 'RUN_DEPRECIATION', 'DISPOSE_ASSET', 'MANAGE_CASH_FORECAST'))
   OR (r.code = 'ACCOUNTANT' AND p.code IN ('VIEW_FIXED_ASSETS', 'MANAGE_FIXED_ASSETS', 'RUN_DEPRECIATION'))
   OR (r.code = 'TREASURY' AND p.code = 'MANAGE_CASH_FORECAST')
   OR (r.code IN ('DISPATCH_OFFICER', 'WAREHOUSE_MANAGER', 'DIRECTOR_SUPPLY_CHAIN', 'ORDER_DESK') AND p.code = 'SCHEDULE_DELIVERY')
ON CONFLICT DO NOTHING;
