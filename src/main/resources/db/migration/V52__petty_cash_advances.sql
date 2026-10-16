-- =====================================================================
-- V52: petty cash, per diem and staff advances (M17 of the coverage plan, second half: Cost Management & Expenditure
-- Control Policy 05, 07, 08, 10; Forms Manual F-13 D, F-16; Board Paper 5.1; not in SRS v2.1)
--   - petty cash funds per site (imprest), topped up from the vault or the bank, vouchers with their receipt approved
--     by another person, daily counts by the custodian and surprise counts by Finance (S.01-S.04, P.07)
--   - per diem rates by category (S.05)
--   - staff advances approved by the Managing Director, retired with receipts within ten working days, no new advance
--     while one is open, overdue ones recovered from salary (S.06)
--   - employees and their pay by cost centre (F-15 A)
-- =====================================================================

-- ---------------------------------------------------------------------
-- The petty cash account: a fund's balance is its site's lines on it
-- ---------------------------------------------------------------------
INSERT INTO accounts (code, name, account_type, system_key, description, created_by) VALUES
('1060', 'Petty Cash', 'ASSET', 'PETTY_CASH', 'The petty cash imprest of each site: its lines name the site', 'system');

-- ---------------------------------------------------------------------
-- Petty cash funds (CMP 07: a float per location, a custodian)
-- ---------------------------------------------------------------------
CREATE TABLE petty_cash_funds (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version            BIGINT        NOT NULL DEFAULT 0,
    code               VARCHAR(20)   NOT NULL,               -- PC-WH
    location_id        UUID          NOT NULL REFERENCES locations(id),
    custodian_user_id  BIGINT        NOT NULL REFERENCES users(id),
    custodian_name     VARCHAR(50)   NOT NULL,
    imprest            NUMERIC(18,2) NOT NULL,
    status             VARCHAR(10)   NOT NULL DEFAULT 'ACTIVE',
    opened_by          VARCHAR(50)   NOT NULL,
    opened_at          TIMESTAMP     NOT NULL,
    closed_by          VARCHAR(50),
    closed_at          TIMESTAMP,
    close_reason       VARCHAR(500),
    close_journal_id   UUID          REFERENCES journal_entries(id),
    created_at         TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by         VARCHAR(50),
    updated_at         TIMESTAMP,
    updated_by         VARCHAR(50),
    CONSTRAINT uk_petty_cash_funds_code UNIQUE (code),
    CONSTRAINT chk_petty_cash_funds_status CHECK (status IN ('ACTIVE', 'CLOSED')),
    CONSTRAINT chk_petty_cash_funds_imprest CHECK (imprest > 0),
    CONSTRAINT chk_petty_cash_funds_closed CHECK (status <> 'CLOSED' OR (closed_at IS NOT NULL AND close_reason IS NOT NULL))
);
-- One fund in use per site
CREATE UNIQUE INDEX uk_petty_cash_funds_site ON petty_cash_funds (location_id) WHERE status = 'ACTIVE';

-- Money into the fund (top up) and back out of it (returned when it closes): posted when saved, never changed
CREATE TABLE petty_cash_replenishments (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version          BIGINT        NOT NULL DEFAULT 0,
    number           VARCHAR(30)   NOT NULL,                 -- PCR-WH-2026-000001
    fund_id          UUID          NOT NULL REFERENCES petty_cash_funds(id),
    kind             VARCHAR(10)   NOT NULL,
    source           VARCHAR(12)   NOT NULL,
    reference        VARCHAR(60),
    amount           NUMERIC(18,2) NOT NULL,
    txn_date         DATE          NOT NULL,
    balance_before   NUMERIC(18,2) NOT NULL,
    made_by          VARCHAR(50)   NOT NULL,
    made_by_id       BIGINT,
    made_at          TIMESTAMP     NOT NULL,
    journal_id       UUID          NOT NULL REFERENCES journal_entries(id),
    created_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by       VARCHAR(50),
    updated_at       TIMESTAMP,
    updated_by       VARCHAR(50),
    CONSTRAINT uk_petty_cash_replenishments_number UNIQUE (number),
    CONSTRAINT chk_petty_cash_replenishments_kind CHECK (kind IN ('TOP_UP', 'RETURN')),
    CONSTRAINT chk_petty_cash_replenishments_source CHECK (source IN ('CASH_VAULT', 'BANK')),
    CONSTRAINT chk_petty_cash_replenishments_amount CHECK (amount > 0)
);
CREATE INDEX idx_petty_cash_replenishments_fund ON petty_cash_replenishments (fund_id, txn_date);
CREATE TRIGGER trg_petty_cash_replenishments_append_only BEFORE UPDATE OR DELETE ON petty_cash_replenishments
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_modification();

-- Payments from the fund (CMP 07, F-13 D: an approved voucher and the original receipt)
CREATE TABLE petty_cash_vouchers (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version              BIGINT        NOT NULL DEFAULT 0,
    number               VARCHAR(30)   NOT NULL,             -- PCV-WH-2026-000001
    fund_id              UUID          NOT NULL REFERENCES petty_cash_funds(id),
    voucher_date         DATE          NOT NULL,
    payee                VARCHAR(100)  NOT NULL,
    purpose              VARCHAR(255)  NOT NULL,
    category             VARCHAR(30)   NOT NULL,
    cost_centre_id       UUID          NOT NULL REFERENCES cost_centres(id),
    amount               NUMERIC(18,2) NOT NULL,
    receipt_ref          VARCHAR(60),
    status               VARCHAR(20)   NOT NULL DEFAULT 'DRAFT',
    recorded_by          VARCHAR(50)   NOT NULL,
    recorded_by_id       BIGINT,
    recorded_at          TIMESTAMP     NOT NULL,
    submitted_at         TIMESTAMP,
    decided_by           VARCHAR(50),
    decided_by_id        BIGINT,
    decided_at           TIMESTAMP,
    decision_note        VARCHAR(500),
    journal_id           UUID          REFERENCES journal_entries(id),
    posted_at            TIMESTAMP,
    reversal_journal_id  UUID          REFERENCES journal_entries(id),
    reversal_date        DATE,
    reversal_reason      VARCHAR(500),
    reversed_by          VARCHAR(50),
    reversed_at          TIMESTAMP,
    created_at           TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by           VARCHAR(50),
    updated_at           TIMESTAMP,
    updated_by           VARCHAR(50),
    CONSTRAINT uk_petty_cash_vouchers_number UNIQUE (number),
    CONSTRAINT chk_petty_cash_vouchers_status CHECK (status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'REJECTED', 'CANCELLED', 'REVERSED')),
    CONSTRAINT chk_petty_cash_vouchers_category CHECK (category IN ('UTILITIES', 'RENT', 'COMMUNICATION', 'PROFESSIONAL_FEES', 'REPAIRS',
        'TRANSPORT', 'TRAVEL', 'OFFICE', 'SECURITY_CLEANING', 'BANK_CHARGES', 'MARKETING', 'TRAINING', 'INSURANCE', 'TAXES_LICENCES',
        'OTHER')),
    CONSTRAINT chk_petty_cash_vouchers_amount CHECK (amount > 0),
    CONSTRAINT chk_petty_cash_vouchers_posted CHECK (status NOT IN ('APPROVED', 'REVERSED') OR (journal_id IS NOT NULL AND posted_at IS NOT NULL)),
    CONSTRAINT chk_petty_cash_vouchers_reason CHECK (status NOT IN ('REJECTED', 'CANCELLED') OR decision_note IS NOT NULL),
    CONSTRAINT chk_petty_cash_vouchers_reversed CHECK (status <> 'REVERSED' OR (reversal_journal_id IS NOT NULL AND reversal_reason IS NOT NULL))
);
CREATE INDEX idx_petty_cash_vouchers_fund ON petty_cash_vouchers (fund_id, voucher_date);
CREATE INDEX idx_petty_cash_vouchers_status ON petty_cash_vouchers (status);

-- Counts of the cash in the box: daily by the custodian, by surprise by Finance (CMP 07); a difference posts at once
CREATE TABLE petty_cash_counts (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version          BIGINT        NOT NULL DEFAULT 0,
    fund_id          UUID          NOT NULL REFERENCES petty_cash_funds(id),
    kind             VARCHAR(10)   NOT NULL,
    counted_on       DATE          NOT NULL,
    counted_by       VARCHAR(50)   NOT NULL,
    counted_by_id    BIGINT,
    counted_at       TIMESTAMP     NOT NULL,
    expected         NUMERIC(18,2) NOT NULL,
    counted          NUMERIC(18,2) NOT NULL,
    difference       NUMERIC(18,2) NOT NULL,
    note             VARCHAR(500),
    journal_id       UUID          REFERENCES journal_entries(id),
    created_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by       VARCHAR(50),
    updated_at       TIMESTAMP,
    updated_by       VARCHAR(50),
    CONSTRAINT chk_petty_cash_counts_kind CHECK (kind IN ('DAILY', 'SURPRISE')),
    CONSTRAINT chk_petty_cash_counts_amounts CHECK (counted >= 0 AND difference = counted - expected),
    CONSTRAINT chk_petty_cash_counts_note CHECK (difference = 0 OR (note IS NOT NULL AND journal_id IS NOT NULL))
);
CREATE INDEX idx_petty_cash_counts_fund ON petty_cash_counts (fund_id, counted_on);
CREATE TRIGGER trg_petty_cash_counts_append_only BEFORE UPDATE OR DELETE ON petty_cash_counts
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_modification();

-- ---------------------------------------------------------------------
-- Per diem rates (CMP 08): local, upcountry, regional and international, per day and per night
-- ---------------------------------------------------------------------
CREATE TABLE per_diem_rates (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version          BIGINT        NOT NULL DEFAULT 0,
    category         VARCHAR(15)   NOT NULL,
    currency_code    VARCHAR(3)    NOT NULL REFERENCES currencies(code),
    meal             NUMERIC(18,2) NOT NULL DEFAULT 0,
    accommodation    NUMERIC(18,2) NOT NULL DEFAULT 0,
    incidentals      NUMERIC(18,2) NOT NULL DEFAULT 0,
    created_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by       VARCHAR(50),
    updated_at       TIMESTAMP,
    updated_by       VARCHAR(50),
    CONSTRAINT uk_per_diem_rates_category UNIQUE (category),
    CONSTRAINT chk_per_diem_rates_category CHECK (category IN ('LOCAL', 'UPCOUNTRY', 'REGIONAL', 'INTERNATIONAL')),
    CONSTRAINT chk_per_diem_rates_amounts CHECK (meal >= 0 AND accommodation >= 0 AND incidentals >= 0)
);

INSERT INTO per_diem_rates (category, currency_code, meal, accommodation, incidentals, created_by) VALUES
('LOCAL',         'RWF', 10000, 0,     5000,  'system'),
('UPCOUNTRY',     'RWF', 25000, 40000, 10000, 'system'),
('REGIONAL',      'USD', 40,    80,    20,    'system'),
('INTERNATIONAL', 'USD', 60,    120,   30,    'system');

-- ---------------------------------------------------------------------
-- Staff advances (CMP 08): asked, approved by the MD, paid, retired with receipts or recovered from salary
-- ---------------------------------------------------------------------
CREATE TABLE staff_advances (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version              BIGINT        NOT NULL DEFAULT 0,
    number               VARCHAR(30)   NOT NULL,             -- ADV-WH-2026-000001
    employee_id          UUID          NOT NULL REFERENCES employees(id),
    kind                 VARCHAR(10)   NOT NULL,
    purpose              VARCHAR(255)  NOT NULL,
    cost_centre_id       UUID          NOT NULL REFERENCES cost_centres(id),
    per_diem_category    VARCHAR(15),
    destination          VARCHAR(100),
    depart_on            DATE,
    return_on            DATE,
    days                 INTEGER,
    nights               INTEGER,
    per_diem_currency    VARCHAR(3),
    per_diem_amount      NUMERIC(18,2),                      -- in the rate's currency
    rate                 NUMERIC(18,6),                      -- its rate to RWF, as applied
    rate_date            DATE,
    rate_source          VARCHAR(10),
    amount               NUMERIC(18,2) NOT NULL,             -- RWF: what is paid
    status               VARCHAR(12)   NOT NULL DEFAULT 'DRAFT',
    requested_by         VARCHAR(50)   NOT NULL,
    requested_by_id      BIGINT,
    requested_at         TIMESTAMP     NOT NULL,
    submitted_at         TIMESTAMP,
    decided_by           VARCHAR(50),
    decided_by_id        BIGINT,
    decided_at           TIMESTAMP,
    decision_note        VARCHAR(500),
    authority_level      VARCHAR(10),
    paid_by              VARCHAR(50),
    paid_by_id           BIGINT,
    paid_on              DATE,
    pay_method           VARCHAR(15),
    pay_fund_id          UUID          REFERENCES petty_cash_funds(id),
    pay_reference        VARCHAR(60),
    pay_journal_id       UUID          REFERENCES journal_entries(id),
    due_on               DATE,                               -- retired by this day
    retired_by           VARCHAR(50),
    retired_by_id        BIGINT,
    retired_on           DATE,
    retired_at           TIMESTAMP,
    spent                NUMERIC(18,2),
    settled              NUMERIC(18,2),                      -- returned (+) or paid back to the employee (-)
    settle_method        VARCHAR(15),
    settle_fund_id       UUID          REFERENCES petty_cash_funds(id),
    settle_reference     VARCHAR(60),
    retire_journal_id    UUID          REFERENCES journal_entries(id),
    recovered            NUMERIC(18,2) NOT NULL DEFAULT 0,   -- taken from salary by posted pay runs
    created_at           TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by           VARCHAR(50),
    updated_at           TIMESTAMP,
    updated_by           VARCHAR(50),
    CONSTRAINT uk_staff_advances_number UNIQUE (number),
    CONSTRAINT chk_staff_advances_kind CHECK (kind IN ('TRAVEL', 'OTHER')),
    CONSTRAINT chk_staff_advances_status CHECK (status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'PAID', 'RETIRED', 'RECOVERED', 'REJECTED',
        'CANCELLED')),
    CONSTRAINT chk_staff_advances_category CHECK (per_diem_category IS NULL OR per_diem_category IN ('LOCAL', 'UPCOUNTRY', 'REGIONAL',
        'INTERNATIONAL')),
    CONSTRAINT chk_staff_advances_method CHECK ((pay_method IS NULL OR pay_method IN ('CASH_VAULT', 'BANK', 'MOBILE_MONEY', 'PETTY_CASH'))
        AND (settle_method IS NULL OR settle_method IN ('CASH_VAULT', 'BANK', 'MOBILE_MONEY', 'PETTY_CASH'))),
    CONSTRAINT chk_staff_advances_amount CHECK (amount > 0 AND recovered >= 0 AND recovered <= amount),
    CONSTRAINT chk_staff_advances_travel CHECK (kind <> 'TRAVEL' OR (per_diem_category IS NOT NULL AND depart_on IS NOT NULL
        AND return_on IS NOT NULL AND return_on >= depart_on)),
    CONSTRAINT chk_staff_advances_decided CHECK (status IN ('DRAFT', 'SUBMITTED') OR decided_at IS NOT NULL),
    CONSTRAINT chk_staff_advances_reason CHECK (status NOT IN ('REJECTED', 'CANCELLED') OR decision_note IS NOT NULL),
    CONSTRAINT chk_staff_advances_paid CHECK (status NOT IN ('PAID', 'RETIRED', 'RECOVERED') OR (pay_journal_id IS NOT NULL
        AND paid_on IS NOT NULL AND due_on IS NOT NULL)),
    CONSTRAINT chk_staff_advances_retired CHECK (status <> 'RETIRED' OR (retire_journal_id IS NOT NULL AND retired_on IS NOT NULL
        AND spent IS NOT NULL AND settled IS NOT NULL)),
    CONSTRAINT chk_staff_advances_recovered CHECK (status <> 'RECOVERED' OR recovered = amount)
);
CREATE INDEX idx_staff_advances_employee ON staff_advances (employee_id, status);
CREATE INDEX idx_staff_advances_status ON staff_advances (status, due_on);

-- What the advance paid for, with the receipts: written once, when it is retired
CREATE TABLE staff_advance_lines (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version          BIGINT        NOT NULL DEFAULT 0,
    advance_id       UUID          NOT NULL REFERENCES staff_advances(id),
    line_no          INTEGER       NOT NULL,
    category         VARCHAR(30)   NOT NULL,
    description      VARCHAR(255)  NOT NULL,
    cost_centre_id   UUID          NOT NULL REFERENCES cost_centres(id),
    amount           NUMERIC(18,2) NOT NULL,
    receipt_ref      VARCHAR(60),
    created_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by       VARCHAR(50),
    updated_at       TIMESTAMP,
    updated_by       VARCHAR(50),
    CONSTRAINT uk_staff_advance_lines_no UNIQUE (advance_id, line_no),
    CONSTRAINT chk_staff_advance_lines_category CHECK (category IN ('UTILITIES', 'RENT', 'COMMUNICATION', 'PROFESSIONAL_FEES', 'REPAIRS',
        'TRANSPORT', 'TRAVEL', 'OFFICE', 'SECURITY_CLEANING', 'BANK_CHARGES', 'MARKETING', 'TRAINING', 'INSURANCE', 'TAXES_LICENCES',
        'OTHER')),
    CONSTRAINT chk_staff_advance_lines_amount CHECK (amount > 0)
);
CREATE TRIGGER trg_staff_advance_lines_append_only BEFORE UPDATE OR DELETE ON staff_advance_lines
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_modification();

-- A pay run line recovers an overdue advance (CMP 08); employees and their pay by cost centre (F-15 A)
ALTER TABLE pay_run_lines
    ADD COLUMN advance_id     UUID REFERENCES staff_advances(id),
    ADD COLUMN cost_centre_id UUID REFERENCES cost_centres(id);
ALTER TABLE employees ADD COLUMN cost_centre_id UUID REFERENCES cost_centres(id);

-- ---------------------------------------------------------------------
-- The Delegation of Authority: staff advances are approved by the Managing Director (CMP 08)
-- ---------------------------------------------------------------------
ALTER TABLE authority_limits DROP CONSTRAINT chk_authority_limits_decision;
ALTER TABLE authority_limits ADD CONSTRAINT chk_authority_limits_decision CHECK (decision IN ('DISCOUNT', 'BELOW_COST',
    'CREDIT_EXCEPTION', 'CREDIT_NOTE', 'RECEIVABLE_WRITE_OFF', 'STOCK_ADJUSTMENT', 'PURCHASE_ORDER', 'SUPPLIER_PAYMENT',
    'MANUAL_JOURNAL', 'VEHICLE_COST', 'CREDIT_LIMIT', 'PRICE_CHANGE', 'EXPENSE_OPEX', 'EXPENSE_CAPEX', 'EXPENSE_UNBUDGETED',
    'EXPENSE_EMERGENCY', 'STAFF_ADVANCE'));

INSERT INTO authority_limits (decision, level, allowed, max_amount, created_by) VALUES
('STAFF_ADVANCE', 'DIRECTOR', FALSE, NULL, 'system'),
('STAFF_ADVANCE', 'COO',      FALSE, NULL, 'system'),
('STAFF_ADVANCE', 'MD',       TRUE,  NULL, 'system'),
('STAFF_ADVANCE', 'BOARD',    TRUE,  NULL, 'system');

ALTER TABLE journal_entries DROP CONSTRAINT chk_journal_entries_source;
ALTER TABLE journal_entries ADD CONSTRAINT chk_journal_entries_source CHECK (source_type IN
    ('GOODS_RECEIPT', 'SHIPMENT', 'CLAIM_OPENED', 'CLAIM_SETTLED', 'CLAIM_REJECTED', 'CUTTING_JOB', 'ADJUSTMENT',
     'OPENING_STOCK', 'SALES_INVOICE', 'TILL_OPENED', 'TILL_CLOSED', 'SALES_DELIVERY', 'SALES_BALANCE', 'CREDIT_NOTE',
     'CUSTOMER_PAYMENT', 'SUPPLIER_INVOICE', 'SUPPLIER_PAYMENT', 'FX_REVALUATION', 'MANUAL_JOURNAL', 'MOBILE_SALE',
     'TRIP_RETURN', 'AUDIT_CASE', 'FLOAT_CLEARANCE', 'PAYROLL', 'PAYROLL_PAYMENT', 'VEHICLE_COST', 'RECEIVABLE_WRITE_OFF',
     'CASH_DEPOSIT', 'TRIP_DEPOSIT', 'DAMAGE_RECOVERY', 'EXPENSE_BILL', 'PETTY_CASH_REPLENISHMENT', 'PETTY_CASH_VOUCHER',
     'PETTY_CASH_COUNT', 'STAFF_ADVANCE', 'ADVANCE_RETIREMENT'));

ALTER TABLE attachments DROP CONSTRAINT chk_attachments_owner;
ALTER TABLE attachments ADD CONSTRAINT chk_attachments_owner CHECK (owner_type IN
    ('GOODS_RECEIPT', 'SHIPMENT', 'SUPPLIER_INVOICE', 'STOCK_ADJUSTMENT', 'VEHICLE', 'DRIVER', 'TRIP', 'VEHICLE_COST',
     'CREDIT_NOTE_REQUEST', 'RECEIVABLE_WRITE_OFF', 'CUSTOMER', 'SUPPLIER', 'CREDIT_APPLICATION', 'SITE_TRANSFER',
     'CUTTING_JOB', 'DELIVERY_NOTE', 'CASH_DEPOSIT', 'BALANCE_CONFIRMATION', 'DAILY_CLOSE', 'DAMAGE_REPORT', 'STOCK_COUNT',
     'STOCK_VARIANCE', 'EXPENSE_BILL', 'PURCHASE_REQUISITION', 'PETTY_CASH_VOUCHER', 'STAFF_ADVANCE'));

-- ---------------------------------------------------------------------
-- Numbers and settings (the policy's figures)
-- ---------------------------------------------------------------------
INSERT INTO number_sequences (doc_type, branch_code, prefix, reset_policy, padding, created_by) VALUES
('PETTY_CASH_VOUCHER',       'WH', 'PCV', 'YEARLY', 6, 'system'),
('PETTY_CASH_REPLENISHMENT', 'WH', 'PCR', 'YEARLY', 6, 'system'),
('STAFF_ADVANCE',            'WH', 'ADV', 'YEARLY', 6, 'system');

INSERT INTO settings (setting_key, setting_value, created_by) VALUES
('pettycash.max-float',          '200000', 'system'),
('pettycash.max-payment',        '50000',  'system'),
('pettycash.replenish-percent',  '25',     'system'),
('pettycash.receipt-required',   'true',   'system'),
('pettycash.audit-day',          '25',     'system'),
('advance.retire-days',          '10',     'system'),
('advance.block-open',           'true',   'system'),
('advance.receipts-required',    'true',   'system'),
('advance.payroll-recovery',     'true',   'system');

UPDATE permissions SET description = 'Be told of emergency spending not ratified in time, petty cash running low, cash count differences, surprise audits due and staff advances overdue'
WHERE code = 'ALERT_EXPENSE';

-- ---------------------------------------------------------------------
-- Security
-- ---------------------------------------------------------------------
INSERT INTO pages (code, name, module, path, icon, sort_order) VALUES
('PETTY_CASH',     'Petty Cash',     'Accounting', '/petty-cash',         'dollar-sign', 32),
('STAFF_ADVANCES', 'Staff Advances', 'Payroll',    '/payroll/advances',   'credit-card', 4),
('PER_DIEM',       'Per Diem Rates', 'Payroll',    '/payroll/per-diem',   'map',         5);

INSERT INTO permissions (code, name, module, action, description) VALUES
('VIEW_PETTY_CASH',      'View Petty Cash',          'Expenditure', 'VIEW',    'View petty cash funds, their vouchers, top-ups and counts'),
('MANAGE_PETTY_CASH',    'Manage Petty Cash Funds',  'Expenditure', 'UPDATE',  'Open a petty cash fund, change its custodian or float, close it'),
('REPLENISH_PETTY_CASH', 'Top Up Petty Cash',        'Expenditure', 'CREATE',  'Top a petty cash fund up from the vault or the bank'),
('RECORD_PETTY_CASH',    'Record Petty Cash',        'Expenditure', 'CREATE',  'Record petty cash vouchers and count the fund as its custodian'),
('APPROVE_PETTY_CASH',   'Approve Petty Cash',       'Expenditure', 'APPROVE', 'Approve or reject a petty cash voucher (head of department)'),
('AUDIT_PETTY_CASH',     'Audit Petty Cash',         'Expenditure', 'REVIEW',  'Count a petty cash fund by surprise (Finance)'),
('REVERSE_PETTY_CASH',   'Reverse Petty Cash',       'Expenditure', 'REVERSE', 'Reverse a posted petty cash voucher with a reason'),
('VIEW_ADVANCE',         'View Staff Advances',      'Payroll',     'VIEW',    'View staff advances and their retirement'),
('REQUEST_ADVANCE',      'Request Staff Advances',   'Payroll',     'CREATE',  'Ask for a staff advance for an employee (travel or other)'),
('APPROVE_ADVANCE',      'Approve Staff Advances',   'Payroll',     'APPROVE', 'Approve or reject a staff advance within the Delegation of Authority (the MD)'),
('PAY_ADVANCE',          'Pay Staff Advances',       'Payroll',     'CREATE',  'Pay an approved staff advance'),
('RETIRE_ADVANCE',       'Retire Staff Advances',    'Payroll',     'UPDATE',  'Record what an advance paid for, with the receipts, and the cash returned'),
('MANAGE_PER_DIEM',      'Manage Per Diem Rates',    'Payroll',     'UPDATE',  'Change the per diem rates of each travel category');

INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE (p.code IN ('PETTY_CASH', 'STAFF_ADVANCES', 'PER_DIEM') AND r.code IN ('ADMIN', 'OWNER', 'COO', 'DIRECTOR_FINANCE', 'ACCOUNTANT',
        'INTERNAL_CONTROLLER', 'AUDITOR', 'TREASURY'))
   OR (p.code = 'PETTY_CASH' AND r.code IN ('BOOKKEEPING_CASHIER', 'DIRECTOR_COMMERCIAL', 'DIRECTOR_SUPPLY_CHAIN', 'REPORTING_TAX_BUDGET'))
   OR (p.code IN ('STAFF_ADVANCES', 'PER_DIEM') AND r.code = 'REPORTING_TAX_BUDGET')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE (r.code = 'ADMIN' AND p.code IN ('VIEW_PETTY_CASH', 'MANAGE_PETTY_CASH', 'REPLENISH_PETTY_CASH', 'RECORD_PETTY_CASH',
        'APPROVE_PETTY_CASH', 'AUDIT_PETTY_CASH', 'REVERSE_PETTY_CASH', 'VIEW_ADVANCE', 'REQUEST_ADVANCE', 'APPROVE_ADVANCE',
        'PAY_ADVANCE', 'RETIRE_ADVANCE', 'MANAGE_PER_DIEM'))
   OR (r.code = 'OWNER' AND p.code IN ('VIEW_PETTY_CASH', 'APPROVE_PETTY_CASH', 'VIEW_ADVANCE', 'APPROVE_ADVANCE'))
   OR (r.code = 'COO' AND p.code IN ('VIEW_PETTY_CASH', 'APPROVE_PETTY_CASH', 'VIEW_ADVANCE'))
   OR (r.code = 'DIRECTOR_FINANCE' AND p.code IN ('VIEW_PETTY_CASH', 'MANAGE_PETTY_CASH', 'APPROVE_PETTY_CASH', 'AUDIT_PETTY_CASH',
        'REVERSE_PETTY_CASH', 'VIEW_ADVANCE', 'RETIRE_ADVANCE', 'MANAGE_PER_DIEM'))
   OR (r.code IN ('DIRECTOR_COMMERCIAL', 'DIRECTOR_SUPPLY_CHAIN') AND p.code IN ('VIEW_PETTY_CASH', 'APPROVE_PETTY_CASH'))
   OR (r.code = 'ACCOUNTANT' AND p.code IN ('VIEW_PETTY_CASH', 'AUDIT_PETTY_CASH', 'VIEW_ADVANCE', 'REQUEST_ADVANCE', 'RETIRE_ADVANCE'))
   OR (r.code = 'BOOKKEEPING_CASHIER' AND p.code IN ('VIEW_PETTY_CASH', 'RECORD_PETTY_CASH'))
   OR (r.code = 'TREASURY' AND p.code IN ('VIEW_PETTY_CASH', 'REPLENISH_PETTY_CASH', 'VIEW_ADVANCE', 'PAY_ADVANCE'))
   OR (r.code = 'REPORTING_TAX_BUDGET' AND p.code IN ('VIEW_PETTY_CASH', 'VIEW_ADVANCE'))
   OR (r.code IN ('INTERNAL_CONTROLLER', 'AUDITOR') AND p.code IN ('VIEW_PETTY_CASH', 'VIEW_ADVANCE'))
   OR (r.code = 'INTERNAL_CONTROLLER' AND p.code = 'AUDIT_PETTY_CASH')
ON CONFLICT DO NOTHING;
