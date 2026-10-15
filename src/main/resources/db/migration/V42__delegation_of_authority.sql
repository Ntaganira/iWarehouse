-- =====================================================================
-- V42: the Delegation of Authority (client controls M10: Board Paper
-- 11.2, 13.1 and Annex E; Forms Manual F-03; Cost Management Policy 05).
-- Each decision that needs another person's approval names, per office
-- (Director, COO, Managing Director, Board), whether that office may
-- approve it and up to what amount or percentage. A request needs the
-- lowest office whose limit covers it; a higher office may always decide
-- it, and the Board decides whatever no office below it may (recorded by
-- a holder of the Board's authority with the resolution). The amounts
-- seeded here stand in for the Board's Annex E until it sets them.
-- Counter exceptions gain discount tiers, a below-cost check, a block on
-- customers with overdue invoices, extended terms, approval in part and
-- an optional recommendation before the decision. The posts of the new
-- structure become roles, and access is reviewed each quarter.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Who may approve how much
-- ---------------------------------------------------------------------
CREATE TABLE authority_limits (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version      BIGINT        NOT NULL DEFAULT 0,
    decision     VARCHAR(30)   NOT NULL,
    level        VARCHAR(10)   NOT NULL,                  -- DIRECTOR, COO, MD, BOARD
    allowed      BOOLEAN       NOT NULL,                  -- may this office decide it at all
    max_amount   NUMERIC(18,2),                           -- up to and including (RWF, or % for DISCOUNT); NULL: no limit
    created_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by   VARCHAR(50),
    updated_at   TIMESTAMP,
    updated_by   VARCHAR(50),
    CONSTRAINT uk_authority_limits_decision_level UNIQUE (decision, level),
    CONSTRAINT chk_authority_limits_decision CHECK (decision IN ('DISCOUNT', 'BELOW_COST', 'CREDIT_EXCEPTION', 'CREDIT_NOTE',
        'RECEIVABLE_WRITE_OFF', 'STOCK_ADJUSTMENT', 'PURCHASE_ORDER', 'SUPPLIER_PAYMENT', 'MANUAL_JOURNAL', 'VEHICLE_COST')),
    CONSTRAINT chk_authority_limits_level CHECK (level IN ('DIRECTOR', 'COO', 'MD', 'BOARD')),
    CONSTRAINT chk_authority_limits_amount CHECK (max_amount IS NULL OR max_amount >= 0),
    CONSTRAINT chk_authority_limits_board CHECK (level <> 'BOARD' OR (allowed AND max_amount IS NULL)),
    CONSTRAINT chk_authority_limits_off CHECK (allowed OR max_amount IS NULL)
);

-- Annex E's offices with stand-in amounts (RWF; DISCOUNT in %): the Board replaces them
INSERT INTO authority_limits (decision, level, allowed, max_amount, created_by) VALUES
('DISCOUNT',             'DIRECTOR', FALSE, NULL,        'system'),
('DISCOUNT',             'COO',      TRUE,  7,           'system'),
('DISCOUNT',             'MD',       TRUE,  NULL,        'system'),
('DISCOUNT',             'BOARD',    TRUE,  NULL,        'system'),
('BELOW_COST',           'DIRECTOR', FALSE, NULL,        'system'),
('BELOW_COST',           'COO',      FALSE, NULL,        'system'),
('BELOW_COST',           'MD',       TRUE,  NULL,        'system'),
('BELOW_COST',           'BOARD',    TRUE,  NULL,        'system'),
('CREDIT_EXCEPTION',     'DIRECTOR', FALSE, NULL,        'system'),
('CREDIT_EXCEPTION',     'COO',      TRUE,  2000000,     'system'),
('CREDIT_EXCEPTION',     'MD',       TRUE,  NULL,        'system'),
('CREDIT_EXCEPTION',     'BOARD',    TRUE,  NULL,        'system'),
('CREDIT_NOTE',          'DIRECTOR', FALSE, NULL,        'system'),
('CREDIT_NOTE',          'COO',      TRUE,  500000,      'system'),
('CREDIT_NOTE',          'MD',       TRUE,  2000000,     'system'),
('CREDIT_NOTE',          'BOARD',    TRUE,  NULL,        'system'),
('RECEIVABLE_WRITE_OFF', 'DIRECTOR', FALSE, NULL,        'system'),
('RECEIVABLE_WRITE_OFF', 'COO',      TRUE,  500000,      'system'),
('RECEIVABLE_WRITE_OFF', 'MD',       FALSE, NULL,        'system'),
('RECEIVABLE_WRITE_OFF', 'BOARD',    TRUE,  NULL,        'system'),
('STOCK_ADJUSTMENT',     'DIRECTOR', FALSE, NULL,        'system'),
('STOCK_ADJUSTMENT',     'COO',      TRUE,  2000000,     'system'),
('STOCK_ADJUSTMENT',     'MD',       FALSE, NULL,        'system'),
('STOCK_ADJUSTMENT',     'BOARD',    TRUE,  NULL,        'system'),
('PURCHASE_ORDER',       'DIRECTOR', TRUE,  5000000,     'system'),
('PURCHASE_ORDER',       'COO',      TRUE,  20000000,    'system'),
('PURCHASE_ORDER',       'MD',       TRUE,  NULL,        'system'),
('PURCHASE_ORDER',       'BOARD',    TRUE,  NULL,        'system'),
('SUPPLIER_PAYMENT',     'DIRECTOR', TRUE,  500000,      'system'),
('SUPPLIER_PAYMENT',     'COO',      TRUE,  20000000,    'system'),
('SUPPLIER_PAYMENT',     'MD',       TRUE,  NULL,        'system'),
('SUPPLIER_PAYMENT',     'BOARD',    TRUE,  NULL,        'system'),
('MANUAL_JOURNAL',       'DIRECTOR', TRUE,  5000000,     'system'),
('MANUAL_JOURNAL',       'COO',      TRUE,  NULL,        'system'),
('MANUAL_JOURNAL',       'MD',       TRUE,  NULL,        'system'),
('MANUAL_JOURNAL',       'BOARD',    TRUE,  NULL,        'system'),
('VEHICLE_COST',         'DIRECTOR', TRUE,  500000,      'system'),
('VEHICLE_COST',         'COO',      TRUE,  2000000,     'system'),
('VEHICLE_COST',         'MD',       TRUE,  NULL,        'system'),
('VEHICLE_COST',         'BOARD',    TRUE,  NULL,        'system');

-- ---------------------------------------------------------------------
-- Counter exceptions (F-03): extended terms, approval in part, the
-- office it needs, below cost, overdue invoices, a recommendation first
-- ---------------------------------------------------------------------
ALTER TABLE sale_approvals
    ADD COLUMN approved_price       NUMERIC(18,2),          -- approved in part: a price between the one asked and the list
    ADD COLUMN approved_credit      NUMERIC(18,2),          -- approved in part: less credit than asked
    ADD COLUMN below_cost           BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN cost_amount          NUMERIC(18,2),          -- PRICE below cost: what the glass cost (net of VAT)
    ADD COLUMN overdue_amount       NUMERIC(18,2),          -- CREDIT, TERMS: owed past due when asked
    ADD COLUMN oldest_overdue_days  INTEGER,
    ADD COLUMN extra_days           INTEGER,                -- TERMS: days added to the customer's terms for this invoice
    ADD COLUMN authority_level      VARCHAR(10),            -- the lowest office that may decide it, when asked
    ADD COLUMN recommended_by       VARCHAR(50),
    ADD COLUMN recommended_by_id    BIGINT,
    ADD COLUMN recommended_at       TIMESTAMP,
    ADD COLUMN recommendation_note  VARCHAR(200);

ALTER TABLE sale_approvals DROP CONSTRAINT chk_sale_approvals_kind;
ALTER TABLE sale_approvals ADD CONSTRAINT chk_sale_approvals_kind CHECK (kind IN ('PRICE', 'CREDIT', 'TERMS'));
ALTER TABLE sale_approvals DROP CONSTRAINT chk_sale_approvals_status;
ALTER TABLE sale_approvals ADD CONSTRAINT chk_sale_approvals_status CHECK (status IN ('PENDING', 'RECOMMENDED', 'APPROVED', 'REJECTED', 'WITHDRAWN'));
ALTER TABLE sale_approvals DROP CONSTRAINT chk_sale_approvals_decided;
ALTER TABLE sale_approvals ADD CONSTRAINT chk_sale_approvals_decided CHECK (status IN ('PENDING', 'RECOMMENDED')
    OR (decided_by IS NOT NULL AND decided_at IS NOT NULL));
ALTER TABLE sale_approvals ADD CONSTRAINT chk_sale_approvals_terms CHECK (kind <> 'TERMS' OR extra_days BETWEEN 1 AND 365);
ALTER TABLE sale_approvals ADD CONSTRAINT chk_sale_approvals_recommended CHECK (status <> 'RECOMMENDED'
    OR (recommended_by IS NOT NULL AND recommended_at IS NOT NULL));
ALTER TABLE sale_approvals ADD CONSTRAINT chk_sale_approvals_level CHECK (authority_level IS NULL
    OR authority_level IN ('DIRECTOR', 'COO', 'MD', 'BOARD'));
ALTER TABLE sale_approvals ADD CONSTRAINT chk_sale_approvals_partly CHECK ((approved_price IS NULL OR approved_price > 0)
    AND (approved_credit IS NULL OR approved_credit > 0));

-- An invoice paid later than the customer's terms, as approved (F-03 "extended terms"): the ageing adds the days
ALTER TABLE sales_invoices ADD COLUMN extra_terms_days INTEGER;
ALTER TABLE sales_invoices ADD CONSTRAINT chk_sales_invoices_extra_terms CHECK (extra_terms_days IS NULL
    OR extra_terms_days BETWEEN 1 AND 365);

-- A phone sale below what its glass cost is refused like any other price outside the rules (SDP 8)
ALTER TABLE mobile_sync_conflicts DROP CONSTRAINT chk_mobile_sync_conflicts_reason;
ALTER TABLE mobile_sync_conflicts ADD CONSTRAINT chk_mobile_sync_conflicts_reason CHECK (reason IN
    ('NO_TRIP', 'NOT_YOUR_TRIP', 'UNIT_NOT_ON_VEHICLE', 'UNIT_REPORTED_BROKEN', 'NUMBER_NOT_ISSUED', 'NUMBER_USED', 'NO_PRICE',
     'PRICE_BELOW_LIMIT', 'TOTAL_MISMATCH', 'PAYMENT_MISMATCH', 'INVALID', 'BELOW_COST'));

-- ---------------------------------------------------------------------
-- The quarterly access review (Board Paper Table 8)
-- ---------------------------------------------------------------------
CREATE TABLE access_reviews (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version         BIGINT        NOT NULL DEFAULT 0,
    reviewed_at     TIMESTAMP     NOT NULL,
    reviewed_by     VARCHAR(50)   NOT NULL,
    users_count     INTEGER       NOT NULL,
    enabled_count   INTEGER       NOT NULL,
    note            VARCHAR(500)  NOT NULL,                 -- what was checked, what was changed
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by      VARCHAR(50),
    updated_at      TIMESTAMP,
    updated_by      VARCHAR(50),
    CONSTRAINT chk_access_reviews_counts CHECK (users_count >= 0 AND enabled_count BETWEEN 0 AND users_count)
);
CREATE INDEX idx_access_reviews_at ON access_reviews (reviewed_at);

-- ---------------------------------------------------------------------
-- Settings: overdue invoices block credit (SDP 11), below-cost prices
-- need approval (SDP 8), and a recommendation before a counter exception
-- is decided (F-03; off until the counter wants the second step)
-- ---------------------------------------------------------------------
INSERT INTO settings (setting_key, setting_value, created_by) VALUES
('credit.block-overdue',      'true',  'system'),
('credit.overdue-grace-days', '0',     'system'),
('approval.below-cost',       'true',  'system'),
('approval.sale-recommend',   'false', 'system');

-- ---------------------------------------------------------------------
-- Security: the four offices, who sets their limits, the recommendation,
-- the access review and the exceptions report
-- ---------------------------------------------------------------------
INSERT INTO pages (code, name, module, path, icon, sort_order) VALUES
('EXCEPTIONS_REPORT', 'Exceptions Report', 'Reports', '/reports/exceptions', 'alert', 40);

INSERT INTO permissions (code, name, module, action, description) VALUES
('AUTHORITY_DIRECTOR',      'Authority: Director',            'Approvals',      'AUTHORITY', 'Approve within the Director column of the Delegation of Authority'),
('AUTHORITY_COO',           'Authority: Chief Operating Officer', 'Approvals',  'AUTHORITY', 'Approve within the COO column of the Delegation of Authority'),
('AUTHORITY_MD',            'Authority: Managing Director',   'Approvals',      'AUTHORITY', 'Approve within the Managing Director column of the Delegation of Authority'),
('AUTHORITY_BOARD',         'Authority: Board',               'Approvals',      'AUTHORITY', 'Record a decision the Board took above every other limit, with its resolution'),
('MANAGE_AUTHORITY',        'Manage the Delegation of Authority', 'Settings',   'EDIT',      'Change which office may approve what, and up to what amount'),
('RECOMMEND_SALE_APPROVAL', 'Recommend Sale Exceptions',      'Sales',          'RECOMMEND', 'Recommend a counter exception before it is decided'),
('REVIEW_ACCESS',           'Review User Access',             'Administration', 'REVIEW',    'Record the quarterly review of who holds which rights'),
('VIEW_EXCEPTIONS_REPORT',  'View the Exceptions Report',     'Reports',        'VIEW',      'See the month''s approvals, credit notes, write-offs and adjustments');

-- The existing approvers keep deciding what they decided: the owner as the Managing Director (and the Board's
-- recorder), the accountant and the warehouse supervisor as Directors
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE (r.code = 'ADMIN' AND p.code IN ('AUTHORITY_DIRECTOR', 'AUTHORITY_COO', 'AUTHORITY_MD', 'AUTHORITY_BOARD', 'MANAGE_AUTHORITY',
                                        'RECOMMEND_SALE_APPROVAL', 'REVIEW_ACCESS', 'VIEW_EXCEPTIONS_REPORT'))
   OR (r.code = 'OWNER' AND p.code IN ('AUTHORITY_MD', 'AUTHORITY_BOARD', 'MANAGE_AUTHORITY', 'REVIEW_ACCESS', 'VIEW_EXCEPTIONS_REPORT'))
   OR (r.code = 'ACCOUNTANT' AND p.code IN ('AUTHORITY_DIRECTOR', 'VIEW_EXCEPTIONS_REPORT'))
   OR (r.code = 'WAREHOUSE_SUPERVISOR' AND p.code = 'AUTHORITY_DIRECTOR')
   OR (r.code = 'AUDITOR' AND p.code = 'VIEW_EXCEPTIONS_REPORT')
ON CONFLICT DO NOTHING;

INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE p.code = 'EXCEPTIONS_REPORT' AND r.code IN ('ADMIN', 'OWNER', 'ACCOUNTANT', 'AUDITOR')
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------
-- The posts of the new structure (Board Paper Annex B) as roles. The
-- Managing Director keeps the Owner role, the Regional Sales Managers the
-- Driver role and the technicians the Cutting operator role. Later
-- milestones add the rights their own screens need (the Order Desk's
-- orders, the Inventory Transactions Officer's tickets).
-- ---------------------------------------------------------------------
INSERT INTO roles (name, code, description, enabled) VALUES
('Chief Operating Officer',              'COO',                    'Drives execution; approves pricing exceptions, credit, write-offs and spending within the Board''s limits', TRUE),
('Director of Finance & Accounting',     'DIRECTOR_FINANCE',       'Leads Finance: reviews and approves what others prepare, authorises payments; posts no entries, handles no cash', TRUE),
('Financial Reporting, Tax & Budget',    'REPORTING_TAX_BUDGET',   'Management and statutory accounts, VAT and EBM, landed cost, budget; reviews credit notes', TRUE),
('Treasury & Bank Reconciliation',       'TREASURY',               'Prepares supplier payments, reconciles bank and mobile money, verifies the cashier''s count', TRUE),
('Credit Control & Receivables',         'CREDIT_CONTROL',         'Customer accounts, limits and follow-up; prepares credit notes and write-offs, never approves them', TRUE),
('Book-keeping, Payables & Cashier',     'BOOKKEEPING_CASHIER',    'Takes payments at the till, records supplier invoices, issues approved credit notes; approves nothing', TRUE),
('Director of Commercial',               'DIRECTOR_COMMERCIAL',    'Both markets: prices, loads to the field, selling discipline; recommends counter exceptions', TRUE),
('Frontline Order Desk',                 'ORDER_DESK',             'The single entry point for orders: quotations and orders at list prices; handles no cash, releases no stock', TRUE),
('Sales Recording Officer',              'SALES_RECORDING',        'Reviews the day''s sales and proves them against the cash; sells nothing, holds no cash', TRUE),
('Director of Supply Chain & Distribution', 'DIRECTOR_SUPPLY_CHAIN', 'Supplier to customer: purchasing, imports, warehouses, stock integrity; approves orders within limits', TRUE),
('Warehouse & Inventory Manager',        'WAREHOUSE_MANAGER',      'Custody of a warehouse: receiving, racks, transfers, counts, cutting, release of goods', TRUE),
('Inventory Transactions Officer',       'INVENTORY_TRANSACTIONS', 'Raises the document of every stock movement for verification', TRUE),
('Dispatch & Distribution Officer',      'DISPATCH_OFFICER',       'Trips and loads, vehicles and drivers, checks goods released', TRUE),
('Internal Controller & Compliance',     'INTERNAL_CONTROLLER',    'Independent testing of controls: reads everything, changes nothing', TRUE),
('Security & Risk Assurance Manager',    'SECURITY_RISK',          'Losses, breakage, missing stock and vehicles on the road: reads and is told', TRUE)
ON CONFLICT DO NOTHING;

-- Every post opens the dashboard, its notifications and its own activity
INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE r.code IN ('COO', 'DIRECTOR_FINANCE', 'REPORTING_TAX_BUDGET', 'TREASURY', 'CREDIT_CONTROL', 'BOOKKEEPING_CASHIER',
                 'DIRECTOR_COMMERCIAL', 'ORDER_DESK', 'SALES_RECORDING', 'DIRECTOR_SUPPLY_CHAIN', 'WAREHOUSE_MANAGER',
                 'INVENTORY_TRANSACTIONS', 'DISPATCH_OFFICER', 'INTERNAL_CONTROLLER', 'SECURITY_RISK')
  AND p.code IN ('DASHBOARD', 'NOTIFICATIONS', 'MY_ACTIVITY')
ON CONFLICT DO NOTHING;
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code IN ('COO', 'DIRECTOR_FINANCE', 'REPORTING_TAX_BUDGET', 'TREASURY', 'CREDIT_CONTROL', 'BOOKKEEPING_CASHIER',
                 'DIRECTOR_COMMERCIAL', 'ORDER_DESK', 'SALES_RECORDING', 'DIRECTOR_SUPPLY_CHAIN', 'WAREHOUSE_MANAGER',
                 'INVENTORY_TRANSACTIONS', 'DISPATCH_OFFICER', 'INTERNAL_CONTROLLER', 'SECURITY_RISK')
  AND p.code IN ('VIEW_DASHBOARD', 'VIEW_NOTIFICATIONS')
ON CONFLICT DO NOTHING;

-- The Internal Controller reads what the auditor reads; the Warehouse & Inventory Manager does what the warehouse
-- supervisor does but approve adjustments; the Dispatch Officer does what the fleet manager does
INSERT INTO role_pages (role_id, page_id)
SELECT n.id, rp.page_id FROM roles n JOIN roles o ON (n.code, o.code) IN
    (('INTERNAL_CONTROLLER', 'AUDITOR'), ('WAREHOUSE_MANAGER', 'WAREHOUSE_SUPERVISOR'), ('DISPATCH_OFFICER', 'FLEET_MANAGER'))
JOIN role_pages rp ON rp.role_id = o.id
ON CONFLICT DO NOTHING;
INSERT INTO role_permissions (role_id, permission_id)
SELECT n.id, rp.permission_id FROM roles n JOIN roles o ON (n.code, o.code) IN
    (('INTERNAL_CONTROLLER', 'AUDITOR'), ('WAREHOUSE_MANAGER', 'WAREHOUSE_SUPERVISOR'), ('DISPATCH_OFFICER', 'FLEET_MANAGER'))
JOIN role_permissions rp ON rp.role_id = o.id
JOIN permissions p ON p.id = rp.permission_id
WHERE NOT (n.code = 'WAREHOUSE_MANAGER' AND p.code IN ('APPROVE_ADJUSTMENT', 'AUTHORITY_DIRECTOR'))
ON CONFLICT DO NOTHING;

-- The other posts, screen by screen and right by right
INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE (r.code = 'COO' AND p.code IN ('SALE_APPROVALS', 'STOCK_ADJUSTMENTS', 'VEHICLE_COSTS', 'CREDIT_NOTES', 'PROCUREMENT',
        'SUPPLIER_PAYMENTS', 'MANUAL_JOURNALS', 'REPORTS', 'SALES_REPORTS', 'FLEET_REPORTS', 'EXCEPTIONS_REPORT', 'STOCK',
        'STOCK_SUMMARY', 'INVOICES', 'CUSTOMERS', 'RECEIVABLES', 'PAYABLES', 'SUPPLIERS', 'ACCOUNTING', 'TRIAL_BALANCE',
        'FINANCIAL_STATEMENTS', 'FLEET', 'AUDIT_CASES'))
   OR (r.code = 'DIRECTOR_FINANCE' AND p.code IN ('ACCOUNTING', 'ACCOUNTS', 'TRIAL_BALANCE', 'FINANCIAL_STATEMENTS', 'VAT_REPORT',
        'PERIODS', 'FX_REVALUATIONS', 'RECONCILIATIONS', 'MANUAL_JOURNALS', 'RECEIVABLES', 'PAYABLES', 'CREDIT_NOTES',
        'SUPPLIER_INVOICES', 'SUPPLIER_PAYMENTS', 'CUSTOMER_PAYMENTS', 'INVOICES', 'TILL_SESSIONS', 'FLOATS', 'PAY_RUNS',
        'EMPLOYEES', 'REPORTS', 'SALES_REPORTS', 'EXCEPTIONS_REPORT', 'CUSTOMERS', 'SUPPLIERS'))
   OR (r.code = 'REPORTING_TAX_BUDGET' AND p.code IN ('ACCOUNTING', 'ACCOUNTS', 'TRIAL_BALANCE', 'FINANCIAL_STATEMENTS', 'VAT_REPORT',
        'FX_REVALUATIONS', 'MANUAL_JOURNALS', 'EBM', 'SHIPMENTS', 'CURRENCIES', 'CREDIT_NOTES', 'INVOICES', 'PAY_RUNS',
        'REPORTS', 'SALES_REPORTS', 'STOCK_SUMMARY'))
   OR (r.code = 'TREASURY' AND p.code IN ('ACCOUNTING', 'RECONCILIATIONS', 'SUPPLIER_PAYMENTS', 'PAYABLES', 'SUPPLIERS',
        'TILL_SESSIONS', 'FLOATS', 'CUSTOMER_PAYMENTS'))
   OR (r.code = 'CREDIT_CONTROL' AND p.code IN ('CUSTOMERS', 'RECEIVABLES', 'CREDIT_NOTES', 'INVOICES', 'CUSTOMER_PAYMENTS',
        'REPORTS', 'SALES_REPORTS'))
   OR (r.code = 'BOOKKEEPING_CASHIER' AND p.code IN ('POS', 'TILL_SESSIONS', 'CUSTOMER_PAYMENTS', 'SUPPLIER_INVOICES',
        'CREDIT_NOTES', 'INVOICES', 'CUSTOMERS', 'SUPPLIERS', 'MANUAL_JOURNALS', 'ACCOUNTING', 'STOCK', 'PRICE_LISTS'))
   OR (r.code = 'DIRECTOR_COMMERCIAL' AND p.code IN ('PRICE_LISTS', 'CUSTOMERS', 'QUOTATIONS', 'INVOICES', 'SALE_APPROVALS',
        'FLEET', 'SYNC_CONFLICTS', 'REPORTS', 'SALES_REPORTS', 'FLEET_REPORTS', 'RECEIVABLES', 'STOCK', 'STOCK_SUMMARY'))
   OR (r.code = 'ORDER_DESK' AND p.code IN ('QUOTATIONS', 'CUSTOMERS', 'STOCK', 'STOCK_SUMMARY', 'PRICE_LISTS', 'INVOICES'))
   OR (r.code = 'SALES_RECORDING' AND p.code IN ('INVOICES', 'CREDIT_NOTES', 'TILL_SESSIONS', 'FLEET', 'RECEIVABLES',
        'REPORTS', 'SALES_REPORTS'))
   OR (r.code = 'DIRECTOR_SUPPLY_CHAIN' AND p.code IN ('PROCUREMENT', 'RECEIVING', 'SHIPMENTS', 'SUPPLIERS', 'STOCK',
        'STOCK_SUMMARY', 'STOCK_COUNTS', 'STOCK_TRANSFERS', 'STOCK_ADJUSTMENTS', 'LOCATIONS', 'PRODUCTS', 'PRODUCTION',
        'CUTTING_YIELD', 'FLEET', 'VEHICLES', 'REPORTS', 'FLEET_REPORTS'))
   OR (r.code = 'INVENTORY_TRANSACTIONS' AND p.code IN ('RECEIVING', 'STOCK', 'STOCK_SUMMARY', 'STOCK_TRANSFERS',
        'STOCK_ADJUSTMENTS', 'STOCK_COUNTS', 'PROCUREMENT'))
   OR (r.code = 'SECURITY_RISK' AND p.code IN ('FLEET', 'AUDIT_CASES', 'VEHICLES', 'DRIVERS', 'FLOATS', 'REPORTS',
        'FLEET_REPORTS', 'STOCK_ADJUSTMENTS'))
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE (r.code = 'COO' AND p.code IN ('AUTHORITY_COO', 'APPROVE_SALE', 'APPROVE_ADJUSTMENT', 'APPROVE_VEHICLE_COST',
        'APPROVE_MANUAL_JOURNAL', 'VIEW_BUSINESS_DASHBOARD', 'VIEW_STOCK', 'VIEW_STOCK_COST', 'VIEW_STOCK_ADJUSTMENT',
        'VIEW_VEHICLE_COST', 'VIEW_CREDIT_NOTE', 'VIEW_PURCHASE_ORDER', 'VIEW_SUPPLIER', 'VIEW_SUPPLIER_ACCOUNT', 'VIEW_INVOICE',
        'VIEW_CUSTOMER', 'VIEW_CUSTOMER_ACCOUNT', 'VIEW_ACCOUNTING', 'VIEW_SALES_REPORTS', 'VIEW_FLEET_REPORTS',
        'VIEW_EXCEPTIONS_REPORT', 'VIEW_TRIP', 'VIEW_AUDIT_CASES'))
   OR (r.code = 'DIRECTOR_FINANCE' AND p.code IN ('AUTHORITY_DIRECTOR', 'APPROVE_MANUAL_JOURNAL', 'REVERSE_MANUAL_JOURNAL',
        'APPROVE_PAY_RUN', 'CLOSE_PERIOD', 'REOPEN_PERIOD', 'VIEW_ACCOUNTING', 'VIEW_BUSINESS_DASHBOARD', 'VIEW_CREDIT_NOTE',
        'VIEW_INVOICE', 'VIEW_CUSTOMER', 'VIEW_CUSTOMER_ACCOUNT', 'VIEW_SUPPLIER', 'VIEW_SUPPLIER_ACCOUNT', 'VIEW_TILL_SESSION',
        'VIEW_FLOATS', 'VIEW_PAY_RUN', 'VIEW_EMPLOYEE', 'VIEW_SALES_REPORTS', 'VIEW_EXCEPTIONS_REPORT', 'VIEW_STOCK_COST'))
   OR (r.code = 'REPORTING_TAX_BUDGET' AND p.code IN ('VIEW_ACCOUNTING', 'CREATE_MANUAL_JOURNAL', 'REVALUE_FX', 'MANAGE_EBM',
        'MANAGE_SHIPMENT', 'POST_LANDED_COST', 'VIEW_SHIPMENT', 'MANAGE_EXCHANGE_RATE', 'VIEW_CURRENCY', 'VIEW_CREDIT_NOTE',
        'VIEW_INVOICE', 'VIEW_PAY_RUN', 'VIEW_SALES_REPORTS', 'VIEW_STOCK', 'VIEW_STOCK_COST'))
   OR (r.code = 'TREASURY' AND p.code IN ('RECONCILE_ACCOUNT', 'PAY_SUPPLIER', 'VIEW_ACCOUNTING', 'VIEW_SUPPLIER',
        'VIEW_SUPPLIER_ACCOUNT', 'VIEW_TILL_SESSION', 'VIEW_FLOATS', 'VIEW_CUSTOMER'))
   OR (r.code = 'CREDIT_CONTROL' AND p.code IN ('MANAGE_CUSTOMER', 'MANAGE_CUSTOMER_TERMS', 'VIEW_CUSTOMER', 'VIEW_CUSTOMER_ACCOUNT',
        'RETURN_SALE', 'VIEW_CREDIT_NOTE', 'VIEW_INVOICE', 'VIEW_SALES_REPORTS'))
   OR (r.code = 'BOOKKEEPING_CASHIER' AND p.code IN ('SELL', 'RECEIVE_CUSTOMER_PAYMENT', 'RECORD_SUPPLIER_INVOICE',
        'CREATE_MANUAL_JOURNAL', 'VIEW_CREDIT_NOTE', 'VIEW_INVOICE', 'VIEW_CUSTOMER', 'VIEW_CUSTOMER_ACCOUNT', 'VIEW_SUPPLIER',
        'VIEW_SUPPLIER_ACCOUNT', 'VIEW_TILL_SESSION', 'VIEW_ACCOUNTING', 'VIEW_STOCK', 'VIEW_PRICE_LIST', 'VIEW_PRODUCT'))
   OR (r.code = 'DIRECTOR_COMMERCIAL' AND p.code IN ('AUTHORITY_DIRECTOR', 'RECOMMEND_SALE_APPROVAL', 'MANAGE_PRICE_LIST',
        'VIEW_PRICE_LIST', 'DEPART_TRIP', 'REVIEW_SYNC_CONFLICT', 'VIEW_SYNC_CONFLICTS', 'VIEW_TRIP', 'VIEW_CUSTOMER',
        'VIEW_CUSTOMER_ACCOUNT', 'VIEW_QUOTATION', 'VIEW_INVOICE', 'VIEW_SALES_REPORTS', 'VIEW_FLEET_REPORTS', 'VIEW_STOCK'))
   OR (r.code = 'ORDER_DESK' AND p.code IN ('MANAGE_QUOTATION', 'VIEW_QUOTATION', 'MANAGE_CUSTOMER', 'VIEW_CUSTOMER',
        'VIEW_CUSTOMER_ACCOUNT', 'VIEW_STOCK', 'VIEW_PRICE_LIST', 'VIEW_PRODUCT', 'VIEW_INVOICE'))
   OR (r.code = 'SALES_RECORDING' AND p.code IN ('VIEW_INVOICE', 'VIEW_CREDIT_NOTE', 'VIEW_TILL_SESSION', 'VIEW_TRIP',
        'VIEW_CUSTOMER_ACCOUNT', 'VIEW_SALES_REPORTS'))
   OR (r.code = 'DIRECTOR_SUPPLY_CHAIN' AND p.code IN ('AUTHORITY_DIRECTOR', 'MANAGE_PURCHASE_ORDER', 'VIEW_PURCHASE_ORDER',
        'MANAGE_SHIPMENT', 'VIEW_SHIPMENT', 'VIEW_GOODS_RECEIPT', 'VIEW_SUPPLIER', 'VIEW_STOCK', 'VIEW_STOCK_COST', 'VIEW_STOCK_COUNT',
        'VIEW_STOCK_TRANSFER', 'VIEW_STOCK_ADJUSTMENT', 'VIEW_LOCATION', 'VIEW_PRODUCT', 'VIEW_CUTTING_JOB', 'VIEW_CUTTING_YIELD',
        'VIEW_TRIP', 'VIEW_VEHICLE', 'VIEW_FLEET_REPORTS'))
   OR (r.code = 'INVENTORY_TRANSACTIONS' AND p.code IN ('ADJUST_STOCK', 'VIEW_STOCK', 'VIEW_GOODS_RECEIPT', 'VIEW_STOCK_TRANSFER',
        'VIEW_STOCK_ADJUSTMENT', 'VIEW_STOCK_COUNT', 'VIEW_PURCHASE_ORDER', 'PRINT_LABEL'))
   OR (r.code = 'SECURITY_RISK' AND p.code IN ('VIEW_TRIP', 'VIEW_AUDIT_CASES', 'VIEW_VEHICLE', 'VIEW_DRIVER', 'VIEW_FLOATS',
        'VIEW_FLEET_REPORTS', 'VIEW_STOCK_ADJUSTMENT', 'ALERT_EOD', 'ALERT_FLEET', 'ALERT_FLOAT'))
   OR (r.code = 'INTERNAL_CONTROLLER' AND p.code = 'VIEW_EXCEPTIONS_REPORT')
ON CONFLICT DO NOTHING;
INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE r.code = 'INTERNAL_CONTROLLER' AND p.code = 'EXCEPTIONS_REPORT'
ON CONFLICT DO NOTHING;
