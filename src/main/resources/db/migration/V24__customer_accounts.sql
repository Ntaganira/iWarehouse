-- =====================================================================
-- V24: customer accounts (ACC-09). What a customer owes is the
-- receivable account's lines that name them (credit sales, the balance
-- of deposit orders, credit notes to their account). A customer payment
-- (RCT-WH-2026-000001) settles it: cash into the cashier's till, mobile
-- money, card or bank transfer; Dr that account / Cr the receivable.
-- Ageing matches payments to the oldest charges, each due its invoice
-- date plus the customer's payment terms.
-- =====================================================================

CREATE TABLE customer_payments (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version           BIGINT         NOT NULL DEFAULT 0,
    number            VARCHAR(30)    NOT NULL,          -- RCT-WH-2026-000001
    customer_id       UUID           NOT NULL REFERENCES customers(id),
    payment_date      DATE           NOT NULL,
    method            VARCHAR(15)    NOT NULL,
    amount            NUMERIC(18,2)  NOT NULL,
    reference         VARCHAR(60),                      -- mobile money, card or transfer reference
    till_session_id   UUID           REFERENCES till_sessions(id), -- cash: the till it went into
    cash_tendered     NUMERIC(18,2),                    -- cash: what was handed over (the change is the difference)
    notes             VARCHAR(255),
    posted_at         TIMESTAMP      NOT NULL,
    posted_by         VARCHAR(50)    NOT NULL,
    created_at        TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by        VARCHAR(50),
    updated_at        TIMESTAMP,
    updated_by        VARCHAR(50),
    CONSTRAINT uk_customer_payments_number UNIQUE (number),
    CONSTRAINT chk_customer_payments_method CHECK (method IN ('CASH', 'MOBILE_MONEY', 'CARD', 'BANK_TRANSFER')),
    CONSTRAINT chk_customer_payments_amount CHECK (amount > 0),
    CONSTRAINT chk_customer_payments_reference CHECK (method = 'CASH' OR reference IS NOT NULL),
    CONSTRAINT chk_customer_payments_till CHECK ((method = 'CASH') = (till_session_id IS NOT NULL)),
    CONSTRAINT chk_customer_payments_tendered CHECK (cash_tendered IS NULL OR (method = 'CASH' AND cash_tendered >= amount))
);
CREATE INDEX idx_customer_payments_customer ON customer_payments (customer_id, payment_date);
CREATE INDEX idx_customer_payments_till ON customer_payments (till_session_id) WHERE till_session_id IS NOT NULL;

-- A customer's statement reads the receivable lines that name them
CREATE INDEX idx_journal_lines_account_customer ON journal_lines (account_id, customer_id) WHERE customer_id IS NOT NULL;

-- Cash a till took on customer accounts: it is in the drawer
ALTER TABLE till_sessions ADD COLUMN cash_account_payments NUMERIC(18,2);
UPDATE till_sessions SET cash_account_payments = 0 WHERE status = 'CLOSED';
ALTER TABLE till_sessions ADD CONSTRAINT chk_till_sessions_account_payments CHECK ((status = 'CLOSED') = (cash_account_payments IS NOT NULL)
    AND (cash_account_payments IS NULL OR cash_account_payments >= 0));

ALTER TABLE journal_entries DROP CONSTRAINT chk_journal_entries_source;
ALTER TABLE journal_entries ADD CONSTRAINT chk_journal_entries_source CHECK (source_type IN ('GOODS_RECEIPT', 'SHIPMENT',
    'CLAIM_OPENED', 'CLAIM_SETTLED', 'CLAIM_REJECTED', 'CUTTING_JOB', 'ADJUSTMENT', 'OPENING_STOCK',
    'SALES_INVOICE', 'TILL_OPENED', 'TILL_CLOSED', 'SALES_DELIVERY', 'SALES_BALANCE', 'CREDIT_NOTE', 'CUSTOMER_PAYMENT'));

-- Numbers: RECEIPT (RCT-WH-2026-000001) was seeded with the other document types in V5

-- ---------------------------------------------------------------------
-- Pages, permissions, grants (SRS 2.2: the cashier and the accountant
-- take what customers owe; the owner and auditor read the accounts)
-- ---------------------------------------------------------------------
INSERT INTO pages (code, name, module, path, icon, sort_order) VALUES
('CUSTOMER_PAYMENTS', 'Customer Payments', 'Sales & POS', '/customer-payments', 'credit-card', 9),
('RECEIVABLES',       'Receivables',       'Accounting',  '/accounting/receivables', 'clock', 4);

INSERT INTO permissions (code, name, module, action, description) VALUES
('VIEW_CUSTOMER_ACCOUNT',    'View Customer Accounts',  'Sales', 'VIEW',    'View what customers owe, their statements, ageing and payments'),
('RECEIVE_CUSTOMER_PAYMENT', 'Receive Customer Payments', 'Sales', 'RECEIVE', 'Take a payment on a customer''s account');

INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE (p.code = 'CUSTOMER_PAYMENTS' AND r.code IN ('ADMIN', 'CASHIER', 'ACCOUNTANT', 'OWNER', 'AUDITOR'))
   OR (p.code = 'RECEIVABLES' AND r.code IN ('ADMIN', 'ACCOUNTANT', 'OWNER', 'AUDITOR'))
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE (p.code = 'VIEW_CUSTOMER_ACCOUNT' AND r.code IN ('ADMIN', 'CASHIER', 'ACCOUNTANT', 'OWNER', 'AUDITOR'))
   OR (p.code = 'RECEIVE_CUSTOMER_PAYMENT' AND r.code IN ('ADMIN', 'CASHIER', 'ACCOUNTANT'))
ON CONFLICT DO NOTHING;
