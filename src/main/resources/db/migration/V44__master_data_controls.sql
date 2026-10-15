-- =====================================================================
-- V44: Master data controls (M11 of the plan to cover the client's restructuring documents; not in SRS v2.1)
--   - products: a maximum stock level beside the reorder level (STC-003)
--   - customers: know-your-customer data (SDP 11, F-02 A-B), documents, and the yearly credit review (F-02 C)
--   - credit applications: credit terms asked, assessed by Credit Control, reviewed by the Director of Finance and
--     approved within the Delegation of Authority (F-02 C-D)
--   - suppliers: the approved supplier register (registration, bank details, references, documents; CMP 08)
--   - price changes approved by the Managing Director before they apply (SDP 5)
--   - the monthly review of master data changes (Board Paper 11 and Table 9)
--   - goods receipts: the delivery's vehicle, driver, transporter, invoice and declaration (GRN-001, F-06 A)
--   - landed cost: demurrage and storage as cost types of their own (F-06 C)
-- =====================================================================

-- ---------------------------------------------------------------------
-- Products: a maximum stock level (STC-003)
-- ---------------------------------------------------------------------
ALTER TABLE products ADD COLUMN max_level_m2 NUMERIC(10,4);                 -- empty = no maximum
ALTER TABLE products ADD CONSTRAINT chk_products_max_level CHECK (max_level_m2 IS NULL
    OR (max_level_m2 > 0 AND (reorder_level_m2 IS NULL OR max_level_m2 >= reorder_level_m2)));

-- ---------------------------------------------------------------------
-- Customers: know-your-customer data and the credit review (SDP 11, F-02)
-- ---------------------------------------------------------------------
ALTER TABLE customers
    ADD COLUMN registration_number VARCHAR(40),        -- RDB company code or national ID of a sole trader
    ADD COLUMN district            VARCHAR(60),
    ADD COLUMN country_code        CHAR(2),
    ADD COLUMN bank_name           VARCHAR(80),
    ADD COLUMN bank_account        VARCHAR(40),
    ADD COLUMN momo_number         VARCHAR(30),        -- the registered mobile money number
    ADD COLUMN expected_monthly    NUMERIC(18,2),      -- expected purchases a month, RWF
    ADD COLUMN trade_references    VARCHAR(500),
    ADD COLUMN security_offered    VARCHAR(255),       -- guarantee, cheque, deposit...
    ADD COLUMN credit_reviewed_on  DATE,               -- the last approved credit application
    ADD COLUMN credit_review_due   DATE;               -- the next review; empty with credit = never reviewed
ALTER TABLE customers ADD CONSTRAINT chk_customers_expected CHECK (expected_monthly IS NULL OR expected_monthly >= 0);
ALTER TABLE customers ADD CONSTRAINT chk_customers_country CHECK (country_code IS NULL OR country_code ~ '^[A-Z]{2}$');
CREATE INDEX idx_customers_phone ON customers (phone) WHERE phone IS NOT NULL;
CREATE INDEX idx_customers_review_due ON customers (credit_review_due);

-- ---------------------------------------------------------------------
-- Credit applications (F-02 C-D)
-- ---------------------------------------------------------------------
CREATE TABLE credit_applications (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version                 BIGINT        NOT NULL DEFAULT 0,
    number                  VARCHAR(30)   NOT NULL,
    customer_id             UUID          NOT NULL REFERENCES customers(id),
    kind                    VARCHAR(10)   NOT NULL,              -- NEW (first terms), CHANGE, REVIEW (yearly or overdue)
    credit_limit            NUMERIC(18,2) NOT NULL,
    payment_terms_days      INTEGER       NOT NULL,
    price_list_id           UUID          REFERENCES price_lists(id),
    previous_limit          NUMERIC(18,2) NOT NULL,
    previous_terms_days     INTEGER       NOT NULL,
    previous_price_list_id  UUID          REFERENCES price_lists(id),
    reason                  VARCHAR(500)  NOT NULL,
    owed_when_asked         NUMERIC(18,2) NOT NULL,
    oldest_overdue_days     INTEGER,
    authority_level         VARCHAR(10)   NOT NULL,
    status                  VARCHAR(20)   NOT NULL,
    requested_by            VARCHAR(50)   NOT NULL,
    requested_by_id         BIGINT,
    requested_at            TIMESTAMP     NOT NULL,
    assessed_by             VARCHAR(50),
    assessed_by_id          BIGINT,
    assessed_at             TIMESTAMP,
    assessment_note         VARCHAR(500),
    reviewed_by             VARCHAR(50),
    reviewed_by_id          BIGINT,
    reviewed_at             TIMESTAMP,
    review_note             VARCHAR(255),
    decided_by              VARCHAR(50),
    decided_by_id           BIGINT,
    decided_at              TIMESTAMP,
    decision_note           VARCHAR(255),
    created_at              TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by              VARCHAR(50),
    updated_at              TIMESTAMP,
    updated_by              VARCHAR(50),
    CONSTRAINT uk_credit_applications_number UNIQUE (number),
    CONSTRAINT chk_credit_applications_kind CHECK (kind IN ('NEW', 'CHANGE', 'REVIEW')),
    CONSTRAINT chk_credit_applications_status CHECK (status IN ('PENDING_ASSESSMENT', 'PENDING_REVIEW', 'PENDING_APPROVAL',
        'APPROVED', 'REJECTED', 'WITHDRAWN')),
    CONSTRAINT chk_credit_applications_level CHECK (authority_level IN ('DIRECTOR', 'COO', 'MD', 'BOARD')),
    CONSTRAINT chk_credit_applications_terms CHECK (credit_limit >= 0 AND payment_terms_days BETWEEN 0 AND 365
        AND previous_limit >= 0 AND previous_terms_days BETWEEN 0 AND 365),
    CONSTRAINT chk_credit_applications_assessed CHECK (status = 'PENDING_ASSESSMENT' OR status = 'WITHDRAWN'
        OR status = 'REJECTED' OR assessed_at IS NOT NULL),
    CONSTRAINT chk_credit_applications_decided CHECK (status IN ('PENDING_ASSESSMENT', 'PENDING_REVIEW', 'PENDING_APPROVAL')
        OR (decided_by IS NOT NULL AND decided_at IS NOT NULL))
);
CREATE INDEX idx_credit_applications_customer ON credit_applications (customer_id, requested_at DESC);
CREATE INDEX idx_credit_applications_status ON credit_applications (status);

-- ---------------------------------------------------------------------
-- Suppliers: the approved supplier register (CMP 08)
-- ---------------------------------------------------------------------
ALTER TABLE suppliers
    ADD COLUMN registration_number VARCHAR(40),
    ADD COLUMN bank_name           VARCHAR(80),
    ADD COLUMN bank_account        VARCHAR(40),
    ADD COLUMN bank_swift          VARCHAR(11),
    ADD COLUMN trade_references    VARCHAR(500),
    ADD COLUMN approval_status     VARCHAR(10) NOT NULL DEFAULT 'APPROVED',   -- suppliers already used stay approved
    ADD COLUMN approved_by         VARCHAR(50),
    ADD COLUMN approved_by_id      BIGINT,
    ADD COLUMN approved_at         TIMESTAMP,
    ADD COLUMN approval_note       VARCHAR(255);
ALTER TABLE suppliers ALTER COLUMN approval_status DROP DEFAULT;
ALTER TABLE suppliers ADD CONSTRAINT chk_suppliers_approval CHECK (approval_status IN ('PENDING', 'APPROVED', 'REJECTED'));
ALTER TABLE suppliers ADD CONSTRAINT chk_suppliers_swift CHECK (bank_swift IS NULL OR bank_swift ~ '^[A-Z0-9]{8}([A-Z0-9]{3})?$');

-- ---------------------------------------------------------------------
-- Price changes approved before they apply (SDP 5)
-- ---------------------------------------------------------------------
CREATE TABLE price_changes (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version          BIGINT        NOT NULL DEFAULT 0,
    number           VARCHAR(30)   NOT NULL,
    price_list_id    UUID          NOT NULL REFERENCES price_lists(id),
    reason           VARCHAR(255)  NOT NULL,
    authority_level  VARCHAR(10)   NOT NULL,
    status           VARCHAR(10)   NOT NULL,
    requested_by     VARCHAR(50)   NOT NULL,
    requested_by_id  BIGINT,
    requested_at     TIMESTAMP     NOT NULL,
    decided_by       VARCHAR(50),
    decided_by_id    BIGINT,
    decided_at       TIMESTAMP,
    decision_note    VARCHAR(255),
    created_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by       VARCHAR(50),
    updated_at       TIMESTAMP,
    updated_by       VARCHAR(50),
    CONSTRAINT uk_price_changes_number UNIQUE (number),
    CONSTRAINT chk_price_changes_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN')),
    CONSTRAINT chk_price_changes_level CHECK (authority_level IN ('DIRECTOR', 'COO', 'MD', 'BOARD')),
    CONSTRAINT chk_price_changes_decided CHECK (status = 'PENDING' OR (decided_by IS NOT NULL AND decided_at IS NOT NULL))
);
CREATE INDEX idx_price_changes_list ON price_changes (price_list_id, requested_at DESC);
CREATE INDEX idx_price_changes_status ON price_changes (status);

-- What a price change asks, fixed once asked: a glass's price per m2 or a processing price, from the old to the new
-- (empty = not priced on the list)
CREATE TABLE price_change_lines (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    change_id   UUID          NOT NULL REFERENCES price_changes(id),
    line_no     INTEGER       NOT NULL,
    product_id  UUID          REFERENCES products(id),
    service_id  UUID          REFERENCES processing_services(id),
    old_price   NUMERIC(18,2),
    new_price   NUMERIC(18,2),
    CONSTRAINT uk_price_change_lines_no UNIQUE (change_id, line_no),
    CONSTRAINT chk_price_change_lines_item CHECK ((product_id IS NULL) <> (service_id IS NULL)),
    CONSTRAINT chk_price_change_lines_price CHECK ((old_price IS NULL OR old_price > 0) AND (new_price IS NULL OR new_price > 0)
        AND old_price IS DISTINCT FROM new_price)
);
CREATE INDEX idx_price_change_lines_change ON price_change_lines (change_id);
CREATE TRIGGER trg_price_change_lines_append_only BEFORE UPDATE OR DELETE ON price_change_lines
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_modification();

-- ---------------------------------------------------------------------
-- The monthly review of master data changes (Board Paper 11, Table 9)
-- ---------------------------------------------------------------------
CREATE TABLE master_data_reviews (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version          BIGINT        NOT NULL DEFAULT 0,
    period_start     DATE          NOT NULL,                     -- the month reviewed (its first day)
    changes          INTEGER       NOT NULL,
    note             VARCHAR(500),
    reviewed_by      VARCHAR(50)   NOT NULL,
    reviewed_by_id   BIGINT,
    reviewed_at      TIMESTAMP     NOT NULL,
    created_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by       VARCHAR(50),
    updated_at       TIMESTAMP,
    updated_by       VARCHAR(50),
    CONSTRAINT uk_master_data_reviews_period UNIQUE (period_start),
    CONSTRAINT chk_master_data_reviews_period CHECK (EXTRACT(DAY FROM period_start) = 1),
    CONSTRAINT chk_master_data_reviews_changes CHECK (changes >= 0)
);

-- ---------------------------------------------------------------------
-- Goods receipts: the delivery's papers (GRN-001, F-06 A)
-- ---------------------------------------------------------------------
ALTER TABLE goods_receipts
    ADD COLUMN vehicle_plate       VARCHAR(20),
    ADD COLUMN driver_name         VARCHAR(100),
    ADD COLUMN transporter         VARCHAR(100),
    ADD COLUMN invoice_number      VARCHAR(60),      -- the supplier's invoice or packing list
    ADD COLUMN declaration_number  VARCHAR(60);      -- the import declaration

-- ---------------------------------------------------------------------
-- Landed cost: demurrage and storage of their own (F-06 C)
-- ---------------------------------------------------------------------
ALTER TABLE shipment_costs DROP CONSTRAINT chk_shipment_costs_type;
ALTER TABLE shipment_costs ADD CONSTRAINT chk_shipment_costs_type CHECK (cost_type IN
    ('FREIGHT', 'INSURANCE', 'DUTY', 'CLEARING', 'PORT', 'TRANSPORT', 'DEMURRAGE', 'STORAGE', 'OTHER'));

-- ---------------------------------------------------------------------
-- Documents on customers, suppliers and credit applications
-- ---------------------------------------------------------------------
ALTER TABLE attachments DROP CONSTRAINT chk_attachments_owner;
ALTER TABLE attachments ADD CONSTRAINT chk_attachments_owner CHECK (owner_type IN
    ('GOODS_RECEIPT', 'SHIPMENT', 'SUPPLIER_INVOICE', 'STOCK_ADJUSTMENT', 'VEHICLE', 'DRIVER', 'TRIP', 'VEHICLE_COST',
     'CREDIT_NOTE_REQUEST', 'RECEIVABLE_WRITE_OFF', 'CUSTOMER', 'SUPPLIER', 'CREDIT_APPLICATION'));

-- ---------------------------------------------------------------------
-- Credit reviews due are told to Credit Control
-- ---------------------------------------------------------------------
ALTER TABLE notifications DROP CONSTRAINT chk_notifications_kind;
ALTER TABLE notifications ADD CONSTRAINT chk_notifications_kind
    CHECK (kind IN ('LOW_STOCK', 'APPROVAL', 'DECISION', 'SYSTEM', 'EBM', 'FLEET', 'SYNC', 'EOD', 'CREDIT'));

-- ---------------------------------------------------------------------
-- Delegation of Authority: credit limits and price changes (Annex E)
-- ---------------------------------------------------------------------
ALTER TABLE authority_limits DROP CONSTRAINT chk_authority_limits_decision;
ALTER TABLE authority_limits ADD CONSTRAINT chk_authority_limits_decision CHECK (decision IN ('DISCOUNT', 'BELOW_COST',
    'CREDIT_EXCEPTION', 'CREDIT_NOTE', 'RECEIVABLE_WRITE_OFF', 'STOCK_ADJUSTMENT', 'PURCHASE_ORDER', 'SUPPLIER_PAYMENT',
    'MANUAL_JOURNAL', 'VEHICLE_COST', 'CREDIT_LIMIT', 'PRICE_CHANGE'));

INSERT INTO authority_limits (decision, level, allowed, max_amount, created_by) VALUES
('CREDIT_LIMIT',  'DIRECTOR', FALSE, NULL,        'system'),
('CREDIT_LIMIT',  'COO',      TRUE,  5000000,     'system'),
('CREDIT_LIMIT',  'MD',       TRUE,  20000000,    'system'),
('CREDIT_LIMIT',  'BOARD',    TRUE,  NULL,        'system'),
('PRICE_CHANGE',  'DIRECTOR', FALSE, NULL,        'system'),
('PRICE_CHANGE',  'COO',      FALSE, NULL,        'system'),
('PRICE_CHANGE',  'MD',       TRUE,  NULL,        'system'),
('PRICE_CHANGE',  'BOARD',    TRUE,  NULL,        'system');

-- ---------------------------------------------------------------------
-- Numbers and settings
-- ---------------------------------------------------------------------
INSERT INTO number_sequences (doc_type, branch_code, prefix, reset_policy, padding, created_by) VALUES
('CREDIT_APPLICATION', 'WH', 'CAP', 'YEARLY', 6, 'system'),
('PRICE_CHANGE',       'WH', 'PCH', 'YEARLY', 6, 'system');

INSERT INTO settings (setting_key, setting_value, created_by) VALUES
('approval.credit-application',        'true',  'system'),   -- credit terms change only through an approved application
('approval.credit-application-review', 'true',  'system'),   -- the Director of Finance reviews it after Credit Control
('credit.review-months',               '12',    'system'),   -- an approved limit is reviewed after so many months
('credit.review-overdue-days',         '90',    'system'),   -- and as soon as an invoice is so many days overdue
('approval.price-change',              'true',  'system'),   -- prices change only once approved
('approval.supplier-onboarding',       'true',  'system'),   -- a new supplier is approved before it is used
('supplier.references-required',       '2',     'system');   -- references a supplier needs to be approved

-- ---------------------------------------------------------------------
-- Security
-- ---------------------------------------------------------------------
INSERT INTO pages (code, name, module, path, icon, sort_order) VALUES
('CREDIT_APPLICATIONS', 'Credit Applications', 'Sales & POS', '/credit-applications', 'file-text', 9),
('MASTER_DATA_REPORT',  'Master Data Changes', 'Reports',     '/reports/master-data', 'list',      41);

INSERT INTO permissions (code, name, module, action, description) VALUES
('REQUEST_CREDIT_TERMS',        'Ask for Credit Terms',              'Customers',  'CREATE',  'Ask for a customer''s credit limit, payment terms or price list to change'),
('ASSESS_CREDIT',               'Assess Credit Applications',        'Customers',  'REVIEW',  'Assess a credit application before it is reviewed and approved'),
('REVIEW_CREDIT_APPLICATION',   'Review Credit Applications',        'Customers',  'REVIEW',  'Review a credit application after Credit Control assessed it'),
('APPROVE_CREDIT_APPLICATION',  'Approve Credit Applications',       'Customers',  'APPROVE', 'Approve or reject a credit application within the Delegation of Authority'),
('APPROVE_SUPPLIER',            'Approve Suppliers',                 'Suppliers',  'APPROVE', 'Approve or reject a new supplier before it is ordered from or paid'),
('APPROVE_PRICE_CHANGE',        'Approve Price Changes',             'Price Lists', 'APPROVE', 'Approve or reject prices changed by someone else'),
('VIEW_MASTER_DATA_REPORT',     'View Master Data Changes',          'Reports',    'VIEW',    'See the month''s changes to customers, suppliers, glass and prices'),
('REVIEW_MASTER_DATA',          'Review Master Data Changes',        'Reports',    'REVIEW',  'Record the monthly review of master data changes'),
('ALERT_CREDIT_REVIEW',         'Credit Review Alerts',              'Customers',  'ALERT',   'Be told when a customer''s credit limit is due for review');

INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE (p.code = 'CREDIT_APPLICATIONS' AND r.code IN ('ADMIN', 'OWNER', 'ACCOUNTANT', 'CASHIER', 'AUDITOR', 'COO', 'DIRECTOR_FINANCE',
        'CREDIT_CONTROL', 'DIRECTOR_COMMERCIAL', 'ORDER_DESK', 'SALES_RECORDING', 'INTERNAL_CONTROLLER'))
   OR (p.code = 'MASTER_DATA_REPORT' AND r.code IN ('ADMIN', 'OWNER', 'ACCOUNTANT', 'AUDITOR', 'COO', 'DIRECTOR_FINANCE',
        'REPORTING_TAX_BUDGET', 'INTERNAL_CONTROLLER'))
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE (r.code = 'ADMIN' AND p.code IN ('REQUEST_CREDIT_TERMS', 'ASSESS_CREDIT', 'REVIEW_CREDIT_APPLICATION', 'APPROVE_CREDIT_APPLICATION',
        'APPROVE_SUPPLIER', 'APPROVE_PRICE_CHANGE', 'VIEW_MASTER_DATA_REPORT', 'REVIEW_MASTER_DATA', 'ALERT_CREDIT_REVIEW'))
   OR (r.code = 'OWNER' AND p.code IN ('APPROVE_CREDIT_APPLICATION', 'APPROVE_SUPPLIER', 'APPROVE_PRICE_CHANGE',
        'VIEW_MASTER_DATA_REPORT', 'REVIEW_MASTER_DATA'))
   OR (r.code = 'ACCOUNTANT' AND p.code IN ('REQUEST_CREDIT_TERMS', 'ASSESS_CREDIT', 'VIEW_MASTER_DATA_REPORT', 'ALERT_CREDIT_REVIEW'))
   OR (r.code = 'CASHIER' AND p.code = 'REQUEST_CREDIT_TERMS')
   OR (r.code = 'AUDITOR' AND p.code = 'VIEW_MASTER_DATA_REPORT')
   OR (r.code = 'INTERNAL_CONTROLLER' AND p.code IN ('VIEW_MASTER_DATA_REPORT', 'REVIEW_MASTER_DATA'))
   OR (r.code = 'COO' AND p.code IN ('APPROVE_CREDIT_APPLICATION', 'APPROVE_SUPPLIER', 'VIEW_MASTER_DATA_REPORT'))
   OR (r.code = 'DIRECTOR_FINANCE' AND p.code IN ('REVIEW_CREDIT_APPLICATION', 'VIEW_MASTER_DATA_REPORT', 'REVIEW_MASTER_DATA',
        'ALERT_CREDIT_REVIEW'))
   OR (r.code = 'REPORTING_TAX_BUDGET' AND p.code = 'VIEW_MASTER_DATA_REPORT')
   OR (r.code = 'CREDIT_CONTROL' AND p.code IN ('REQUEST_CREDIT_TERMS', 'ASSESS_CREDIT', 'ALERT_CREDIT_REVIEW'))
   OR (r.code IN ('DIRECTOR_COMMERCIAL', 'ORDER_DESK', 'SALES_RECORDING') AND p.code = 'REQUEST_CREDIT_TERMS')
   OR (r.code = 'DIRECTOR_SUPPLY_CHAIN' AND p.code = 'APPROVE_SUPPLIER')
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------
-- Corrections to V42 found while documenting it
-- ---------------------------------------------------------------------
-- Credit Control, Sales Recording and the Director Commercial were given the Receivables page without the right to read
-- the accounts it shows, so the page refused them
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code IN ('CREDIT_CONTROL', 'SALES_RECORDING', 'DIRECTOR_COMMERCIAL') AND p.code = 'VIEW_ACCOUNTING'
ON CONFLICT DO NOTHING;

-- A Director (the warehouse manager, the supervisor) approves stock adjustments up to 500,000 RWF again, as before the
-- Delegation of Authority; the COO above that (Annex E stand-in amounts: the Board replaces them)
UPDATE authority_limits SET allowed = TRUE, max_amount = 500000, updated_by = 'system', updated_at = CURRENT_TIMESTAMP
WHERE decision = 'STOCK_ADJUSTMENT' AND level = 'DIRECTOR';
