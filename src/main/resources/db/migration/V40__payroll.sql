-- =====================================================================
-- V40: payroll (owner, 2026-10-11; not in SRS v2.1). Employees and their
-- salary, a pay run per month: gross pay, PAYE (RRA), RSSB pension,
-- occupational hazards and maternity, CBHI, other deductions, net pay;
-- payslips, the PAYE and RSSB declarations, and the journals: the run's
-- costs and what it owes when approved, the salaries when paid.
-- Rates are dated rows (payroll_rate_sets): a change in the law is a new
-- row from its date, never a code change. A posted run never changes: a
-- correction goes into the next run.
-- =====================================================================

-- ---------------------------------------------------------------------
-- The rates in force from a date (Law 027/2022 on income tax, RSSB)
-- ---------------------------------------------------------------------
CREATE TABLE payroll_rate_sets (
    id                         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version                    BIGINT         NOT NULL DEFAULT 0,
    effective_from             DATE           NOT NULL,
    -- PAYE on a month's taxable pay: rate1 up to band1, rate2 up to band2, rate3 up to band3, rate4 above
    paye_band1                 NUMERIC(18,2)  NOT NULL,
    paye_band2                 NUMERIC(18,2)  NOT NULL,
    paye_band3                 NUMERIC(18,2)  NOT NULL,
    paye_rate1                 NUMERIC(5,2)   NOT NULL,
    paye_rate2                 NUMERIC(5,2)   NOT NULL,
    paye_rate3                 NUMERIC(5,2)   NOT NULL,
    paye_rate4                 NUMERIC(5,2)   NOT NULL,
    paye_secondary_rate        NUMERIC(5,2)   NOT NULL,          -- a second employer withholds this rate on everything
    pension_employee_rate      NUMERIC(5,2)   NOT NULL,
    pension_employer_rate      NUMERIC(5,2)   NOT NULL,
    occupational_hazards_rate  NUMERIC(5,2)   NOT NULL,          -- the employer's alone
    maternity_employee_rate    NUMERIC(5,2)   NOT NULL,
    maternity_employer_rate    NUMERIC(5,2)   NOT NULL,
    cbhi_rate                  NUMERIC(5,2)   NOT NULL,          -- of the net pay before it
    transport_in_rssb_base     BOOLEAN        NOT NULL,          -- the transport allowance counts for RSSB contributions
    note                       VARCHAR(255),
    created_at                 TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by                 VARCHAR(50),
    updated_at                 TIMESTAMP,
    updated_by                 VARCHAR(50),
    CONSTRAINT uk_payroll_rate_sets_from UNIQUE (effective_from),
    CONSTRAINT chk_payroll_rate_sets_bands CHECK (paye_band1 >= 0 AND paye_band2 > paye_band1 AND paye_band3 > paye_band2),
    CONSTRAINT chk_payroll_rate_sets_rates CHECK (
        paye_rate1 BETWEEN 0 AND 100 AND paye_rate2 BETWEEN 0 AND 100 AND paye_rate3 BETWEEN 0 AND 100
        AND paye_rate4 BETWEEN 0 AND 100 AND paye_secondary_rate BETWEEN 0 AND 100
        AND pension_employee_rate BETWEEN 0 AND 100 AND pension_employer_rate BETWEEN 0 AND 100
        AND occupational_hazards_rate BETWEEN 0 AND 100 AND maternity_employee_rate BETWEEN 0 AND 100
        AND maternity_employer_rate BETWEEN 0 AND 100 AND cbhi_rate BETWEEN 0 AND 100)
);

-- PAYE bands of Law 027/2022 (RRA, monthly): 0% to 60,000, 10% to 100,000, 20% to 200,000, 30% above; a second
-- employer 30%. RSSB from January 2025: pension 6% + 6%, occupational hazards 2% (employer), maternity 0.3% + 0.3%;
-- CBHI 0.5% of the net pay. To confirm with the accountant before the first run (TODO, Payroll).
INSERT INTO payroll_rate_sets (effective_from, paye_band1, paye_band2, paye_band3, paye_rate1, paye_rate2, paye_rate3, paye_rate4,
    paye_secondary_rate, pension_employee_rate, pension_employer_rate, occupational_hazards_rate, maternity_employee_rate,
    maternity_employer_rate, cbhi_rate, transport_in_rssb_base, note, created_by) VALUES
('2025-01-01', 60000, 100000, 200000, 0, 10, 20, 30, 30, 6, 6, 2, 0.3, 0.3, 0.5, TRUE,
 'PAYE Law 027/2022; RSSB pension from January 2025; to confirm with the accountant', 'system');

-- ---------------------------------------------------------------------
-- Employees: who is paid, how much, into which account
-- ---------------------------------------------------------------------
CREATE TABLE employees (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version              BIGINT         NOT NULL DEFAULT 0,
    code                 VARCHAR(20)    NOT NULL,                -- EMP-WH-0001
    full_name            VARCHAR(100)   NOT NULL,
    user_id              BIGINT         REFERENCES users(id),    -- signs in: their payslips on My profile
    national_id          VARCHAR(16)    NOT NULL,                -- personal data (NFR-12)
    rssb_number          VARCHAR(20),
    job_title            VARCHAR(100),
    hired_on             DATE           NOT NULL,
    left_on              DATE,                                    -- the last day worked: not in runs after its month
    employment           VARCHAR(10)    NOT NULL,                -- PRIMARY or SECONDARY (PAYE)
    base_salary          NUMERIC(18,2)  NOT NULL,                -- a month
    transport_allowance  NUMERIC(18,2)  NOT NULL DEFAULT 0,
    housing_allowance    NUMERIC(18,2)  NOT NULL DEFAULT 0,
    other_allowance      NUMERIC(18,2)  NOT NULL DEFAULT 0,
    pay_method           VARCHAR(15)    NOT NULL,                -- BANK, MOBILE_MONEY, CASH
    bank_name            VARCHAR(60),
    account_number       VARCHAR(40),                            -- bank account or mobile-money number: personal data
    created_at           TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by           VARCHAR(50),
    updated_at           TIMESTAMP,
    updated_by           VARCHAR(50),
    CONSTRAINT uk_employees_code UNIQUE (code),
    CONSTRAINT uk_employees_user UNIQUE (user_id),
    CONSTRAINT uk_employees_national_id UNIQUE (national_id),
    CONSTRAINT chk_employees_national_id CHECK (national_id ~ '^[0-9]{16}$'),
    CONSTRAINT chk_employees_employment CHECK (employment IN ('PRIMARY', 'SECONDARY')),
    CONSTRAINT chk_employees_pay_method CHECK (pay_method IN ('BANK', 'MOBILE_MONEY', 'CASH')),
    CONSTRAINT chk_employees_account CHECK (pay_method = 'CASH' OR account_number IS NOT NULL),
    CONSTRAINT chk_employees_amounts CHECK (base_salary >= 0 AND transport_allowance >= 0 AND housing_allowance >= 0 AND other_allowance >= 0),
    CONSTRAINT chk_employees_left CHECK (left_on IS NULL OR left_on >= hired_on)
);
CREATE INDEX idx_employees_name ON employees (full_name);

-- ---------------------------------------------------------------------
-- Pay runs: one a month, prepared, approved by another person (posted), paid
-- ---------------------------------------------------------------------
CREATE TABLE pay_runs (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version               BIGINT         NOT NULL DEFAULT 0,
    number                VARCHAR(30)    NOT NULL,               -- PAY-WH-2026-000001
    period                DATE           NOT NULL,               -- the month's first day
    status                VARCHAR(12)    NOT NULL,               -- DRAFT, SUBMITTED, POSTED, PAID, CANCELLED
    rate_set_id           UUID           NOT NULL REFERENCES payroll_rate_sets(id),
    employees             INTEGER        NOT NULL DEFAULT 0,
    total_gross           NUMERIC(18,2)  NOT NULL DEFAULT 0,
    total_paye            NUMERIC(18,2)  NOT NULL DEFAULT 0,
    total_rssb_employee   NUMERIC(18,2)  NOT NULL DEFAULT 0,     -- pension and maternity, the employees' shares
    total_cbhi            NUMERIC(18,2)  NOT NULL DEFAULT 0,
    total_other           NUMERIC(18,2)  NOT NULL DEFAULT 0,     -- advances, driver shortages, other deductions
    total_net             NUMERIC(18,2)  NOT NULL DEFAULT 0,
    total_employer        NUMERIC(18,2)  NOT NULL DEFAULT 0,     -- pension, occupational hazards and maternity, the employer's shares
    prepared_by_id        BIGINT,
    prepared_by           VARCHAR(50)    NOT NULL,
    prepared_at           TIMESTAMP      NOT NULL,
    submitted_by_id       BIGINT,
    submitted_by          VARCHAR(50),
    submitted_at          TIMESTAMP,
    returned_by           VARCHAR(50),                           -- the last time it was sent back, and why
    returned_at           TIMESTAMP,
    returned_note         VARCHAR(255),
    approved_by           VARCHAR(50),
    approved_at           TIMESTAMP,
    posting_date          DATE,                                  -- the month's last day
    journal_id            UUID           REFERENCES journal_entries(id),
    paid_on               DATE,
    paid_by               VARCHAR(50),
    payment_reference     VARCHAR(60),
    payment_journal_id    UUID           REFERENCES journal_entries(id),
    cancel_reason         VARCHAR(255),
    cancelled_by          VARCHAR(50),
    cancelled_at          TIMESTAMP,
    created_at            TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by            VARCHAR(50),
    updated_at            TIMESTAMP,
    updated_by            VARCHAR(50),
    CONSTRAINT uk_pay_runs_number UNIQUE (number),
    CONSTRAINT chk_pay_runs_status CHECK (status IN ('DRAFT', 'SUBMITTED', 'POSTED', 'PAID', 'CANCELLED')),
    CONSTRAINT chk_pay_runs_period CHECK (EXTRACT(DAY FROM period) = 1),
    CONSTRAINT chk_pay_runs_submitted CHECK (status IN ('DRAFT', 'CANCELLED') OR (submitted_by IS NOT NULL AND submitted_at IS NOT NULL)),
    CONSTRAINT chk_pay_runs_posted CHECK ((status IN ('POSTED', 'PAID')) = (journal_id IS NOT NULL)
        AND (journal_id IS NULL OR (approved_by IS NOT NULL AND approved_at IS NOT NULL AND posting_date IS NOT NULL))),
    CONSTRAINT chk_pay_runs_paid CHECK ((status = 'PAID') = (payment_journal_id IS NOT NULL)
        AND (payment_journal_id IS NULL OR (paid_on IS NOT NULL AND paid_by IS NOT NULL))),
    CONSTRAINT chk_pay_runs_cancelled CHECK ((status = 'CANCELLED') = (cancel_reason IS NOT NULL AND cancelled_by IS NOT NULL)),
    CONSTRAINT chk_pay_runs_totals CHECK (employees >= 0 AND total_gross >= 0 AND total_net >= 0)
);
-- One run a month (a cancelled one gives the month back)
CREATE UNIQUE INDEX uk_pay_runs_period ON pay_runs (period) WHERE status <> 'CANCELLED';
CREATE INDEX idx_pay_runs_status ON pay_runs (status, period);

-- A line per employee: what they earned and what was kept, as fixed when the run was posted
CREATE TABLE pay_run_lines (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version               BIGINT         NOT NULL DEFAULT 0,
    pay_run_id            UUID           NOT NULL REFERENCES pay_runs(id),
    employee_id           UUID           NOT NULL REFERENCES employees(id),
    driver_id             UUID           REFERENCES drivers(id), -- an employee who drives: their shortage is recovered here
    employee_code         VARCHAR(20)    NOT NULL,
    employee_name         VARCHAR(100)   NOT NULL,
    national_id           VARCHAR(16)    NOT NULL,
    rssb_number           VARCHAR(20),
    job_title             VARCHAR(100),
    employment            VARCHAR(10)    NOT NULL,
    pay_method            VARCHAR(15)    NOT NULL,
    account_number        VARCHAR(40),
    base_pay              NUMERIC(18,2)  NOT NULL,
    transport_allowance   NUMERIC(18,2)  NOT NULL DEFAULT 0,
    housing_allowance     NUMERIC(18,2)  NOT NULL DEFAULT 0,
    other_allowance       NUMERIC(18,2)  NOT NULL DEFAULT 0,
    overtime              NUMERIC(18,2)  NOT NULL DEFAULT 0,
    bonus                 NUMERIC(18,2)  NOT NULL DEFAULT 0,
    advance_deduction     NUMERIC(18,2)  NOT NULL DEFAULT 0,     -- salary advances and loans recovered
    shortage_deduction    NUMERIC(18,2)  NOT NULL DEFAULT 0,     -- a driver's shortage recovered (ACC-07)
    other_deduction       NUMERIC(18,2)  NOT NULL DEFAULT 0,
    deduction_note        VARCHAR(255),
    gross                 NUMERIC(18,2)  NOT NULL DEFAULT 0,
    rssb_base             NUMERIC(18,2)  NOT NULL DEFAULT 0,
    paye                  NUMERIC(18,2)  NOT NULL DEFAULT 0,
    pension_employee      NUMERIC(18,2)  NOT NULL DEFAULT 0,
    pension_employer      NUMERIC(18,2)  NOT NULL DEFAULT 0,
    occupational_hazards  NUMERIC(18,2)  NOT NULL DEFAULT 0,
    maternity_employee    NUMERIC(18,2)  NOT NULL DEFAULT 0,
    maternity_employer    NUMERIC(18,2)  NOT NULL DEFAULT 0,
    cbhi                  NUMERIC(18,2)  NOT NULL DEFAULT 0,
    net_pay               NUMERIC(18,2)  NOT NULL DEFAULT 0,
    employer_cost         NUMERIC(18,2)  NOT NULL DEFAULT 0,
    created_at            TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by            VARCHAR(50),
    updated_at            TIMESTAMP,
    updated_by            VARCHAR(50),
    CONSTRAINT uk_pay_run_lines_employee UNIQUE (pay_run_id, employee_id),
    CONSTRAINT chk_pay_run_lines_employment CHECK (employment IN ('PRIMARY', 'SECONDARY')),
    CONSTRAINT chk_pay_run_lines_pay_method CHECK (pay_method IN ('BANK', 'MOBILE_MONEY', 'CASH')),
    CONSTRAINT chk_pay_run_lines_amounts CHECK (base_pay >= 0 AND transport_allowance >= 0 AND housing_allowance >= 0
        AND other_allowance >= 0 AND overtime >= 0 AND bonus >= 0 AND advance_deduction >= 0 AND shortage_deduction >= 0
        AND other_deduction >= 0 AND net_pay >= 0),
    CONSTRAINT chk_pay_run_lines_shortage CHECK (shortage_deduction = 0 OR driver_id IS NOT NULL)
);
CREATE INDEX idx_pay_run_lines_run ON pay_run_lines (pay_run_id);
CREATE INDEX idx_pay_run_lines_employee ON pay_run_lines (employee_id);

-- The lines of a posted or paid run never change
CREATE OR REPLACE FUNCTION forbid_posted_pay_run_line() RETURNS trigger AS $$
BEGIN
    IF (SELECT status FROM pay_runs WHERE id = OLD.pay_run_id) IN ('POSTED', 'PAID') THEN
        RAISE EXCEPTION 'The lines of a posted pay run never change (pay_run_lines %)', OLD.id;
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_pay_run_lines_posted BEFORE UPDATE OR DELETE ON pay_run_lines
    FOR EACH ROW EXECUTE FUNCTION forbid_posted_pay_run_line();

INSERT INTO number_sequences (doc_type, branch_code, prefix, reset_policy, padding, created_by) VALUES
('EMPLOYEE', 'WH', 'EMP', 'NEVER', 4, 'system'),
('PAY_RUN',  'WH', 'PAY', 'YEARLY', 6, 'system');

-- ---------------------------------------------------------------------
-- The ledger: what payroll costs, owes and recovers
-- ---------------------------------------------------------------------
INSERT INTO accounts (code, name, account_type, system_key, description, created_by) VALUES
('1140', 'Staff Advances',                'ASSET',     'STAFF_ADVANCES',         'Salary advances and loans to staff, recovered through payroll', 'system'),
('2060', 'PAYE Payable',                  'LIABILITY', 'PAYE_PAYABLE',           'Tax withheld from salaries, owed to RRA', 'system'),
('2070', 'RSSB Contributions Payable',    'LIABILITY', 'RSSB_PAYABLE',           'Pension, occupational hazards, maternity and CBHI owed to RSSB', 'system'),
('2080', 'Net Salaries Payable',          'LIABILITY', 'NET_PAY_PAYABLE',        'Net pay of approved pay runs until it is paid', 'system'),
('2090', 'Other Payroll Deductions',      'LIABILITY', 'PAYROLL_DEDUCTIONS',     'Other amounts kept from salaries, owed to whom they are for', 'system'),
('6010', 'Salaries and Wages',            'EXPENSE',   'SALARIES',               'Gross pay of approved pay runs', 'system'),
('6020', 'Employer Social Contributions', 'EXPENSE',   'EMPLOYER_CONTRIBUTIONS', 'The employer''s RSSB pension, occupational hazards and maternity', 'system');

ALTER TABLE journal_entries DROP CONSTRAINT chk_journal_entries_source;
ALTER TABLE journal_entries ADD CONSTRAINT chk_journal_entries_source CHECK (source_type IN
    ('GOODS_RECEIPT', 'SHIPMENT', 'CLAIM_OPENED', 'CLAIM_SETTLED', 'CLAIM_REJECTED', 'CUTTING_JOB', 'ADJUSTMENT',
     'OPENING_STOCK', 'SALES_INVOICE', 'TILL_OPENED', 'TILL_CLOSED', 'SALES_DELIVERY', 'SALES_BALANCE', 'CREDIT_NOTE',
     'CUSTOMER_PAYMENT', 'SUPPLIER_INVOICE', 'SUPPLIER_PAYMENT', 'FX_REVALUATION', 'MANUAL_JOURNAL', 'MOBILE_SALE',
     'TRIP_RETURN', 'AUDIT_CASE', 'FLOAT_CLEARANCE', 'PAYROLL', 'PAYROLL_PAYMENT'));

-- ---------------------------------------------------------------------
-- Security: the accountant prepares and pays, the owner approves, the
-- auditor reads; national IDs and accounts in full only for those who
-- manage pay (NFR-12, Law 058/2021)
-- ---------------------------------------------------------------------
INSERT INTO pages (code, name, module, path, icon, sort_order) VALUES
('EMPLOYEES',     'Employees',     'Payroll', '/payroll/employees', 'users',   1),
('PAY_RUNS',      'Pay Runs',      'Payroll', '/payroll/runs',      'cash',    2),
('PAYROLL_RATES', 'Payroll Rates', 'Payroll', '/payroll/rates',     'percent', 3);

INSERT INTO permissions (code, name, module, action, description) VALUES
('VIEW_EMPLOYEE',        'View Employees',        'Payroll', 'VIEW',    'See employees, their salaries and pay history'),
('MANAGE_EMPLOYEE',      'Manage Employees',      'Payroll', 'MANAGE',  'Add employees, change their salary and account, record that they left'),
('VIEW_EMPLOYEE_DATA',   'View Employee Data',    'Payroll', 'VIEW',    'See national IDs and bank or mobile-money accounts in full'),
('VIEW_PAY_RUN',         'View Pay Runs',         'Payroll', 'VIEW',    'See pay runs, payslips and the PAYE and RSSB declarations'),
('PREPARE_PAY_RUN',      'Prepare Pay Runs',      'Payroll', 'PREPARE', 'Prepare a month''s pay run, submit it and record the salaries paid'),
('APPROVE_PAY_RUN',      'Approve Pay Runs',      'Payroll', 'APPROVE', 'Approve (post) or send back a pay run prepared by someone else'),
('MANAGE_PAYROLL_RATES', 'Manage Payroll Rates',  'Payroll', 'MANAGE',  'Record the PAYE and RSSB rates in force from a date');

INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE p.code IN ('EMPLOYEES', 'PAY_RUNS', 'PAYROLL_RATES') AND r.code IN ('ADMIN', 'ACCOUNTANT', 'OWNER', 'AUDITOR')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE (r.code = 'ADMIN' AND p.code IN ('VIEW_EMPLOYEE', 'MANAGE_EMPLOYEE', 'VIEW_EMPLOYEE_DATA', 'VIEW_PAY_RUN', 'PREPARE_PAY_RUN',
        'APPROVE_PAY_RUN', 'MANAGE_PAYROLL_RATES'))
   OR (r.code = 'ACCOUNTANT' AND p.code IN ('VIEW_EMPLOYEE', 'MANAGE_EMPLOYEE', 'VIEW_EMPLOYEE_DATA', 'VIEW_PAY_RUN', 'PREPARE_PAY_RUN',
        'MANAGE_PAYROLL_RATES'))
   OR (r.code = 'OWNER' AND p.code IN ('VIEW_EMPLOYEE', 'MANAGE_EMPLOYEE', 'VIEW_EMPLOYEE_DATA', 'VIEW_PAY_RUN', 'APPROVE_PAY_RUN'))
   OR (r.code = 'AUDITOR' AND p.code IN ('VIEW_EMPLOYEE', 'VIEW_PAY_RUN'))
ON CONFLICT DO NOTHING;
