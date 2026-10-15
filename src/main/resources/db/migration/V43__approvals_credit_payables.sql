-- =====================================================================
-- V43: approvals on credit and spending (client controls M10, second
-- part: Forms Manual F-14, Cost Management Policy 06-07, Board Paper
-- Table 6 and 11.2). A credit note is prepared, reviewed and approved
-- before it is issued; a receivable is written off only on approval; a
-- purchase order above a Settings limit waits for approval; a supplier
-- payment is prepared, then authorised (twice above a Settings amount);
-- a goods receipt is posted by someone other than who drafted it. Each
-- approval follows the Delegation of Authority (V42). The posts made
-- roles in V42 take their authority name ROLE_<code>, as every role does.
-- =====================================================================

-- The roles of V42 are named as the others: their authority, ROLE_<code>
UPDATE roles SET name = 'ROLE_' || code
WHERE code IN ('COO', 'DIRECTOR_FINANCE', 'REPORTING_TAX_BUDGET', 'TREASURY', 'CREDIT_CONTROL', 'BOOKKEEPING_CASHIER',
               'DIRECTOR_COMMERCIAL', 'ORDER_DESK', 'SALES_RECORDING', 'DIRECTOR_SUPPLY_CHAIN', 'WAREHOUSE_MANAGER',
               'INVENTORY_TRANSACTIONS', 'DISPATCH_OFFICER', 'INTERNAL_CONTROLLER', 'SECURITY_RISK')
  AND name NOT LIKE 'ROLE\_%';

-- ---------------------------------------------------------------------
-- Credit note requests (F-14): CNR-WH-2026-000001, then the credit note
-- ---------------------------------------------------------------------
CREATE TABLE credit_note_requests (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version           BIGINT         NOT NULL DEFAULT 0,
    number            VARCHAR(30)    NOT NULL,
    kind              VARCHAR(10)    NOT NULL,                 -- RETURN (glass back) or CANCEL (pieces given up)
    invoice_id        UUID           NOT NULL REFERENCES sales_invoices(id),
    customer_id       UUID           NOT NULL REFERENCES customers(id),
    reason            VARCHAR(255)   NOT NULL,
    refund_reason     VARCHAR(2)     NOT NULL,                 -- the EBM refund reason
    refund_method     VARCHAR(15),
    refund_reference  VARCHAR(60),
    location_id       UUID           REFERENCES locations(id), -- where returned glass goes back
    net_amount        NUMERIC(18,2)  NOT NULL,                 -- as worked out when asked
    vat_amount        NUMERIC(18,2)  NOT NULL,
    total_amount      NUMERIC(18,2)  NOT NULL,
    authority_level   VARCHAR(10)    NOT NULL,
    status            VARCHAR(16)    NOT NULL,                 -- PENDING_REVIEW, PENDING_APPROVAL, APPROVED, REJECTED, WITHDRAWN, ISSUED
    requested_by      VARCHAR(50)    NOT NULL,
    requested_by_id   BIGINT,
    requested_at      TIMESTAMP      NOT NULL,
    reviewed_by       VARCHAR(50),
    reviewed_by_id    BIGINT,
    reviewed_at       TIMESTAMP,
    review_note       VARCHAR(255),
    decided_by        VARCHAR(50),
    decided_by_id     BIGINT,
    decided_at        TIMESTAMP,
    decision_note     VARCHAR(255),
    credit_note_id    UUID           REFERENCES credit_notes(id),
    issued_by         VARCHAR(50),
    issued_at         TIMESTAMP,
    created_at        TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by        VARCHAR(50),
    updated_at        TIMESTAMP,
    updated_by        VARCHAR(50),
    CONSTRAINT uk_credit_note_requests_number UNIQUE (number),
    CONSTRAINT chk_credit_note_requests_kind CHECK (kind IN ('RETURN', 'CANCEL')),
    CONSTRAINT chk_credit_note_requests_status CHECK (status IN ('PENDING_REVIEW', 'PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'WITHDRAWN', 'ISSUED')),
    CONSTRAINT chk_credit_note_requests_level CHECK (authority_level IN ('DIRECTOR', 'COO', 'MD', 'BOARD')),
    CONSTRAINT chk_credit_note_requests_totals CHECK (total_amount > 0 AND total_amount = net_amount + vat_amount),
    CONSTRAINT chk_credit_note_requests_decided CHECK (status NOT IN ('APPROVED', 'REJECTED', 'ISSUED') OR (decided_by IS NOT NULL AND decided_at IS NOT NULL)),
    CONSTRAINT chk_credit_note_requests_note CHECK (status NOT IN ('REJECTED', 'WITHDRAWN') OR decision_note IS NOT NULL),
    CONSTRAINT chk_credit_note_requests_issued CHECK ((status = 'ISSUED') = (credit_note_id IS NOT NULL AND issued_at IS NOT NULL))
);
CREATE INDEX idx_credit_note_requests_invoice ON credit_note_requests (invoice_id);
CREATE INDEX idx_credit_note_requests_status ON credit_note_requests (status, requested_at);

-- What it asks for: the units coming back (to a rack or to cullet), or the pieces given up per size; fixed once asked
CREATE TABLE credit_note_request_items (
    request_id  UUID     NOT NULL REFERENCES credit_note_requests(id),
    unit_id     UUID     NOT NULL REFERENCES stock_units(id),
    cullet      BOOLEAN  NOT NULL,
    CONSTRAINT pk_credit_note_request_items PRIMARY KEY (request_id, unit_id)
);
CREATE TABLE credit_note_request_sizes (
    request_id  UUID     NOT NULL REFERENCES credit_note_requests(id),
    line_id     UUID     NOT NULL REFERENCES sales_invoice_lines(id),
    quantity    INTEGER  NOT NULL,
    CONSTRAINT pk_credit_note_request_sizes PRIMARY KEY (request_id, line_id),
    CONSTRAINT chk_credit_note_request_sizes_quantity CHECK (quantity > 0)
);

ALTER TABLE credit_notes ADD COLUMN request_id UUID REFERENCES credit_note_requests(id);
CREATE UNIQUE INDEX uk_credit_notes_request ON credit_notes (request_id) WHERE request_id IS NOT NULL;

-- ---------------------------------------------------------------------
-- Receivable write-offs (F-14): RWO-WH-2026-000001, posted on approval
-- ---------------------------------------------------------------------
CREATE TABLE receivable_write_offs (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version           BIGINT         NOT NULL DEFAULT 0,
    number            VARCHAR(30)    NOT NULL,
    customer_id       UUID           NOT NULL REFERENCES customers(id),
    invoice_id        UUID           REFERENCES sales_invoices(id),    -- the invoice it is mainly about, if one
    amount            NUMERIC(18,2)  NOT NULL,
    owed_when_asked   NUMERIC(18,2)  NOT NULL,
    oldest_days       INTEGER,                                        -- how late the oldest charge was when asked
    reason            VARCHAR(255)   NOT NULL,
    recovery_action   VARCHAR(500),                                   -- what Credit Control tried first
    authority_level   VARCHAR(10)    NOT NULL,
    status            VARCHAR(16)    NOT NULL,                        -- PENDING_REVIEW, PENDING_APPROVAL, POSTED, REJECTED, WITHDRAWN
    requested_by      VARCHAR(50)    NOT NULL,
    requested_by_id   BIGINT,
    requested_at      TIMESTAMP      NOT NULL,
    reviewed_by       VARCHAR(50),
    reviewed_by_id    BIGINT,
    reviewed_at       TIMESTAMP,
    review_note       VARCHAR(255),
    decided_by        VARCHAR(50),
    decided_by_id     BIGINT,
    decided_at        TIMESTAMP,
    decision_note     VARCHAR(255),
    journal_id        UUID           REFERENCES journal_entries(id),
    posted_at         TIMESTAMP,
    created_at        TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by        VARCHAR(50),
    updated_at        TIMESTAMP,
    updated_by        VARCHAR(50),
    CONSTRAINT uk_receivable_write_offs_number UNIQUE (number),
    CONSTRAINT chk_receivable_write_offs_status CHECK (status IN ('PENDING_REVIEW', 'PENDING_APPROVAL', 'POSTED', 'REJECTED', 'WITHDRAWN')),
    CONSTRAINT chk_receivable_write_offs_level CHECK (authority_level IN ('DIRECTOR', 'COO', 'MD', 'BOARD')),
    CONSTRAINT chk_receivable_write_offs_amount CHECK (amount > 0 AND owed_when_asked >= amount),
    CONSTRAINT chk_receivable_write_offs_posted CHECK ((status = 'POSTED') = (journal_id IS NOT NULL AND posted_at IS NOT NULL)),
    CONSTRAINT chk_receivable_write_offs_note CHECK (status NOT IN ('REJECTED', 'WITHDRAWN') OR decision_note IS NOT NULL)
);
CREATE INDEX idx_receivable_write_offs_customer ON receivable_write_offs (customer_id);
CREATE INDEX idx_receivable_write_offs_status ON receivable_write_offs (status, requested_at);

INSERT INTO accounts (code, name, account_type, system_key, description, created_by) VALUES
('6210', 'Bad Debts', 'EXPENSE', 'BAD_DEBTS', 'Receivables written off on approval (F-14)', 'system');

ALTER TABLE journal_entries DROP CONSTRAINT chk_journal_entries_source;
ALTER TABLE journal_entries ADD CONSTRAINT chk_journal_entries_source CHECK (source_type IN
    ('GOODS_RECEIPT', 'SHIPMENT', 'CLAIM_OPENED', 'CLAIM_SETTLED', 'CLAIM_REJECTED', 'CUTTING_JOB', 'ADJUSTMENT',
     'OPENING_STOCK', 'SALES_INVOICE', 'TILL_OPENED', 'TILL_CLOSED', 'SALES_DELIVERY', 'SALES_BALANCE', 'CREDIT_NOTE',
     'CUSTOMER_PAYMENT', 'SUPPLIER_INVOICE', 'SUPPLIER_PAYMENT', 'FX_REVALUATION', 'MANUAL_JOURNAL', 'MOBILE_SALE',
     'TRIP_RETURN', 'AUDIT_CASE', 'FLOAT_CLEARANCE', 'PAYROLL', 'PAYROLL_PAYMENT', 'VEHICLE_COST', 'RECEIVABLE_WRITE_OFF'));

-- Evidence for a credit note or a write-off: a photo or a PDF
ALTER TABLE attachments DROP CONSTRAINT chk_attachments_owner;
ALTER TABLE attachments ADD CONSTRAINT chk_attachments_owner CHECK (owner_type IN
    ('GOODS_RECEIPT', 'SHIPMENT', 'SUPPLIER_INVOICE', 'STOCK_ADJUSTMENT', 'VEHICLE', 'DRIVER', 'TRIP', 'VEHICLE_COST',
     'CREDIT_NOTE_REQUEST', 'RECEIVABLE_WRITE_OFF'));

-- ---------------------------------------------------------------------
-- Purchase orders above the Settings limit wait for approval (CMP 06)
-- ---------------------------------------------------------------------
ALTER TABLE purchase_orders
    ADD COLUMN approval_amount   NUMERIC(18,2),          -- RWF at the day's rate when placed
    ADD COLUMN authority_level   VARCHAR(10),
    ADD COLUMN submitted_by      VARCHAR(50),
    ADD COLUMN submitted_by_id   BIGINT,
    ADD COLUMN submitted_at      TIMESTAMP,
    ADD COLUMN approved_by       VARCHAR(50),
    ADD COLUMN approved_at       TIMESTAMP,
    ADD COLUMN approval_note     VARCHAR(255);             -- the approver's note, or why it was sent back
ALTER TABLE purchase_orders DROP CONSTRAINT chk_purchase_orders_status;
ALTER TABLE purchase_orders ADD CONSTRAINT chk_purchase_orders_status CHECK (status IN
    ('DRAFT', 'PENDING_APPROVAL', 'ORDERED', 'PARTIALLY_RECEIVED', 'RECEIVED', 'CLOSED', 'CANCELLED'));
ALTER TABLE purchase_orders DROP CONSTRAINT chk_purchase_orders_placed;
ALTER TABLE purchase_orders ADD CONSTRAINT chk_purchase_orders_placed CHECK (status IN ('DRAFT', 'PENDING_APPROVAL', 'CANCELLED')
    OR ordered_at IS NOT NULL);
ALTER TABLE purchase_orders ADD CONSTRAINT chk_purchase_orders_submitted CHECK (status <> 'PENDING_APPROVAL'
    OR (submitted_at IS NOT NULL AND approval_amount IS NOT NULL AND authority_level IS NOT NULL));
ALTER TABLE purchase_orders ADD CONSTRAINT chk_purchase_orders_level CHECK (authority_level IS NULL
    OR authority_level IN ('DIRECTOR', 'COO', 'MD', 'BOARD'));

-- ---------------------------------------------------------------------
-- Supplier payments prepared, then authorised (CMP 07; Board Paper
-- Table 6: Treasury prepares, the Director of Finance and the COO
-- authorise): PRQ-WH-2026-000001
-- ---------------------------------------------------------------------
CREATE TABLE supplier_payment_requests (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version              BIGINT         NOT NULL DEFAULT 0,
    number               VARCHAR(30)    NOT NULL,
    supplier_id          UUID           NOT NULL REFERENCES suppliers(id),
    currency_code        VARCHAR(3)     NOT NULL REFERENCES currencies(code),
    amount               NUMERIC(18,2)  NOT NULL,
    method               VARCHAR(15)    NOT NULL,
    reference            VARCHAR(60),
    notes                VARCHAR(255),
    base_estimate        NUMERIC(18,2)  NOT NULL,           -- RWF at the rate of the day it was prepared
    authority_level      VARCHAR(10)    NOT NULL,
    authorisations       SMALLINT       NOT NULL,           -- 1, or 2 above the Settings amount
    status               VARCHAR(16)    NOT NULL,           -- PREPARED, AUTHORISED (once of two), PAID, REJECTED, CANCELLED
    prepared_by          VARCHAR(50)    NOT NULL,
    prepared_by_id       BIGINT,
    prepared_at          TIMESTAMP      NOT NULL,
    first_by             VARCHAR(50),
    first_by_id          BIGINT,
    first_level          VARCHAR(10),                       -- the office the first authoriser held
    first_at             TIMESTAMP,
    first_note           VARCHAR(255),
    second_by            VARCHAR(50),
    second_by_id         BIGINT,
    second_at            TIMESTAMP,
    second_note          VARCHAR(255),
    decided_by           VARCHAR(50),
    decided_at           TIMESTAMP,
    decision_note        VARCHAR(255),                      -- why rejected or cancelled
    payment_id           UUID           REFERENCES supplier_payments(id),
    created_at           TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by           VARCHAR(50),
    updated_at           TIMESTAMP,
    updated_by           VARCHAR(50),
    CONSTRAINT uk_supplier_payment_requests_number UNIQUE (number),
    CONSTRAINT chk_supplier_payment_requests_method CHECK (method IN ('BANK_TRANSFER', 'CASH', 'MOBILE_MONEY')),
    CONSTRAINT chk_supplier_payment_requests_reference CHECK (method = 'CASH' OR reference IS NOT NULL),
    CONSTRAINT chk_supplier_payment_requests_amount CHECK (amount > 0 AND base_estimate > 0),
    CONSTRAINT chk_supplier_payment_requests_count CHECK (authorisations IN (1, 2)),
    CONSTRAINT chk_supplier_payment_requests_status CHECK (status IN ('PREPARED', 'AUTHORISED', 'PAID', 'REJECTED', 'CANCELLED')),
    CONSTRAINT chk_supplier_payment_requests_level CHECK (authority_level IN ('DIRECTOR', 'COO', 'MD', 'BOARD')
        AND (first_level IS NULL OR first_level IN ('DIRECTOR', 'COO', 'MD', 'BOARD'))),
    CONSTRAINT chk_supplier_payment_requests_paid CHECK ((status = 'PAID') = (payment_id IS NOT NULL)),
    CONSTRAINT chk_supplier_payment_requests_first CHECK (status NOT IN ('AUTHORISED', 'PAID') OR first_at IS NOT NULL),
    CONSTRAINT chk_supplier_payment_requests_note CHECK (status NOT IN ('REJECTED', 'CANCELLED') OR decision_note IS NOT NULL)
);
CREATE INDEX idx_supplier_payment_requests_supplier ON supplier_payment_requests (supplier_id);
CREATE INDEX idx_supplier_payment_requests_status ON supplier_payment_requests (status, prepared_at);

ALTER TABLE supplier_payments ADD COLUMN request_id UUID REFERENCES supplier_payment_requests(id);
CREATE UNIQUE INDEX uk_supplier_payments_request ON supplier_payments (request_id) WHERE request_id IS NOT NULL;

INSERT INTO number_sequences (doc_type, branch_code, prefix, reset_policy, padding, created_by) VALUES
('CREDIT_NOTE_REQUEST',      'WH', 'CNR', 'YEARLY', 6, 'system'),
('RECEIVABLE_WRITE_OFF',     'WH', 'RWO', 'YEARLY', 6, 'system'),
('SUPPLIER_PAYMENT_REQUEST', 'WH', 'PRQ', 'YEARLY', 6, 'system');

-- ---------------------------------------------------------------------
-- Settings
-- ---------------------------------------------------------------------
INSERT INTO settings (setting_key, setting_value, created_by) VALUES
('approval.credit-note-required',         'true',   'system'),
('approval.credit-note-review',           'true',   'system'),
('approval.write-off-review',             'true',   'system'),
('approval.purchase-order-limit',         '0',      'system'),
('approval.supplier-payment-authorise',   'true',   'system'),
('approval.supplier-payment-dual-limit',  '500000', 'system'),
('approval.receipt-second-person',        'true',   'system');

-- ---------------------------------------------------------------------
-- Security
-- ---------------------------------------------------------------------
INSERT INTO pages (code, name, module, path, icon, sort_order) VALUES
('RECEIVABLE_WRITE_OFFS', 'Receivable Write-offs', 'Accounting', '/accounting/write-offs', 'file-text', 16);

INSERT INTO permissions (code, name, module, action, description) VALUES
('REVIEW_CREDIT_CONTROL',       'Review Credit Notes and Write-offs', 'Accounting',  'REVIEW',  'Review a credit note or a write-off before it is approved'),
('APPROVE_CREDIT_NOTE',         'Approve Credit Notes',               'Sales',       'APPROVE', 'Approve or reject a credit note prepared by someone else'),
('ISSUE_CREDIT_NOTE',           'Issue Credit Notes',                 'Sales',       'ISSUE',   'Issue an approved credit note: the glass comes back and the refund is paid'),
('VIEW_WRITE_OFF',              'View Receivable Write-offs',         'Accounting',  'VIEW',    'See the receivables written off and those asked'),
('PREPARE_WRITE_OFF',           'Prepare Receivable Write-offs',      'Accounting',  'CREATE',  'Ask for a customer''s debt to be written off, with the reason and the recovery tried'),
('APPROVE_WRITE_OFF',           'Approve Receivable Write-offs',      'Accounting',  'APPROVE', 'Approve or reject a write-off prepared by someone else'),
('APPROVE_PURCHASE_ORDER',      'Approve Purchase Orders',            'Procurement', 'APPROVE', 'Approve or send back a purchase order placed by someone else'),
('AUTHORISE_SUPPLIER_PAYMENT',  'Authorise Supplier Payments',        'Procurement', 'APPROVE', 'Authorise or reject a supplier payment prepared by someone else');

INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE p.code = 'RECEIVABLE_WRITE_OFFS'
  AND r.code IN ('ADMIN', 'OWNER', 'ACCOUNTANT', 'AUDITOR', 'COO', 'DIRECTOR_FINANCE', 'REPORTING_TAX_BUDGET', 'CREDIT_CONTROL',
                 'INTERNAL_CONTROLLER')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE (r.code = 'ADMIN' AND p.code IN ('REVIEW_CREDIT_CONTROL', 'APPROVE_CREDIT_NOTE', 'ISSUE_CREDIT_NOTE', 'VIEW_WRITE_OFF',
                                        'PREPARE_WRITE_OFF', 'APPROVE_WRITE_OFF', 'APPROVE_PURCHASE_ORDER', 'AUTHORISE_SUPPLIER_PAYMENT'))
   OR (r.code = 'OWNER' AND p.code IN ('APPROVE_CREDIT_NOTE', 'ISSUE_CREDIT_NOTE', 'VIEW_WRITE_OFF', 'APPROVE_WRITE_OFF',
                                        'APPROVE_PURCHASE_ORDER', 'AUTHORISE_SUPPLIER_PAYMENT'))
   OR (r.code = 'ACCOUNTANT' AND p.code IN ('REVIEW_CREDIT_CONTROL', 'VIEW_WRITE_OFF', 'PREPARE_WRITE_OFF', 'AUTHORISE_SUPPLIER_PAYMENT'))
   OR (r.code = 'CASHIER' AND p.code = 'ISSUE_CREDIT_NOTE')
   OR (r.code IN ('AUDITOR', 'INTERNAL_CONTROLLER') AND p.code = 'VIEW_WRITE_OFF')
   OR (r.code = 'COO' AND p.code IN ('APPROVE_CREDIT_NOTE', 'VIEW_WRITE_OFF', 'APPROVE_WRITE_OFF', 'APPROVE_PURCHASE_ORDER',
                                      'AUTHORISE_SUPPLIER_PAYMENT'))
   OR (r.code = 'DIRECTOR_FINANCE' AND p.code IN ('REVIEW_CREDIT_CONTROL', 'VIEW_WRITE_OFF', 'AUTHORISE_SUPPLIER_PAYMENT'))
   OR (r.code = 'REPORTING_TAX_BUDGET' AND p.code IN ('REVIEW_CREDIT_CONTROL', 'VIEW_WRITE_OFF'))
   OR (r.code = 'CREDIT_CONTROL' AND p.code IN ('VIEW_WRITE_OFF', 'PREPARE_WRITE_OFF'))
   OR (r.code = 'BOOKKEEPING_CASHIER' AND p.code = 'ISSUE_CREDIT_NOTE')
   OR (r.code = 'DIRECTOR_SUPPLY_CHAIN' AND p.code = 'APPROVE_PURCHASE_ORDER')
ON CONFLICT DO NOTHING;
