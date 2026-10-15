-- =====================================================================
-- V47: the daily close, cash and banking, receivables follow-up and month-end sign-offs (M14 of the plan to cover the
-- client's restructuring documents; not in SRS v2.1)
--   - the till's cash counted by denomination and verified by Treasury; the day's takings banked in full with the slip
--     number (F-13, SDP 10)
--   - the daily close: the end-of-day sales report (EBM receipts, sales by channel and method, collections, credit
--     granted, cash and MoMo against what came in), prepared, then signed by the Director of Finance, tested weekly by
--     the Internal Controller (F-12, BP 7.1 rule 5, 7.5)
--   - bank and MoMo reconciliations reviewed by a second person, late ones reported (BP Table 9, Annex D)
--   - transfers confirmed by Finance before the goods leave (SDP 14)
--   - customer reminders and balance confirmations (BP 5.1, Table 9; SDP 10-11)
--   - the journal register certified monthly and sampled; the month's accounts signed off by the COO, then the MD
--     (F-16, BP 4.2)
--   - the reporting calendar's reminders (BP 5.3)
-- =====================================================================

-- ---------------------------------------------------------------------
-- Cash banked in full, with its slip (F-13, P.04)
-- ---------------------------------------------------------------------
CREATE TABLE cash_deposits (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version       BIGINT        NOT NULL DEFAULT 0,
    number        VARCHAR(30)   NOT NULL,           -- BNK-WH-2026-000001
    deposit_date  DATE          NOT NULL,
    account_id    UUID          NOT NULL REFERENCES accounts(id),
    slip_number   VARCHAR(40)   NOT NULL,
    amount        NUMERIC(18,2) NOT NULL,
    notes         VARCHAR(255),
    posted_at     TIMESTAMP     NOT NULL,
    posted_by     VARCHAR(50)   NOT NULL,
    created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by    VARCHAR(50),
    updated_at    TIMESTAMP,
    updated_by    VARCHAR(50),
    CONSTRAINT uk_cash_deposits_number UNIQUE (number),
    CONSTRAINT uk_cash_deposits_slip UNIQUE (account_id, slip_number),
    CONSTRAINT chk_cash_deposits_amount CHECK (amount > 0)
);
CREATE INDEX idx_cash_deposits_date ON cash_deposits (deposit_date);

-- The till's count by denomination, Treasury's verification, the deposit that banked it (F-13, M.10)
ALTER TABLE till_sessions ADD COLUMN denominations VARCHAR(400);       -- 5000:12,1000:3,... (note or coin : how many)
ALTER TABLE till_sessions ADD COLUMN verified_by VARCHAR(50);
ALTER TABLE till_sessions ADD COLUMN verified_by_id BIGINT;
ALTER TABLE till_sessions ADD COLUMN verified_at TIMESTAMP;
ALTER TABLE till_sessions ADD COLUMN verify_note VARCHAR(255);
ALTER TABLE till_sessions ADD COLUMN deposit_id UUID REFERENCES cash_deposits(id);
ALTER TABLE till_sessions ADD CONSTRAINT chk_till_sessions_verified CHECK (verified_at IS NULL
    OR (status = 'CLOSED' AND verified_by IS NOT NULL));
ALTER TABLE till_sessions ADD CONSTRAINT chk_till_sessions_deposit CHECK (deposit_id IS NULL OR status = 'CLOSED');
CREATE INDEX idx_till_sessions_deposit ON till_sessions (deposit_id);
CREATE INDEX idx_till_sessions_closed ON till_sessions (closed_at);

-- ---------------------------------------------------------------------
-- The daily close (F-12; M.08, M.09, M.12, M.13, V.06, Q.10, P.08)
-- ---------------------------------------------------------------------
CREATE TABLE daily_closes (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version          BIGINT        NOT NULL DEFAULT 0,
    number           VARCHAR(30)   NOT NULL,           -- DC-WH-2026-000001
    business_day     DATE          NOT NULL,
    status           VARCHAR(10)   NOT NULL,
    invoices         INTEGER       NOT NULL,
    sales_total      NUMERIC(18,2) NOT NULL,
    cash_sales       NUMERIC(18,2) NOT NULL,
    momo_sales       NUMERIC(18,2) NOT NULL,
    other_sales      NUMERIC(18,2) NOT NULL,           -- card and bank transfer
    credit_sales     NUMERIC(18,2) NOT NULL,           -- on account, and deposit balances left to pay
    collections      NUMERIC(18,2) NOT NULL,           -- balances and account payments taken
    refunds          NUMERIC(18,2) NOT NULL,
    cash_expected    NUMERIC(18,2) NOT NULL,           -- the tills closed that day
    cash_counted     NUMERIC(18,2) NOT NULL,
    cash_variance    NUMERIC(18,2) NOT NULL,
    cash_to_bank     NUMERIC(18,2) NOT NULL,
    momo_received    NUMERIC(18,2) NOT NULL,           -- every MoMo payment of the day
    momo_statement   NUMERIC(18,2),                    -- the merchant statement's total
    momo_variance    NUMERIC(18,2) NOT NULL,
    ebm_receipts     INTEGER       NOT NULL,
    ebm_first        BIGINT,
    ebm_last         BIGINT,
    ebm_unsigned     INTEGER       NOT NULL,
    variance_note    VARCHAR(500),
    prepared_by      VARCHAR(50)   NOT NULL,
    prepared_by_id   BIGINT,
    prepared_at      TIMESTAMP     NOT NULL,
    signed_by        VARCHAR(50),
    signed_by_id     BIGINT,
    signed_at        TIMESTAMP,
    sign_note        VARCHAR(255),
    tested_by        VARCHAR(50),
    tested_by_id     BIGINT,
    tested_at        TIMESTAMP,
    test_note        VARCHAR(500),
    created_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by       VARCHAR(50),
    updated_at       TIMESTAMP,
    updated_by       VARCHAR(50),
    CONSTRAINT uk_daily_closes_number UNIQUE (number),
    CONSTRAINT uk_daily_closes_day UNIQUE (business_day),
    CONSTRAINT chk_daily_closes_status CHECK (status IN ('PREPARED', 'SIGNED')),
    CONSTRAINT chk_daily_closes_signed CHECK (status = 'PREPARED' OR (signed_at IS NOT NULL AND signed_by IS NOT NULL)),
    CONSTRAINT chk_daily_closes_variance CHECK ((cash_variance = 0 AND momo_variance = 0) OR variance_note IS NOT NULL),
    CONSTRAINT chk_daily_closes_tested CHECK (tested_at IS NULL OR (status = 'SIGNED' AND tested_by IS NOT NULL))
);

-- ---------------------------------------------------------------------
-- Reconciliations reviewed by a second person (P.09, M.16)
-- ---------------------------------------------------------------------
ALTER TABLE bank_reconciliations ADD COLUMN reconciled_by_id BIGINT;
ALTER TABLE bank_reconciliations ADD COLUMN reviewed_by VARCHAR(50);
ALTER TABLE bank_reconciliations ADD COLUMN reviewed_by_id BIGINT;
ALTER TABLE bank_reconciliations ADD COLUMN reviewed_at TIMESTAMP;
ALTER TABLE bank_reconciliations ADD COLUMN review_note VARCHAR(255);
ALTER TABLE bank_reconciliations ADD CONSTRAINT chk_bank_reconciliations_reviewed CHECK (reviewed_at IS NULL OR reviewed_by IS NOT NULL);

-- ---------------------------------------------------------------------
-- Transfers confirmed before the goods leave (P.05)
-- ---------------------------------------------------------------------
CREATE TABLE payment_confirmations (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sales_payment_id  UUID          NOT NULL REFERENCES sales_payments(id),
    invoice_id        UUID          NOT NULL REFERENCES sales_invoices(id),
    bank_reference    VARCHAR(60),
    note              VARCHAR(255),
    created_at        TIMESTAMP     NOT NULL,
    user_id           BIGINT,
    username          VARCHAR(50)   NOT NULL,
    CONSTRAINT uk_payment_confirmations_payment UNIQUE (sales_payment_id)
);
CREATE INDEX idx_payment_confirmations_invoice ON payment_confirmations (invoice_id);
CREATE TRIGGER trg_payment_confirmations_append_only BEFORE UPDATE OR DELETE ON payment_confirmations
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_modification();

-- ---------------------------------------------------------------------
-- Reminders and balance confirmations (Q.04, Q.07)
-- ---------------------------------------------------------------------
CREATE TABLE customer_reminders (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    number       VARCHAR(30)   NOT NULL,              -- REM-WH-2026-000001
    customer_id  UUID          NOT NULL REFERENCES customers(id),
    level        INTEGER       NOT NULL,
    as_of        DATE          NOT NULL,
    balance      NUMERIC(18,2) NOT NULL,
    overdue      NUMERIC(18,2) NOT NULL,
    oldest_days  INTEGER       NOT NULL,
    created_at   TIMESTAMP     NOT NULL,
    user_id      BIGINT,
    username     VARCHAR(50)   NOT NULL,
    CONSTRAINT uk_customer_reminders_number UNIQUE (number),
    CONSTRAINT chk_customer_reminders_level CHECK (level BETWEEN 1 AND 3),
    CONSTRAINT chk_customer_reminders_overdue CHECK (overdue > 0)
);
CREATE INDEX idx_customer_reminders_customer ON customer_reminders (customer_id, created_at);
CREATE TRIGGER trg_customer_reminders_append_only BEFORE UPDATE OR DELETE ON customer_reminders
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_modification();

CREATE TABLE balance_confirmations (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version               BIGINT        NOT NULL DEFAULT 0,
    number                VARCHAR(30)   NOT NULL,     -- BC-WH-2026-000001
    customer_id           UUID          NOT NULL REFERENCES customers(id),
    as_of                 DATE          NOT NULL,
    balance               NUMERIC(18,2) NOT NULL,
    status                VARCHAR(10)   NOT NULL,
    sent_by               VARCHAR(50)   NOT NULL,
    sent_by_id            BIGINT,
    sent_at               TIMESTAMP     NOT NULL,
    replied_on            DATE,
    reply_balance         NUMERIC(18,2),
    reply_note            VARCHAR(500),
    reply_recorded_by     VARCHAR(50),
    reply_recorded_by_id  BIGINT,
    reply_recorded_at     TIMESTAMP,
    created_at            TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by            VARCHAR(50),
    updated_at            TIMESTAMP,
    updated_by            VARCHAR(50),
    CONSTRAINT uk_balance_confirmations_number UNIQUE (number),
    CONSTRAINT chk_balance_confirmations_status CHECK (status IN ('SENT', 'AGREED', 'DISAGREED')),
    CONSTRAINT chk_balance_confirmations_reply CHECK (status = 'SENT'
        OR (replied_on IS NOT NULL AND reply_recorded_at IS NOT NULL AND reply_recorded_by IS NOT NULL)),
    CONSTRAINT chk_balance_confirmations_disagreed CHECK (status <> 'DISAGREED'
        OR (reply_balance IS NOT NULL AND reply_note IS NOT NULL))
);
CREATE UNIQUE INDEX uk_balance_confirmations_open ON balance_confirmations (customer_id) WHERE status = 'SENT';

-- ---------------------------------------------------------------------
-- The journal register certified and sampled; the month signed off (U.07, U.15)
-- ---------------------------------------------------------------------
CREATE TABLE journal_register_certifications (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version         BIGINT        NOT NULL DEFAULT 0,
    period_end      DATE          NOT NULL,
    journals        INTEGER       NOT NULL,
    first_number    VARCHAR(30),
    last_number     VARCHAR(30),
    gaps            VARCHAR(500),
    total           NUMERIC(18,2) NOT NULL,
    certified_by    VARCHAR(50)   NOT NULL,
    certified_by_id BIGINT,
    certified_at    TIMESTAMP     NOT NULL,
    certify_note    VARCHAR(255),
    sampled_by      VARCHAR(50),
    sampled_by_id   BIGINT,
    sampled_at      TIMESTAMP,
    sample_note     VARCHAR(500),
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by      VARCHAR(50),
    updated_at      TIMESTAMP,
    updated_by      VARCHAR(50),
    CONSTRAINT uk_journal_register_certifications_period UNIQUE (period_end),
    CONSTRAINT chk_journal_register_certifications_sampled CHECK (sampled_at IS NULL
        OR (sampled_by IS NOT NULL AND sample_note IS NOT NULL))
);

CREATE TABLE period_sign_offs (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    period_id   UUID          NOT NULL REFERENCES accounting_periods(id),
    office      VARCHAR(10)   NOT NULL,
    note        VARCHAR(255),
    created_at  TIMESTAMP     NOT NULL,
    user_id     BIGINT,
    username    VARCHAR(50)   NOT NULL,
    CONSTRAINT chk_period_sign_offs_office CHECK (office IN ('COO', 'MD'))
);
CREATE INDEX idx_period_sign_offs_period ON period_sign_offs (period_id, created_at);
CREATE TRIGGER trg_period_sign_offs_append_only BEFORE UPDATE OR DELETE ON period_sign_offs
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_modification();

-- ---------------------------------------------------------------------
-- Journals, documents, notifications
-- ---------------------------------------------------------------------
ALTER TABLE journal_entries DROP CONSTRAINT chk_journal_entries_source;
ALTER TABLE journal_entries ADD CONSTRAINT chk_journal_entries_source CHECK (source_type IN
    ('GOODS_RECEIPT', 'SHIPMENT', 'CLAIM_OPENED', 'CLAIM_SETTLED', 'CLAIM_REJECTED', 'CUTTING_JOB', 'ADJUSTMENT',
     'OPENING_STOCK', 'SALES_INVOICE', 'TILL_OPENED', 'TILL_CLOSED', 'SALES_DELIVERY', 'SALES_BALANCE', 'CREDIT_NOTE',
     'CUSTOMER_PAYMENT', 'SUPPLIER_INVOICE', 'SUPPLIER_PAYMENT', 'FX_REVALUATION', 'MANUAL_JOURNAL', 'MOBILE_SALE',
     'TRIP_RETURN', 'AUDIT_CASE', 'FLOAT_CLEARANCE', 'PAYROLL', 'PAYROLL_PAYMENT', 'VEHICLE_COST', 'RECEIVABLE_WRITE_OFF',
     'CASH_DEPOSIT'));

ALTER TABLE attachments DROP CONSTRAINT chk_attachments_owner;
ALTER TABLE attachments ADD CONSTRAINT chk_attachments_owner CHECK (owner_type IN
    ('GOODS_RECEIPT', 'SHIPMENT', 'SUPPLIER_INVOICE', 'STOCK_ADJUSTMENT', 'VEHICLE', 'DRIVER', 'TRIP', 'VEHICLE_COST',
     'CREDIT_NOTE_REQUEST', 'RECEIVABLE_WRITE_OFF', 'CUSTOMER', 'SUPPLIER', 'CREDIT_APPLICATION', 'SITE_TRANSFER',
     'CUTTING_JOB', 'DELIVERY_NOTE', 'CASH_DEPOSIT', 'BALANCE_CONFIRMATION', 'DAILY_CLOSE'));

ALTER TABLE notifications DROP CONSTRAINT chk_notifications_kind;
ALTER TABLE notifications ADD CONSTRAINT chk_notifications_kind
    CHECK (kind IN ('LOW_STOCK', 'APPROVAL', 'DECISION', 'SYSTEM', 'EBM', 'FLEET', 'SYNC', 'EOD', 'CREDIT', 'TRANSFER',
                    'ORDER', 'DELIVERY', 'CLOSE'));

-- ---------------------------------------------------------------------
-- Numbers and settings
-- ---------------------------------------------------------------------
INSERT INTO number_sequences (doc_type, branch_code, prefix, reset_policy, padding, created_by) VALUES
('CASH_DEPOSIT',         'WH', 'BNK', 'YEARLY', 6, 'system'),
('DAILY_CLOSE',          'WH', 'DC',  'YEARLY', 6, 'system'),
('CUSTOMER_REMINDER',    'WH', 'REM', 'YEARLY', 6, 'system'),
('BALANCE_CONFIRMATION', 'WH', 'BC',  'YEARLY', 6, 'system');

INSERT INTO settings (setting_key, setting_value, created_by) VALUES
('cash.denominations',           '5000,2000,1000,500,100,50,20,10,5,1', 'system'),  -- RWF notes and coins counted at the till
('cash.verify-count',            'true',  'system'),   -- Treasury verifies a till's count before its cash is banked (F-13)
('close.daily-report-by',        '10:00', 'system'),   -- the day's close is prepared by this time the next day (BP 5.3)
('reconciliation.due-working-day', '5',   'system'),   -- bank and MoMo reconciled by this working day of the month
('release.transfer-confirmed',   'true',  'system'),   -- goods paid by transfer leave once Finance confirms the money (SDP 14)
('credit.over90-max-percent',    '5',     'system'),   -- the share of receivables more than 90 days overdue kept below this
('credit.dso-days',              '90',    'system'),   -- the credit sales of so many days give the days sales outstanding
('calendar.accounts-working-day', '10',   'system');   -- the month's accounts are ready by this working day (BP 5.3)

-- ---------------------------------------------------------------------
-- Security
-- ---------------------------------------------------------------------
INSERT INTO pages (code, name, module, path, icon, sort_order) VALUES
('DAILY_CLOSES',          'Daily Close',            'Accounting', '/daily-close',                 'calendar-check', 2),
('CASH_DEPOSITS',         'Cash Banking',           'Accounting', '/cash-deposits',               'landmark',       3),
('TRANSFER_CONFIRMATIONS','Transfers to Confirm',   'Accounting', '/transfers',                   'check-circle',   3),
('BALANCE_CONFIRMATIONS', 'Balance Confirmations',  'Accounting', '/accounting/confirmations',    'mail-check',     4),
('JOURNAL_REGISTER',      'Journal Register',       'Accounting', '/accounting/journal-register', 'list-ordered',   9),
('EBM_LEDGER_REPORT',     'EBM and the Ledger',     'Reports',    '/reports/ebm-ledger',          'scale',          43);

INSERT INTO permissions (code, name, module, action, description) VALUES
('VIEW_DAILY_CLOSE',          'View the Daily Close',          'Accounting', 'VIEW',    'See the end-of-day sales report and the daily closes'),
('PREPARE_DAILY_CLOSE',       'Prepare the Daily Close',       'Accounting', 'CREATE',  'Prepare the day''s close: the MoMo statement and the variances explained'),
('SIGN_DAILY_CLOSE',          'Sign the Daily Close',          'Accounting', 'APPROVE', 'Sign a daily close prepared by someone else'),
('TEST_DAILY_CLOSE',          'Test Daily Closes',             'Accounting', 'REVIEW',  'Record the weekly test of signed daily closes'),
('VERIFY_CASH_COUNT',         'Verify Cash Counts',            'Accounting', 'VERIFY',  'Verify a closed till''s cash count (Treasury)'),
('VIEW_CASH_DEPOSIT',         'View Cash Banking',             'Accounting', 'VIEW',    'See the cash banked and the tills each deposit took'),
('BANK_CASH',                 'Bank Cash',                     'Accounting', 'CREATE',  'Record the takings banked in full with the deposit slip'),
('CONFIRM_TRANSFER',          'Confirm Transfers',             'Accounting', 'APPROVE', 'Confirm that a customer''s bank transfer has arrived'),
('REVIEW_RECONCILIATION',     'Review Reconciliations',        'Accounting', 'REVIEW',  'Review and sign a bank or MoMo reconciliation made by someone else'),
('ISSUE_REMINDER',            'Issue Payment Reminders',       'Accounting', 'CREATE',  'Issue reminder letters to customers with overdue balances'),
('VIEW_BALANCE_CONFIRMATION', 'View Balance Confirmations',    'Accounting', 'VIEW',    'See the balance confirmations sent to customers and their replies'),
('CONFIRM_BALANCES',          'Send Balance Confirmations',    'Accounting', 'CREATE',  'Send balance confirmations to customers and record their replies'),
('CERTIFY_JOURNAL_REGISTER',  'Certify the Journal Register',  'Accounting', 'APPROVE', 'Certify a closed month''s journal register'),
('SAMPLE_JOURNALS',           'Sample the Journal Register',   'Accounting', 'REVIEW',  'Record the sample checked in a certified journal register'),
('SIGN_OFF_PERIOD',           'Sign Off the Month''s Accounts','Accounting', 'APPROVE', 'Sign off a closed month''s accounts: the COO, then the MD'),
('ALERT_DAILY_CLOSE',         'Daily Close Alerts',            'Accounting', 'ALERT',   'Be told of variances, late daily closes, cash not banked, late reconciliations and disputed balances'),
('ALERT_CREDIT_LIST',         'Credit List',                   'Accounting', 'ALERT',   'Receive the day''s credit list when the day is closed'),
('ALERT_REPORT_CALENDAR',     'Reporting Calendar',            'Accounting', 'ALERT',   'Be reminded of the weekly, monthly and quarterly reports');

INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE (p.code = 'DAILY_CLOSES' AND r.code IN ('ADMIN', 'OWNER', 'ACCOUNTANT', 'AUDITOR', 'COO', 'DIRECTOR_FINANCE', 'TREASURY',
        'SALES_RECORDING', 'CREDIT_CONTROL', 'INTERNAL_CONTROLLER', 'BOOKKEEPING_CASHIER', 'DIRECTOR_COMMERCIAL'))
   OR (p.code = 'CASH_DEPOSITS' AND r.code IN ('ADMIN', 'OWNER', 'ACCOUNTANT', 'AUDITOR', 'COO', 'DIRECTOR_FINANCE', 'TREASURY',
        'BOOKKEEPING_CASHIER', 'INTERNAL_CONTROLLER'))
   OR (p.code = 'TRANSFER_CONFIRMATIONS' AND r.code IN ('ADMIN', 'OWNER', 'ACCOUNTANT', 'AUDITOR', 'DIRECTOR_FINANCE', 'TREASURY',
        'INTERNAL_CONTROLLER', 'DISPATCH_OFFICER', 'DIRECTOR_SUPPLY_CHAIN'))
   OR (p.code = 'BALANCE_CONFIRMATIONS' AND r.code IN ('ADMIN', 'OWNER', 'ACCOUNTANT', 'AUDITOR', 'DIRECTOR_FINANCE',
        'INTERNAL_CONTROLLER', 'CREDIT_CONTROL', 'COO'))
   OR (p.code = 'JOURNAL_REGISTER' AND r.code IN ('ADMIN', 'OWNER', 'ACCOUNTANT', 'AUDITOR', 'DIRECTOR_FINANCE', 'INTERNAL_CONTROLLER',
        'COO', 'REPORTING_TAX_BUDGET'))
   OR (p.code = 'EBM_LEDGER_REPORT' AND r.code IN ('ADMIN', 'OWNER', 'ACCOUNTANT', 'AUDITOR', 'DIRECTOR_FINANCE', 'INTERNAL_CONTROLLER',
        'COO', 'REPORTING_TAX_BUDGET'))
   -- reminders and the movement of receivables are on the receivables page
   OR (p.code = 'RECEIVABLES' AND r.code IN ('CREDIT_CONTROL', 'DIRECTOR_FINANCE', 'INTERNAL_CONTROLLER'))
   OR (p.code = 'RECONCILIATIONS' AND r.code IN ('DIRECTOR_FINANCE', 'TREASURY', 'INTERNAL_CONTROLLER'))
   OR (p.code = 'PERIODS' AND r.code IN ('COO', 'DIRECTOR_FINANCE', 'INTERNAL_CONTROLLER'))
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE (r.code = 'ADMIN' AND p.code IN ('VIEW_DAILY_CLOSE', 'PREPARE_DAILY_CLOSE', 'SIGN_DAILY_CLOSE', 'TEST_DAILY_CLOSE',
        'VERIFY_CASH_COUNT', 'VIEW_CASH_DEPOSIT', 'BANK_CASH', 'CONFIRM_TRANSFER', 'REVIEW_RECONCILIATION', 'ISSUE_REMINDER',
        'VIEW_BALANCE_CONFIRMATION', 'CONFIRM_BALANCES', 'CERTIFY_JOURNAL_REGISTER', 'SAMPLE_JOURNALS', 'SIGN_OFF_PERIOD',
        'ALERT_DAILY_CLOSE', 'ALERT_CREDIT_LIST', 'ALERT_REPORT_CALENDAR'))
   OR (r.code = 'OWNER' AND p.code IN ('VIEW_DAILY_CLOSE', 'VIEW_CASH_DEPOSIT', 'VIEW_BALANCE_CONFIRMATION', 'SIGN_OFF_PERIOD',
        'ALERT_REPORT_CALENDAR'))
   OR (r.code = 'COO' AND p.code IN ('VIEW_DAILY_CLOSE', 'VIEW_CASH_DEPOSIT', 'VIEW_BALANCE_CONFIRMATION', 'SIGN_OFF_PERIOD',
        'VIEW_ACCOUNTING', 'ALERT_REPORT_CALENDAR'))
   OR (r.code = 'DIRECTOR_FINANCE' AND p.code IN ('VIEW_DAILY_CLOSE', 'SIGN_DAILY_CLOSE', 'VIEW_CASH_DEPOSIT', 'VERIFY_CASH_COUNT',
        'CONFIRM_TRANSFER', 'REVIEW_RECONCILIATION', 'VIEW_BALANCE_CONFIRMATION', 'CERTIFY_JOURNAL_REGISTER', 'VIEW_ACCOUNTING',
        'VIEW_CUSTOMER_ACCOUNT', 'ALERT_DAILY_CLOSE', 'ALERT_REPORT_CALENDAR'))
   OR (r.code = 'ACCOUNTANT' AND p.code IN ('VIEW_DAILY_CLOSE', 'PREPARE_DAILY_CLOSE', 'VIEW_CASH_DEPOSIT', 'BANK_CASH',
        'CONFIRM_TRANSFER', 'REVIEW_RECONCILIATION', 'ISSUE_REMINDER', 'VIEW_BALANCE_CONFIRMATION', 'ALERT_REPORT_CALENDAR'))
   OR (r.code = 'TREASURY' AND p.code IN ('VIEW_DAILY_CLOSE', 'VERIFY_CASH_COUNT', 'VIEW_CASH_DEPOSIT', 'BANK_CASH',
        'CONFIRM_TRANSFER', 'VIEW_ACCOUNTING', 'RECONCILE_ACCOUNT', 'ALERT_DAILY_CLOSE'))
   OR (r.code = 'BOOKKEEPING_CASHIER' AND p.code IN ('VIEW_DAILY_CLOSE', 'VIEW_CASH_DEPOSIT', 'BANK_CASH'))
   OR (r.code = 'SALES_RECORDING' AND p.code IN ('VIEW_DAILY_CLOSE', 'PREPARE_DAILY_CLOSE'))
   OR (r.code = 'CREDIT_CONTROL' AND p.code IN ('VIEW_DAILY_CLOSE', 'ISSUE_REMINDER', 'VIEW_BALANCE_CONFIRMATION',
        'VIEW_CUSTOMER_ACCOUNT', 'ALERT_CREDIT_LIST'))
   OR (r.code = 'INTERNAL_CONTROLLER' AND p.code IN ('VIEW_DAILY_CLOSE', 'TEST_DAILY_CLOSE', 'VIEW_CASH_DEPOSIT',
        'VIEW_BALANCE_CONFIRMATION', 'CONFIRM_BALANCES', 'SAMPLE_JOURNALS', 'VIEW_ACCOUNTING', 'VIEW_CUSTOMER_ACCOUNT',
        'ALERT_DAILY_CLOSE'))
   OR (r.code = 'AUDITOR' AND p.code IN ('VIEW_DAILY_CLOSE', 'TEST_DAILY_CLOSE', 'VIEW_CASH_DEPOSIT', 'VIEW_BALANCE_CONFIRMATION',
        'CONFIRM_BALANCES', 'SAMPLE_JOURNALS'))
   OR (r.code = 'DIRECTOR_COMMERCIAL' AND p.code = 'VIEW_DAILY_CLOSE')
   OR (r.code = 'REPORTING_TAX_BUDGET' AND p.code IN ('VIEW_ACCOUNTING', 'ALERT_REPORT_CALENDAR'))
   OR (r.code IN ('DISPATCH_OFFICER', 'DIRECTOR_SUPPLY_CHAIN') AND p.code = 'VIEW_INVOICE')
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------
-- M13 grants put right: the Owner and the COO may release goods (they hold the EBM override, which needs it); the
-- Director of Finance, Credit Control and the bookkeeping cashier see delivery notes (they hold the right, not the page)
-- ---------------------------------------------------------------------
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code IN ('OWNER', 'COO') AND p.code = 'RELEASE_GOODS'
ON CONFLICT DO NOTHING;

INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE p.code = 'DELIVERY_NOTES' AND r.code IN ('CREDIT_CONTROL', 'DIRECTOR_FINANCE', 'BOOKKEEPING_CASHIER')
ON CONFLICT DO NOTHING;
