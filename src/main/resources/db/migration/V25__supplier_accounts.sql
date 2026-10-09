-- =====================================================================
-- V25: supplier accounts (ACC-08, ACC-09). A supplier invoice (SINV)
-- matches the supplier's posted goods receipts not invoiced yet: each
-- receipt moves from Goods Received Not Invoiced to Accounts Payable at
-- its own value and rate (Dr GRNI / Cr AP, in the supplier's currency).
-- Shipment bills that name a supplier are payables already. A supplier
-- payment (SPAY) pays in a currency at that day's rate and settles the
-- oldest open items in that currency; the difference between their
-- booked RWF and the RWF paid is the realised FX gain or loss.
-- =====================================================================

CREATE TABLE supplier_invoices (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version           BIGINT         NOT NULL DEFAULT 0,
    number            VARCHAR(30)    NOT NULL,          -- SINV-WH-2026-000001
    supplier_id       UUID           NOT NULL REFERENCES suppliers(id),
    supplier_ref      VARCHAR(60)    NOT NULL,          -- the supplier's own invoice number
    invoice_date      DATE           NOT NULL,
    due_date          DATE           NOT NULL,          -- invoice date + the supplier's payment terms
    currency_code     VARCHAR(3)     NOT NULL REFERENCES currencies(code),
    amount            NUMERIC(18,2)  NOT NULL,          -- in the currency, the receipts' value
    base_amount       NUMERIC(18,2)  NOT NULL,          -- RWF, as the receipts were booked
    notes             VARCHAR(255),
    posted_at         TIMESTAMP      NOT NULL,
    posted_by         VARCHAR(50)    NOT NULL,
    created_at        TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by        VARCHAR(50),
    updated_at        TIMESTAMP,
    updated_by        VARCHAR(50),
    CONSTRAINT uk_supplier_invoices_number UNIQUE (number),
    CONSTRAINT uk_supplier_invoices_ref UNIQUE (supplier_id, supplier_ref),
    CONSTRAINT chk_supplier_invoices_amounts CHECK (amount > 0 AND base_amount > 0),
    CONSTRAINT chk_supplier_invoices_due CHECK (due_date >= invoice_date)
);
CREATE INDEX idx_supplier_invoices_supplier ON supplier_invoices (supplier_id, invoice_date);

-- The receipts an invoice matched, each once
CREATE TABLE supplier_invoice_lines (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    invoice_id        UUID           NOT NULL REFERENCES supplier_invoices(id),
    line_no           INTEGER        NOT NULL,
    goods_receipt_id  UUID           NOT NULL REFERENCES goods_receipts(id),
    receipt_number    VARCHAR(30)    NOT NULL,
    amount            NUMERIC(18,2)  NOT NULL,          -- in the invoice's currency
    rate              NUMERIC(18,6),                    -- the receipt's rate (null in RWF)
    base_amount       NUMERIC(18,2)  NOT NULL,          -- RWF, as the receipt was booked
    CONSTRAINT uk_supplier_invoice_lines_no UNIQUE (invoice_id, line_no),
    CONSTRAINT uk_supplier_invoice_lines_receipt UNIQUE (goods_receipt_id),
    CONSTRAINT chk_supplier_invoice_lines_amounts CHECK (amount > 0 AND base_amount > 0)
);
CREATE INDEX idx_supplier_invoice_lines_invoice ON supplier_invoice_lines (invoice_id);
CREATE TRIGGER trg_supplier_invoice_lines_append_only BEFORE UPDATE OR DELETE ON supplier_invoice_lines
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_modification();

CREATE TABLE supplier_payments (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version           BIGINT         NOT NULL DEFAULT 0,
    number            VARCHAR(30)    NOT NULL,          -- SPAY-WH-2026-000001
    supplier_id       UUID           NOT NULL REFERENCES suppliers(id),
    payment_date      DATE           NOT NULL,
    method            VARCHAR(15)    NOT NULL,          -- BANK_TRANSFER, CASH (from the vault), MOBILE_MONEY
    reference         VARCHAR(60),
    currency_code     VARCHAR(3)     NOT NULL REFERENCES currencies(code),
    amount            NUMERIC(18,2)  NOT NULL,          -- in the currency paid
    rate              NUMERIC(18,6),                    -- that day's rate (null in RWF)
    rate_date         DATE,
    rate_source       VARCHAR(10),
    base_amount       NUMERIC(18,2)  NOT NULL,          -- RWF paid
    settled_base      NUMERIC(18,2)  NOT NULL,          -- RWF the settled items were booked at
    fx_gain_loss      NUMERIC(18,2)  NOT NULL,          -- settled - paid: positive a gain, negative a loss (ACC-08)
    notes             VARCHAR(255),
    posted_at         TIMESTAMP      NOT NULL,
    posted_by         VARCHAR(50)    NOT NULL,
    created_at        TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by        VARCHAR(50),
    updated_at        TIMESTAMP,
    updated_by        VARCHAR(50),
    CONSTRAINT uk_supplier_payments_number UNIQUE (number),
    CONSTRAINT chk_supplier_payments_method CHECK (method IN ('BANK_TRANSFER', 'CASH', 'MOBILE_MONEY')),
    CONSTRAINT chk_supplier_payments_reference CHECK (method = 'CASH' OR reference IS NOT NULL),
    CONSTRAINT chk_supplier_payments_amounts CHECK (amount > 0 AND base_amount > 0 AND settled_base > 0),
    CONSTRAINT chk_supplier_payments_fx CHECK (fx_gain_loss = settled_base - base_amount)
);
CREATE INDEX idx_supplier_payments_supplier ON supplier_payments (supplier_id, payment_date);

-- A supplier's statement reads the payable lines that name them
CREATE INDEX idx_journal_lines_account_supplier ON journal_lines (account_id, supplier_id) WHERE supplier_id IS NOT NULL;

ALTER TABLE journal_entries DROP CONSTRAINT chk_journal_entries_source;
ALTER TABLE journal_entries ADD CONSTRAINT chk_journal_entries_source CHECK (source_type IN ('GOODS_RECEIPT', 'SHIPMENT',
    'CLAIM_OPENED', 'CLAIM_SETTLED', 'CLAIM_REJECTED', 'CUTTING_JOB', 'ADJUSTMENT', 'OPENING_STOCK',
    'SALES_INVOICE', 'TILL_OPENED', 'TILL_CLOSED', 'SALES_DELIVERY', 'SALES_BALANCE', 'CREDIT_NOTE', 'CUSTOMER_PAYMENT',
    'SUPPLIER_INVOICE', 'SUPPLIER_PAYMENT'));

INSERT INTO number_sequences (doc_type, branch_code, prefix, created_by) VALUES
('SUPPLIER_INVOICE', 'WH', 'SINV', 'system'),
('SUPPLIER_PAYMENT', 'WH', 'SPAY', 'system');

-- ---------------------------------------------------------------------
-- Pages, permissions, grants (SRS 2.2: the accountant records supplier
-- invoices and pays suppliers; procurement, the owner and the auditor
-- read the accounts)
-- ---------------------------------------------------------------------
INSERT INTO pages (code, name, module, path, icon, sort_order) VALUES
('SUPPLIER_INVOICES', 'Supplier Invoices', 'Procurement', '/supplier-invoices', 'file-text', 6),
('SUPPLIER_PAYMENTS', 'Supplier Payments', 'Procurement', '/supplier-payments', 'credit-card', 7),
('PAYABLES',          'Payables',          'Accounting',  '/accounting/payables', 'clock', 5);

INSERT INTO permissions (code, name, module, action, description) VALUES
('VIEW_SUPPLIER_ACCOUNT',   'View Supplier Accounts',   'Procurement', 'VIEW',   'View what is owed to suppliers, their statements, invoices and payments'),
('RECORD_SUPPLIER_INVOICE', 'Record Supplier Invoices', 'Procurement', 'RECORD', 'Match a supplier''s invoice to its goods receipts'),
('PAY_SUPPLIER',            'Pay Suppliers',            'Procurement', 'PAY',    'Pay what is owed to a supplier');

INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE (p.code IN ('SUPPLIER_INVOICES', 'SUPPLIER_PAYMENTS') AND r.code IN ('ADMIN', 'PROCUREMENT', 'ACCOUNTANT', 'OWNER', 'AUDITOR'))
   OR (p.code = 'PAYABLES' AND r.code IN ('ADMIN', 'ACCOUNTANT', 'OWNER', 'AUDITOR'))
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE (p.code = 'VIEW_SUPPLIER_ACCOUNT' AND r.code IN ('ADMIN', 'PROCUREMENT', 'ACCOUNTANT', 'OWNER', 'AUDITOR'))
   OR (p.code IN ('RECORD_SUPPLIER_INVOICE', 'PAY_SUPPLIER') AND r.code IN ('ADMIN', 'ACCOUNTANT'))
ON CONFLICT DO NOTHING;
