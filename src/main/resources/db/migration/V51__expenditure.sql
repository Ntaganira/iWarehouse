-- =====================================================================
-- V51: expenditure control (M17 of the coverage plan, first half: Cost Management & Expenditure Control Policy 05, 06,
-- 09, 10; Forms Manual F-15 A; not in SRS v2.1)
--   - cost centres, and a cost centre and a location on journal lines (T.05, T.06)
--   - expense bills from suppliers for utilities, services, fees, repairs, transport and capital items (C.07), approved by
--     the expenditure matrix of the Delegation of Authority (C.06)
--   - purchase requisitions with their quotes before any commitment (C.01, C.02); orders and bills cite one
-- =====================================================================

-- ---------------------------------------------------------------------
-- Cost centres (F-15 A: Commercial, Supply Chain, Finance, Head Office)
-- ---------------------------------------------------------------------
CREATE TABLE cost_centres (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version          BIGINT        NOT NULL DEFAULT 0,
    code             VARCHAR(10)   NOT NULL,
    name             VARCHAR(100)  NOT NULL,
    manager_user_id  BIGINT        REFERENCES users(id),
    manager_name     VARCHAR(100),
    enabled          BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by       VARCHAR(50),
    updated_at       TIMESTAMP,
    updated_by       VARCHAR(50),
    CONSTRAINT uk_cost_centres_code UNIQUE (code),
    CONSTRAINT chk_cost_centres_code CHECK (code ~ '^[A-Z0-9-]{2,10}$')
);

INSERT INTO cost_centres (code, name, created_by) VALUES
('COM', 'Commercial',   'system'),
('SC',  'Supply Chain', 'system'),
('FIN', 'Finance',      'system'),
('HO',  'Head Office',  'system');

-- What an expense is for (department) and where (a site or a vehicle) on its journal line
ALTER TABLE journal_lines
    ADD COLUMN cost_centre_id UUID REFERENCES cost_centres(id),
    ADD COLUMN location_id    UUID REFERENCES locations(id);
CREATE INDEX idx_journal_lines_cost_centre ON journal_lines (cost_centre_id) WHERE cost_centre_id IS NOT NULL;
CREATE INDEX idx_journal_lines_location ON journal_lines (location_id) WHERE location_id IS NOT NULL;

-- ---------------------------------------------------------------------
-- The accounts expense bills post to, by category (the accountant may rename and renumber them)
-- ---------------------------------------------------------------------
INSERT INTO accounts (code, name, account_type, system_key, description, created_by) VALUES
('1500', 'Fixed Assets',                          'ASSET',   'FIXED_ASSETS',          'Capital items bought on expense bills (CAPEX)', 'system'),
('6310', 'Utilities (electricity and water)',     'EXPENSE', 'EXPENSE_UTILITIES',     'Electricity, water and sewerage', 'system'),
('6320', 'Rent',                                  'EXPENSE', 'EXPENSE_RENT',          'Premises rent', 'system'),
('6330', 'Telephone and internet',                'EXPENSE', 'EXPENSE_COMMUNICATION', 'Telephone, internet and airtime', 'system'),
('6340', 'Professional fees',                     'EXPENSE', 'EXPENSE_PROFESSIONAL',  'Audit, legal, consulting and other professional services', 'system'),
('6350', 'Repairs and maintenance',               'EXPENSE', 'EXPENSE_REPAIRS',       'Repairs and maintenance of premises and equipment', 'system'),
('6360', 'Transport and delivery',                'EXPENSE', 'EXPENSE_TRANSPORT',     'Hired transport, deliveries and courier', 'system'),
('6370', 'Travel and per diem',                   'EXPENSE', 'EXPENSE_TRAVEL',        'Travel, accommodation and per diem', 'system'),
('6380', 'Office supplies and stationery',        'EXPENSE', 'EXPENSE_OFFICE',        'Stationery, printing and small office items', 'system'),
('6390', 'Security and cleaning',                 'EXPENSE', 'EXPENSE_SECURITY',      'Guarding, cleaning and waste', 'system'),
('6400', 'Bank charges',                          'EXPENSE', 'EXPENSE_BANK_CHARGES',  'Bank and mobile money charges', 'system'),
('6410', 'Marketing and advertising',             'EXPENSE', 'EXPENSE_MARKETING',     'Advertising, promotion and samples', 'system'),
('6420', 'Training',                              'EXPENSE', 'EXPENSE_TRAINING',      'Staff training', 'system'),
('6430', 'Insurance',                             'EXPENSE', 'EXPENSE_INSURANCE',     'Insurance premiums other than vehicles', 'system'),
('6440', 'Taxes and licences',                    'EXPENSE', 'EXPENSE_TAXES',         'Trading licences, property tax and fees', 'system'),
('6490', 'Other operating expenses',              'EXPENSE', 'EXPENSE_OTHER',         'Operating expenses of no other category', 'system');

-- ---------------------------------------------------------------------
-- Purchase requisitions (CMP 06: an approved PRF before any commitment, with the quotes its value needs)
-- ---------------------------------------------------------------------
CREATE TABLE purchase_requisitions (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version          BIGINT        NOT NULL DEFAULT 0,
    number           VARCHAR(30)   NOT NULL,              -- PRF-WH-2026-000001
    cost_centre_id   UUID          NOT NULL REFERENCES cost_centres(id),
    title            VARCHAR(150)  NOT NULL,
    justification    VARCHAR(1000),
    needed_by        DATE,
    budget_code      VARCHAR(30),
    budgeted         BOOLEAN       NOT NULL DEFAULT TRUE,
    nature           VARCHAR(10)   NOT NULL DEFAULT 'OPEX',
    estimated_total  NUMERIC(18,2) NOT NULL DEFAULT 0,
    sourcing         VARCHAR(20)   NOT NULL DEFAULT 'SINGLE_QUOTE',
    sourcing_ref     VARCHAR(100),                        -- the RFQ or tender reference
    chosen_quote_id  UUID,
    chosen_reason    VARCHAR(500),                        -- why a quote other than the cheapest
    status           VARCHAR(20)   NOT NULL DEFAULT 'DRAFT',
    requested_by     VARCHAR(50)   NOT NULL,
    requested_by_id  BIGINT,
    requested_at     TIMESTAMP     NOT NULL,
    submitted_at     TIMESTAMP,
    decided_by       VARCHAR(50),
    decided_by_id    BIGINT,
    decided_at       TIMESTAMP,
    decision_note    VARCHAR(500),
    used_by          VARCHAR(30),                         -- the order or bill that used it
    used_at          TIMESTAMP,
    created_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by       VARCHAR(50),
    updated_at       TIMESTAMP,
    updated_by       VARCHAR(50),
    CONSTRAINT uk_purchase_requisitions_number UNIQUE (number),
    CONSTRAINT chk_purchase_requisitions_status CHECK (status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'REJECTED', 'CANCELLED', 'CLOSED')),
    CONSTRAINT chk_purchase_requisitions_nature CHECK (nature IN ('OPEX', 'CAPEX')),
    CONSTRAINT chk_purchase_requisitions_sourcing CHECK (sourcing IN ('SINGLE_QUOTE', 'THREE_QUOTES', 'RFQ', 'TENDER')),
    CONSTRAINT chk_purchase_requisitions_total CHECK (estimated_total >= 0),
    CONSTRAINT chk_purchase_requisitions_decided CHECK (status NOT IN ('APPROVED', 'REJECTED', 'CLOSED') OR decided_at IS NOT NULL),
    CONSTRAINT chk_purchase_requisitions_reason CHECK (status NOT IN ('REJECTED', 'CANCELLED') OR decision_note IS NOT NULL),
    CONSTRAINT chk_purchase_requisitions_used CHECK (status <> 'CLOSED' OR used_by IS NOT NULL)
);
CREATE INDEX idx_purchase_requisitions_status ON purchase_requisitions (status);

CREATE TABLE purchase_requisition_lines (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version          BIGINT        NOT NULL DEFAULT 0,
    requisition_id   UUID          NOT NULL REFERENCES purchase_requisitions(id),
    line_no          INTEGER       NOT NULL,
    item             VARCHAR(200)  NOT NULL,
    quantity         NUMERIC(12,2) NOT NULL,
    unit             VARCHAR(20),
    unit_cost        NUMERIC(18,2) NOT NULL,
    amount           NUMERIC(18,2) NOT NULL,
    created_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by       VARCHAR(50),
    updated_at       TIMESTAMP,
    updated_by       VARCHAR(50),
    CONSTRAINT uk_purchase_requisition_lines_no UNIQUE (requisition_id, line_no) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT chk_purchase_requisition_lines_quantity CHECK (quantity > 0),
    CONSTRAINT chk_purchase_requisition_lines_cost CHECK (unit_cost >= 0 AND amount >= 0)
);

CREATE TABLE requisition_quotes (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version          BIGINT        NOT NULL DEFAULT 0,
    requisition_id   UUID          NOT NULL REFERENCES purchase_requisitions(id),
    supplier_id      UUID          REFERENCES suppliers(id),
    supplier_name    VARCHAR(150)  NOT NULL,
    quote_ref        VARCHAR(60),
    quote_date       DATE,
    amount           NUMERIC(18,2) NOT NULL,
    note             VARCHAR(255),
    created_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by       VARCHAR(50),
    updated_at       TIMESTAMP,
    updated_by       VARCHAR(50),
    CONSTRAINT chk_requisition_quotes_amount CHECK (amount > 0)
);
CREATE INDEX idx_requisition_quotes_requisition ON requisition_quotes (requisition_id);

ALTER TABLE purchase_requisitions ADD CONSTRAINT fk_purchase_requisitions_chosen_quote
    FOREIGN KEY (chosen_quote_id) REFERENCES requisition_quotes(id) DEFERRABLE INITIALLY DEFERRED;

-- An order cites its requisition
ALTER TABLE purchase_orders ADD COLUMN requisition_id UUID REFERENCES purchase_requisitions(id);

-- ---------------------------------------------------------------------
-- Expense bills (CMP 01, 05: utilities, services, professional fees, repairs, transport, capital items) owed to suppliers
-- ---------------------------------------------------------------------
CREATE TABLE expense_bills (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version              BIGINT        NOT NULL DEFAULT 0,
    number               VARCHAR(30)   NOT NULL,          -- EXP-WH-2026-000001
    supplier_id          UUID          NOT NULL REFERENCES suppliers(id),
    supplier_ref         VARCHAR(60)   NOT NULL,          -- the supplier's invoice number
    bill_date            DATE          NOT NULL,
    due_date             DATE,
    description          VARCHAR(255)  NOT NULL,
    nature               VARCHAR(10)   NOT NULL DEFAULT 'OPEX',
    budgeted             BOOLEAN       NOT NULL DEFAULT TRUE,
    emergency            BOOLEAN       NOT NULL DEFAULT FALSE,
    requisition_id       UUID          REFERENCES purchase_requisitions(id),
    net_amount           NUMERIC(18,2) NOT NULL DEFAULT 0,
    vat_amount           NUMERIC(18,2) NOT NULL DEFAULT 0,
    amount               NUMERIC(18,2) NOT NULL DEFAULT 0,
    decision             VARCHAR(30),                     -- the expenditure matrix's row (AuthorityDecision)
    status               VARCHAR(20)   NOT NULL DEFAULT 'DRAFT',
    entered_by           VARCHAR(50)   NOT NULL,
    entered_by_id        BIGINT,
    entered_at           TIMESTAMP     NOT NULL,
    submitted_at         TIMESTAMP,
    recommended_by       VARCHAR(50),
    recommended_by_id    BIGINT,
    recommended_at       TIMESTAMP,
    recommend_note       VARCHAR(500),
    reviewed_by          VARCHAR(50),
    reviewed_by_id       BIGINT,
    reviewed_at          TIMESTAMP,
    review_note          VARCHAR(500),
    decided_by           VARCHAR(50),
    decided_by_id        BIGINT,
    decided_at           TIMESTAMP,
    decision_note        VARCHAR(500),
    authority_level      VARCHAR(10),
    journal_id           UUID          REFERENCES journal_entries(id),
    posted_at            TIMESTAMP,
    ratified_by          VARCHAR(50),
    ratified_by_id       BIGINT,
    ratified_at          TIMESTAMP,
    ratify_note          VARCHAR(500),
    reversal_journal_id  UUID          REFERENCES journal_entries(id),
    reversal_date        DATE,
    reversal_reason      VARCHAR(500),
    reversed_by          VARCHAR(50),
    reversed_at          TIMESTAMP,
    created_at           TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by           VARCHAR(50),
    updated_at           TIMESTAMP,
    updated_by           VARCHAR(50),
    CONSTRAINT uk_expense_bills_number UNIQUE (number),
    CONSTRAINT chk_expense_bills_status CHECK (status IN ('DRAFT', 'SUBMITTED', 'RECOMMENDED', 'REVIEWED', 'APPROVED', 'REJECTED',
        'CANCELLED', 'REVERSED')),
    CONSTRAINT chk_expense_bills_nature CHECK (nature IN ('OPEX', 'CAPEX')),
    CONSTRAINT chk_expense_bills_amounts CHECK (net_amount >= 0 AND vat_amount >= 0 AND amount = net_amount + vat_amount),
    CONSTRAINT chk_expense_bills_posted CHECK (status NOT IN ('APPROVED', 'REVERSED') OR (journal_id IS NOT NULL AND posted_at IS NOT NULL)),
    CONSTRAINT chk_expense_bills_reason CHECK (status NOT IN ('REJECTED', 'CANCELLED') OR decision_note IS NOT NULL),
    CONSTRAINT chk_expense_bills_reversed CHECK (status <> 'REVERSED' OR (reversal_journal_id IS NOT NULL AND reversal_reason IS NOT NULL))
);
CREATE INDEX idx_expense_bills_status ON expense_bills (status);
CREATE INDEX idx_expense_bills_supplier_date ON expense_bills (supplier_id, bill_date);
-- One supplier invoice is billed once (duplicate claims: CMP 09)
CREATE UNIQUE INDEX uk_expense_bills_supplier_ref ON expense_bills (supplier_id, upper(supplier_ref))
    WHERE status NOT IN ('REJECTED', 'CANCELLED');

CREATE TABLE expense_bill_lines (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version          BIGINT        NOT NULL DEFAULT 0,
    bill_id          UUID          NOT NULL REFERENCES expense_bills(id),
    line_no          INTEGER       NOT NULL,
    category         VARCHAR(30)   NOT NULL,
    description      VARCHAR(255)  NOT NULL,
    cost_centre_id   UUID          NOT NULL REFERENCES cost_centres(id),
    location_id      UUID          REFERENCES locations(id),
    net_amount       NUMERIC(18,2) NOT NULL,
    vat_amount       NUMERIC(18,2) NOT NULL DEFAULT 0,
    created_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by       VARCHAR(50),
    updated_at       TIMESTAMP,
    updated_by       VARCHAR(50),
    CONSTRAINT uk_expense_bill_lines_no UNIQUE (bill_id, line_no) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT chk_expense_bill_lines_category CHECK (category IN ('UTILITIES', 'RENT', 'COMMUNICATION', 'PROFESSIONAL_FEES', 'REPAIRS',
        'TRANSPORT', 'TRAVEL', 'OFFICE', 'SECURITY_CLEANING', 'BANK_CHARGES', 'MARKETING', 'TRAINING', 'INSURANCE', 'TAXES_LICENCES',
        'OTHER', 'CAPITAL_ITEM')),
    CONSTRAINT chk_expense_bill_lines_amounts CHECK (net_amount > 0 AND vat_amount >= 0)
);

-- ---------------------------------------------------------------------
-- The expenditure matrix (CMP 05) in the Delegation of Authority: operating expenditure, capital expenditure,
-- unbudgeted spending and emergencies (the MD approves; the Board ratifies)
-- ---------------------------------------------------------------------
ALTER TABLE authority_limits DROP CONSTRAINT chk_authority_limits_decision;
ALTER TABLE authority_limits ADD CONSTRAINT chk_authority_limits_decision CHECK (decision IN ('DISCOUNT', 'BELOW_COST',
    'CREDIT_EXCEPTION', 'CREDIT_NOTE', 'RECEIVABLE_WRITE_OFF', 'STOCK_ADJUSTMENT', 'PURCHASE_ORDER', 'SUPPLIER_PAYMENT',
    'MANUAL_JOURNAL', 'VEHICLE_COST', 'CREDIT_LIMIT', 'PRICE_CHANGE', 'EXPENSE_OPEX', 'EXPENSE_CAPEX', 'EXPENSE_UNBUDGETED',
    'EXPENSE_EMERGENCY'));

INSERT INTO authority_limits (decision, level, allowed, max_amount, created_by) VALUES
('EXPENSE_OPEX',        'DIRECTOR', TRUE,  500000,  'system'),
('EXPENSE_OPEX',        'COO',      TRUE,  500000,  'system'),
('EXPENSE_OPEX',        'MD',       TRUE,  2000000, 'system'),
('EXPENSE_OPEX',        'BOARD',    TRUE,  NULL,    'system'),
('EXPENSE_CAPEX',       'DIRECTOR', FALSE, NULL,    'system'),
('EXPENSE_CAPEX',       'COO',      FALSE, NULL,    'system'),
('EXPENSE_CAPEX',       'MD',       TRUE,  5000000, 'system'),
('EXPENSE_CAPEX',       'BOARD',    TRUE,  NULL,    'system'),
('EXPENSE_UNBUDGETED',  'DIRECTOR', FALSE, NULL,    'system'),
('EXPENSE_UNBUDGETED',  'COO',      FALSE, NULL,    'system'),
('EXPENSE_UNBUDGETED',  'MD',       TRUE,  500000,  'system'),
('EXPENSE_UNBUDGETED',  'BOARD',    TRUE,  NULL,    'system'),
('EXPENSE_EMERGENCY',   'DIRECTOR', FALSE, NULL,    'system'),
('EXPENSE_EMERGENCY',   'COO',      FALSE, NULL,    'system'),
('EXPENSE_EMERGENCY',   'MD',       TRUE,  NULL,    'system'),
('EXPENSE_EMERGENCY',   'BOARD',    TRUE,  NULL,    'system');

ALTER TABLE journal_entries DROP CONSTRAINT chk_journal_entries_source;
ALTER TABLE journal_entries ADD CONSTRAINT chk_journal_entries_source CHECK (source_type IN
    ('GOODS_RECEIPT', 'SHIPMENT', 'CLAIM_OPENED', 'CLAIM_SETTLED', 'CLAIM_REJECTED', 'CUTTING_JOB', 'ADJUSTMENT',
     'OPENING_STOCK', 'SALES_INVOICE', 'TILL_OPENED', 'TILL_CLOSED', 'SALES_DELIVERY', 'SALES_BALANCE', 'CREDIT_NOTE',
     'CUSTOMER_PAYMENT', 'SUPPLIER_INVOICE', 'SUPPLIER_PAYMENT', 'FX_REVALUATION', 'MANUAL_JOURNAL', 'MOBILE_SALE',
     'TRIP_RETURN', 'AUDIT_CASE', 'FLOAT_CLEARANCE', 'PAYROLL', 'PAYROLL_PAYMENT', 'VEHICLE_COST', 'RECEIVABLE_WRITE_OFF',
     'CASH_DEPOSIT', 'TRIP_DEPOSIT', 'DAMAGE_RECOVERY', 'EXPENSE_BILL'));

ALTER TABLE attachments DROP CONSTRAINT chk_attachments_owner;
ALTER TABLE attachments ADD CONSTRAINT chk_attachments_owner CHECK (owner_type IN
    ('GOODS_RECEIPT', 'SHIPMENT', 'SUPPLIER_INVOICE', 'STOCK_ADJUSTMENT', 'VEHICLE', 'DRIVER', 'TRIP', 'VEHICLE_COST',
     'CREDIT_NOTE_REQUEST', 'RECEIVABLE_WRITE_OFF', 'CUSTOMER', 'SUPPLIER', 'CREDIT_APPLICATION', 'SITE_TRANSFER',
     'CUTTING_JOB', 'DELIVERY_NOTE', 'CASH_DEPOSIT', 'BALANCE_CONFIRMATION', 'DAILY_CLOSE', 'DAMAGE_REPORT', 'STOCK_COUNT',
     'STOCK_VARIANCE', 'EXPENSE_BILL', 'PURCHASE_REQUISITION'));

ALTER TABLE notifications DROP CONSTRAINT chk_notifications_kind;
ALTER TABLE notifications ADD CONSTRAINT chk_notifications_kind
    CHECK (kind IN ('LOW_STOCK', 'APPROVAL', 'DECISION', 'SYSTEM', 'EBM', 'FLEET', 'SYNC', 'EOD', 'CREDIT', 'TRANSFER',
                    'ORDER', 'DELIVERY', 'CLOSE', 'DAMAGE', 'VARIANCE', 'KPI', 'EXPENSE'));

-- ---------------------------------------------------------------------
-- Numbers and settings (the policy's figures)
-- ---------------------------------------------------------------------
INSERT INTO number_sequences (doc_type, branch_code, prefix, reset_policy, padding, created_by) VALUES
('EXPENSE_BILL',          'WH', 'EXP', 'YEARLY', 6, 'system'),
('PURCHASE_REQUISITION',  'WH', 'PRF', 'YEARLY', 6, 'system');

INSERT INTO settings (setting_key, setting_value, created_by) VALUES
('expense.requisition-required',          'true',    'system'),
('expense.requisition-from',              '0',       'system'),
('expense.requisition-tolerance-percent', '10',      'system'),
('expense.quotes-three-from',             '100000',  'system'),
('expense.rfq-from',                      '1000000', 'system'),
('expense.tender-from',                   '5000000', 'system'),
('expense.quote-documents',               'true',    'system'),
('expense.invoice-document',              'true',    'system'),
('expense.recommend',                     'true',    'system'),
('expense.finance-review-from',           '500000',  'system'),
('expense.split-days',                    '7',       'system'),
('expense.ratify-days',                   '10',      'system'),
('expense.fleet-cost-centre',             'SC',      'system');

-- ---------------------------------------------------------------------
-- Security
-- ---------------------------------------------------------------------
INSERT INTO pages (code, name, module, path, icon, sort_order) VALUES
('EXPENSE_BILLS',      'Expense Bills',      'Accounting', '/expenses',            'file-text', 30),
('COST_CENTRES',       'Cost Centres',       'Accounting', '/cost-centres',        'layers',    31),
('REQUISITIONS',       'Requisitions',       'Purchasing', '/requisitions',        'clipboard', 5),
('EXPENDITURE_REPORT', 'Expenditure Report', 'Reports',    '/reports/expenditure', 'list',      48);

INSERT INTO permissions (code, name, module, action, description) VALUES
('VIEW_EXPENSE',        'View Expense Bills',        'Expenditure', 'VIEW',    'View expense bills and their approvals'),
('CREATE_EXPENSE',      'Enter Expense Bills',       'Expenditure', 'CREATE',  'Enter a supplier''s bill for utilities, services, fees, repairs, transport or a capital item'),
('RECOMMEND_EXPENSE',   'Recommend Expense Bills',   'Expenditure', 'APPROVE', 'Recommend an expense bill as head of its department'),
('REVIEW_EXPENSE',      'Review Expense Bills',      'Expenditure', 'REVIEW',  'Review an expense bill for Finance before it is approved'),
('APPROVE_EXPENSE',     'Approve Expense Bills',     'Expenditure', 'APPROVE', 'Approve or reject an expense bill within the expenditure matrix'),
('RATIFY_EXPENSE',      'Ratify Emergency Spending', 'Expenditure', 'APPROVE', 'Ratify an emergency expense the Managing Director approved (Board)'),
('REVERSE_EXPENSE',     'Reverse Expense Bills',     'Expenditure', 'REVERSE', 'Reverse a posted expense bill with a reason'),
('ALERT_EXPENSE',       'Expenditure Alerts',        'Expenditure', 'NOTIFY',  'Be told of emergency spending not ratified in time'),
('VIEW_REQUISITION',    'View Requisitions',         'Purchasing',  'VIEW',    'View purchase requisitions and their quotes'),
('CREATE_REQUISITION',  'Raise Requisitions',        'Purchasing',  'CREATE',  'Raise a purchase requisition with its quotes'),
('APPROVE_REQUISITION', 'Approve Requisitions',      'Purchasing',  'APPROVE', 'Approve or reject a purchase requisition as head of department'),
('MANAGE_COST_CENTRES', 'Manage Cost Centres',       'Accounting',  'UPDATE',  'Add, rename and deactivate cost centres');

INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE (p.code IN ('EXPENSE_BILLS', 'COST_CENTRES', 'REQUISITIONS', 'EXPENDITURE_REPORT') AND r.code IN ('ADMIN', 'OWNER', 'COO',
        'DIRECTOR_FINANCE', 'REPORTING_TAX_BUDGET', 'ACCOUNTANT', 'INTERNAL_CONTROLLER', 'AUDITOR'))
   OR (p.code IN ('EXPENSE_BILLS', 'REQUISITIONS') AND r.code IN ('DIRECTOR_COMMERCIAL', 'DIRECTOR_SUPPLY_CHAIN', 'BOOKKEEPING_CASHIER'))
   OR (p.code = 'EXPENSE_BILLS' AND r.code = 'TREASURY')
   OR (p.code = 'REQUISITIONS' AND r.code IN ('PROCUREMENT', 'WAREHOUSE_MANAGER'))
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE (r.code = 'ADMIN' AND p.code IN ('VIEW_EXPENSE', 'CREATE_EXPENSE', 'RECOMMEND_EXPENSE', 'REVIEW_EXPENSE', 'APPROVE_EXPENSE',
        'RATIFY_EXPENSE', 'REVERSE_EXPENSE', 'ALERT_EXPENSE', 'VIEW_REQUISITION', 'CREATE_REQUISITION', 'APPROVE_REQUISITION',
        'MANAGE_COST_CENTRES'))
   OR (r.code = 'OWNER' AND p.code IN ('VIEW_EXPENSE', 'APPROVE_EXPENSE', 'RATIFY_EXPENSE', 'ALERT_EXPENSE', 'VIEW_REQUISITION',
        'APPROVE_REQUISITION'))
   OR (r.code = 'COO' AND p.code IN ('VIEW_EXPENSE', 'RECOMMEND_EXPENSE', 'APPROVE_EXPENSE', 'VIEW_REQUISITION', 'APPROVE_REQUISITION'))
   OR (r.code = 'DIRECTOR_FINANCE' AND p.code IN ('VIEW_EXPENSE', 'RECOMMEND_EXPENSE', 'REVIEW_EXPENSE', 'APPROVE_EXPENSE', 'REVERSE_EXPENSE',
        'ALERT_EXPENSE', 'VIEW_REQUISITION', 'APPROVE_REQUISITION', 'MANAGE_COST_CENTRES'))
   OR (r.code IN ('DIRECTOR_COMMERCIAL', 'DIRECTOR_SUPPLY_CHAIN') AND p.code IN ('VIEW_EXPENSE', 'RECOMMEND_EXPENSE', 'VIEW_REQUISITION',
        'CREATE_REQUISITION', 'APPROVE_REQUISITION'))
   OR (r.code IN ('ACCOUNTANT', 'BOOKKEEPING_CASHIER') AND p.code IN ('VIEW_EXPENSE', 'CREATE_EXPENSE', 'VIEW_REQUISITION'))
   OR (r.code = 'REPORTING_TAX_BUDGET' AND p.code IN ('VIEW_EXPENSE', 'REVIEW_EXPENSE', 'VIEW_REQUISITION'))
   OR (r.code IN ('PROCUREMENT', 'WAREHOUSE_MANAGER') AND p.code IN ('VIEW_REQUISITION', 'CREATE_REQUISITION'))
   OR (r.code = 'INTERNAL_CONTROLLER' AND p.code IN ('VIEW_EXPENSE', 'VIEW_REQUISITION', 'ALERT_EXPENSE'))
   OR (r.code IN ('AUDITOR', 'TREASURY') AND p.code IN ('VIEW_EXPENSE', 'VIEW_REQUISITION'))
ON CONFLICT DO NOTHING;
