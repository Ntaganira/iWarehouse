-- =====================================================================
-- V54: budgets and sales targets (M18 of the coverage plan, first half: Cost Management & Expenditure Control Policy 04,
-- 05, 10; Board Paper 4.1, 5.3, Annex D; SDP 15, 16; F-06; not in SRS v2.1)
--   - the annual budget by department and account, submitted by the departments, consolidated by Finance, approved by
--     the Board (T.01), revised within the Delegation of Authority (T.02), against actual every month (T.03, T.11,
--     T.13, C.12); unbudgeted spending flagged (T.04)
--   - sales targets by territory, salesperson and market, and the weekly report (T.07, T.09, M.14)
-- =====================================================================

CREATE TABLE budgets (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version              BIGINT        NOT NULL DEFAULT 0,
    number               VARCHAR(30)   NOT NULL,              -- BUD-WH-2026-000001
    budget_year          INTEGER       NOT NULL,
    title                VARCHAR(150)  NOT NULL,
    status               VARCHAR(10)   NOT NULL DEFAULT 'DRAFT',
    landed_cost_budget   NUMERIC(18,2) NOT NULL DEFAULT 0,    -- the year's landed cost (RWF)
    demurrage_ceiling    NUMERIC(18,2) NOT NULL DEFAULT 0,    -- demurrage and storage per consignment (RWF)
    prepared_by          VARCHAR(50)   NOT NULL,
    prepared_by_id       BIGINT,
    prepared_at          TIMESTAMP     NOT NULL,
    submitted_by         VARCHAR(50),
    submitted_by_id      BIGINT,
    submitted_at         TIMESTAMP,
    decided_by           VARCHAR(50),
    decided_by_id        BIGINT,
    decided_at           TIMESTAMP,
    decision_note        VARCHAR(500),
    returned_note        VARCHAR(500),
    created_at           TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by           VARCHAR(50),
    updated_at           TIMESTAMP,
    updated_by           VARCHAR(50),
    CONSTRAINT uk_budgets_number UNIQUE (number),
    CONSTRAINT chk_budgets_status CHECK (status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'CANCELLED')),
    CONSTRAINT chk_budgets_year CHECK (budget_year BETWEEN 2020 AND 2100),
    CONSTRAINT chk_budgets_amounts CHECK (landed_cost_budget >= 0 AND demurrage_ceiling >= 0),
    CONSTRAINT chk_budgets_decided CHECK (status <> 'APPROVED' OR (decided_at IS NOT NULL AND decision_note IS NOT NULL)),
    CONSTRAINT chk_budgets_cancelled CHECK (status <> 'CANCELLED' OR decision_note IS NOT NULL)
);
-- One budget a year (a cancelled one may be started again)
CREATE UNIQUE INDEX uk_budgets_year ON budgets (budget_year) WHERE status <> 'CANCELLED';

CREATE TABLE budget_lines (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version          BIGINT        NOT NULL DEFAULT 0,
    budget_id        UUID          NOT NULL REFERENCES budgets(id),
    cost_centre_id   UUID          REFERENCES cost_centres(id),   -- none: the company's (revenue)
    account_id       UUID          NOT NULL REFERENCES accounts(id),
    m1  NUMERIC(18,2) NOT NULL DEFAULT 0, m2  NUMERIC(18,2) NOT NULL DEFAULT 0, m3  NUMERIC(18,2) NOT NULL DEFAULT 0,
    m4  NUMERIC(18,2) NOT NULL DEFAULT 0, m5  NUMERIC(18,2) NOT NULL DEFAULT 0, m6  NUMERIC(18,2) NOT NULL DEFAULT 0,
    m7  NUMERIC(18,2) NOT NULL DEFAULT 0, m8  NUMERIC(18,2) NOT NULL DEFAULT 0, m9  NUMERIC(18,2) NOT NULL DEFAULT 0,
    m10 NUMERIC(18,2) NOT NULL DEFAULT 0, m11 NUMERIC(18,2) NOT NULL DEFAULT 0, m12 NUMERIC(18,2) NOT NULL DEFAULT 0,
    total            NUMERIC(18,2) NOT NULL DEFAULT 0,
    created_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by       VARCHAR(50),
    updated_at       TIMESTAMP,
    updated_by       VARCHAR(50),
    CONSTRAINT chk_budget_lines_months CHECK (m1 >= 0 AND m2 >= 0 AND m3 >= 0 AND m4 >= 0 AND m5 >= 0 AND m6 >= 0 AND m7 >= 0 AND m8 >= 0
        AND m9 >= 0 AND m10 >= 0 AND m11 >= 0 AND m12 >= 0),
    CONSTRAINT chk_budget_lines_total CHECK (total = m1 + m2 + m3 + m4 + m5 + m6 + m7 + m8 + m9 + m10 + m11 + m12)
);
CREATE UNIQUE INDEX uk_budget_lines_account ON budget_lines (budget_id, coalesce(cost_centre_id, '00000000-0000-0000-0000-000000000000'::uuid), account_id);

-- Each department submits its part (T.01: by 30 November); Finance may send it back
CREATE TABLE budget_departments (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version          BIGINT        NOT NULL DEFAULT 0,
    budget_id        UUID          NOT NULL REFERENCES budgets(id),
    cost_centre_id   UUID          NOT NULL REFERENCES cost_centres(id),
    status           VARCHAR(10)   NOT NULL DEFAULT 'OPEN',
    submitted_by     VARCHAR(50),
    submitted_by_id  BIGINT,
    submitted_at     TIMESTAMP,
    returned_by      VARCHAR(50),
    returned_note    VARCHAR(500),
    created_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by       VARCHAR(50),
    updated_at       TIMESTAMP,
    updated_by       VARCHAR(50),
    CONSTRAINT uk_budget_departments UNIQUE (budget_id, cost_centre_id),
    CONSTRAINT chk_budget_departments_status CHECK (status IN ('OPEN', 'SUBMITTED')),
    CONSTRAINT chk_budget_departments_submitted CHECK (status <> 'SUBMITTED' OR submitted_at IS NOT NULL)
);

-- A line of an approved budget revised (T.02): the MD up to 15 %, the Board above
CREATE TABLE budget_revisions (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version          BIGINT        NOT NULL DEFAULT 0,
    number           VARCHAR(30)   NOT NULL,              -- BRV-WH-2026-000001
    budget_id        UUID          NOT NULL REFERENCES budgets(id),
    line_id          UUID          NOT NULL REFERENCES budget_lines(id),
    n1  NUMERIC(18,2) NOT NULL DEFAULT 0, n2  NUMERIC(18,2) NOT NULL DEFAULT 0, n3  NUMERIC(18,2) NOT NULL DEFAULT 0,
    n4  NUMERIC(18,2) NOT NULL DEFAULT 0, n5  NUMERIC(18,2) NOT NULL DEFAULT 0, n6  NUMERIC(18,2) NOT NULL DEFAULT 0,
    n7  NUMERIC(18,2) NOT NULL DEFAULT 0, n8  NUMERIC(18,2) NOT NULL DEFAULT 0, n9  NUMERIC(18,2) NOT NULL DEFAULT 0,
    n10 NUMERIC(18,2) NOT NULL DEFAULT 0, n11 NUMERIC(18,2) NOT NULL DEFAULT 0, n12 NUMERIC(18,2) NOT NULL DEFAULT 0,
    old_total        NUMERIC(18,2) NOT NULL,
    new_total        NUMERIC(18,2) NOT NULL,
    change_percent   NUMERIC(7,2)  NOT NULL,
    reason           VARCHAR(500)  NOT NULL,
    status           VARCHAR(10)   NOT NULL DEFAULT 'SUBMITTED',
    requested_by     VARCHAR(50)   NOT NULL,
    requested_by_id  BIGINT,
    requested_at     TIMESTAMP     NOT NULL,
    decided_by       VARCHAR(50),
    decided_by_id    BIGINT,
    decided_at       TIMESTAMP,
    decision_note    VARCHAR(500),
    authority_level  VARCHAR(10),
    created_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by       VARCHAR(50),
    updated_at       TIMESTAMP,
    updated_by       VARCHAR(50),
    CONSTRAINT uk_budget_revisions_number UNIQUE (number),
    CONSTRAINT chk_budget_revisions_status CHECK (status IN ('SUBMITTED', 'APPROVED', 'REJECTED', 'CANCELLED')),
    CONSTRAINT chk_budget_revisions_months CHECK (n1 >= 0 AND n2 >= 0 AND n3 >= 0 AND n4 >= 0 AND n5 >= 0 AND n6 >= 0 AND n7 >= 0 AND n8 >= 0
        AND n9 >= 0 AND n10 >= 0 AND n11 >= 0 AND n12 >= 0),
    CONSTRAINT chk_budget_revisions_decided CHECK (status = 'SUBMITTED' OR decided_at IS NOT NULL),
    CONSTRAINT chk_budget_revisions_reason CHECK (status NOT IN ('REJECTED', 'CANCELLED') OR decision_note IS NOT NULL)
);
CREATE INDEX idx_budget_revisions_budget ON budget_revisions (budget_id, status);

-- The month's budget against actual, signed off (T.03: by the 10th)
CREATE TABLE budget_reviews (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version          BIGINT        NOT NULL DEFAULT 0,
    review_year      INTEGER       NOT NULL,
    review_month     INTEGER       NOT NULL,
    flagged          INTEGER       NOT NULL DEFAULT 0,      -- lines beyond the tolerance
    signed_by        VARCHAR(50)   NOT NULL,
    signed_by_id     BIGINT,
    signed_at        TIMESTAMP     NOT NULL,
    note             VARCHAR(1000),
    created_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by       VARCHAR(50),
    updated_at       TIMESTAMP,
    updated_by       VARCHAR(50),
    CONSTRAINT uk_budget_reviews_month UNIQUE (review_year, review_month),
    CONSTRAINT chk_budget_reviews_month CHECK (review_month BETWEEN 1 AND 12),
    CONSTRAINT chk_budget_reviews_note CHECK (flagged = 0 OR note IS NOT NULL)
);
CREATE TRIGGER trg_budget_reviews_append_only BEFORE UPDATE OR DELETE ON budget_reviews
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_modification();

-- ---------------------------------------------------------------------
-- Sales targets (SDP 15, Annex D): by territory, salesperson and market, month by month
-- ---------------------------------------------------------------------
CREATE TABLE sales_targets (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version          BIGINT        NOT NULL DEFAULT 0,
    target_year      INTEGER       NOT NULL,
    target_month     INTEGER       NOT NULL,
    scope            VARCHAR(12)   NOT NULL,
    target_key       VARCHAR(60)   NOT NULL,              -- COMPANY; a territory's code; a salesperson's username; DOMESTIC or EXPORT
    amount           NUMERIC(18,2) NOT NULL DEFAULT 0,
    created_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by       VARCHAR(50),
    updated_at       TIMESTAMP,
    updated_by       VARCHAR(50),
    CONSTRAINT uk_sales_targets UNIQUE (target_year, target_month, scope, target_key),
    CONSTRAINT chk_sales_targets_scope CHECK (scope IN ('COMPANY', 'TERRITORY', 'SALESPERSON', 'MARKET')),
    CONSTRAINT chk_sales_targets_month CHECK (target_month BETWEEN 1 AND 12),
    CONSTRAINT chk_sales_targets_amount CHECK (amount >= 0)
);

-- ---------------------------------------------------------------------
-- The Delegation of Authority: the Board approves the budget; the MD revises it up to 15 % (CMP 04)
-- ---------------------------------------------------------------------
ALTER TABLE authority_limits DROP CONSTRAINT chk_authority_limits_decision;
ALTER TABLE authority_limits ADD CONSTRAINT chk_authority_limits_decision CHECK (decision IN ('DISCOUNT', 'BELOW_COST',
    'CREDIT_EXCEPTION', 'CREDIT_NOTE', 'RECEIVABLE_WRITE_OFF', 'STOCK_ADJUSTMENT', 'PURCHASE_ORDER', 'SUPPLIER_PAYMENT',
    'MANUAL_JOURNAL', 'VEHICLE_COST', 'CREDIT_LIMIT', 'PRICE_CHANGE', 'EXPENSE_OPEX', 'EXPENSE_CAPEX', 'EXPENSE_UNBUDGETED',
    'EXPENSE_EMERGENCY', 'STAFF_ADVANCE', 'BUDGET', 'BUDGET_REVISION'));

INSERT INTO authority_limits (decision, level, allowed, max_amount, created_by) VALUES
('BUDGET',          'DIRECTOR', FALSE, NULL,  'system'),
('BUDGET',          'COO',      FALSE, NULL,  'system'),
('BUDGET',          'MD',       FALSE, NULL,  'system'),
('BUDGET',          'BOARD',    TRUE,  NULL,  'system'),
('BUDGET_REVISION', 'DIRECTOR', FALSE, NULL,  'system'),
('BUDGET_REVISION', 'COO',      FALSE, NULL,  'system'),
('BUDGET_REVISION', 'MD',       TRUE,  15.00, 'system'),
('BUDGET_REVISION', 'BOARD',    TRUE,  NULL,  'system');

ALTER TABLE notifications DROP CONSTRAINT chk_notifications_kind;
ALTER TABLE notifications ADD CONSTRAINT chk_notifications_kind
    CHECK (kind IN ('LOW_STOCK', 'APPROVAL', 'DECISION', 'SYSTEM', 'EBM', 'FLEET', 'SYNC', 'EOD', 'CREDIT', 'TRANSFER',
                    'ORDER', 'DELIVERY', 'CLOSE', 'DAMAGE', 'VARIANCE', 'KPI', 'EXPENSE', 'BUDGET'));

INSERT INTO number_sequences (doc_type, branch_code, prefix, reset_policy, padding, created_by) VALUES
('BUDGET',          'WH', 'BUD', 'YEARLY', 6, 'system'),
('BUDGET_REVISION', 'WH', 'BRV', 'YEARLY', 6, 'system');

INSERT INTO settings (setting_key, setting_value, created_by) VALUES
('budget.submit-by',         '11-30', 'system'),
('budget.approve-by',        '12-31', 'system'),
('budget.variance-percent',  '5',     'system'),
('budget.margin-target',     '25',    'system'),
('budget.review-day',        '10',    'system');

-- ---------------------------------------------------------------------
-- Security
-- ---------------------------------------------------------------------
INSERT INTO pages (code, name, module, path, icon, sort_order) VALUES
('BUDGETS',              'Budgets',              'Accounting', '/budgets',               'pie-chart',   33),
('SALES_TARGETS',        'Sales Targets',        'Sales',      '/sales-targets',         'target',      30),
('BUDGET_REPORT',        'Budget Report',        'Reports',    '/reports/budget',        'bar-chart-2', 49),
('SALES_TARGET_REPORT',  'Sales Target Report',  'Reports',    '/reports/sales-targets', 'trending-up', 50),
('WEEKLY_REPORT',        'Weekly Report',        'Reports',    '/reports/weekly',        'calendar',    51);

INSERT INTO permissions (code, name, module, action, description) VALUES
('VIEW_BUDGET',             'View Budgets',            'Budget', 'VIEW',    'View the budgets, their departments and revisions'),
('PREPARE_BUDGET',          'Prepare Budgets',         'Budget', 'CREATE',  'Start a year''s budget, consolidate the departments and submit it to the Board'),
('SUBMIT_BUDGET',           'Submit Department Budgets', 'Budget', 'CREATE', 'Enter and submit one''s department''s budget lines'),
('APPROVE_BUDGET',          'Approve Budgets',         'Budget', 'APPROVE', 'Approve the year''s budget for the Board, or send it back'),
('REVISE_BUDGET',           'Ask for Budget Revisions', 'Budget', 'CREATE', 'Ask for a revision of a line of the approved budget'),
('APPROVE_BUDGET_REVISION', 'Approve Budget Revisions', 'Budget', 'APPROVE', 'Approve or reject a budget revision within the Delegation of Authority'),
('REVIEW_BUDGET',           'Sign Off Budget Reviews', 'Budget', 'REVIEW',  'Sign off a month''s budget against actual'),
('ALERT_BUDGET',            'Budget Alerts',           'Budget', 'NOTIFY',  'Be told of departments late with their budget, a budget not approved in time and a month not reviewed'),
('VIEW_SALES_TARGETS',      'View Sales Targets',      'Sales',  'VIEW',    'View the sales targets and the sales against them'),
('MANAGE_SALES_TARGETS',    'Manage Sales Targets',    'Sales',  'UPDATE',  'Set the sales targets by territory, salesperson and market');

INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE (p.code IN ('BUDGETS', 'SALES_TARGETS', 'BUDGET_REPORT', 'SALES_TARGET_REPORT', 'WEEKLY_REPORT') AND r.code IN ('ADMIN', 'OWNER', 'COO',
        'DIRECTOR_FINANCE', 'REPORTING_TAX_BUDGET', 'DIRECTOR_COMMERCIAL', 'INTERNAL_CONTROLLER', 'AUDITOR'))
   OR (p.code IN ('BUDGETS', 'BUDGET_REPORT') AND r.code IN ('DIRECTOR_SUPPLY_CHAIN', 'ACCOUNTANT'))
   OR (p.code IN ('SALES_TARGETS', 'SALES_TARGET_REPORT', 'WEEKLY_REPORT') AND r.code IN ('ORDER_DESK', 'SALES_RECORDING', 'CREDIT_CONTROL'))
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE (r.code = 'ADMIN' AND p.code IN ('VIEW_BUDGET', 'PREPARE_BUDGET', 'SUBMIT_BUDGET', 'APPROVE_BUDGET', 'REVISE_BUDGET',
        'APPROVE_BUDGET_REVISION', 'REVIEW_BUDGET', 'ALERT_BUDGET', 'VIEW_SALES_TARGETS', 'MANAGE_SALES_TARGETS'))
   OR (r.code = 'OWNER' AND p.code IN ('VIEW_BUDGET', 'APPROVE_BUDGET', 'APPROVE_BUDGET_REVISION', 'ALERT_BUDGET', 'VIEW_SALES_TARGETS'))
   OR (r.code = 'COO' AND p.code IN ('VIEW_BUDGET', 'REVIEW_BUDGET', 'ALERT_BUDGET', 'VIEW_SALES_TARGETS', 'MANAGE_SALES_TARGETS'))
   OR (r.code IN ('DIRECTOR_FINANCE', 'REPORTING_TAX_BUDGET') AND p.code IN ('VIEW_BUDGET', 'PREPARE_BUDGET', 'SUBMIT_BUDGET', 'REVISE_BUDGET',
        'REVIEW_BUDGET', 'ALERT_BUDGET', 'VIEW_SALES_TARGETS'))
   OR (r.code = 'DIRECTOR_COMMERCIAL' AND p.code IN ('VIEW_BUDGET', 'SUBMIT_BUDGET', 'REVISE_BUDGET', 'VIEW_SALES_TARGETS', 'MANAGE_SALES_TARGETS'))
   OR (r.code = 'DIRECTOR_SUPPLY_CHAIN' AND p.code IN ('VIEW_BUDGET', 'SUBMIT_BUDGET', 'REVISE_BUDGET'))
   OR (r.code = 'ACCOUNTANT' AND p.code = 'VIEW_BUDGET')
   OR (r.code IN ('INTERNAL_CONTROLLER', 'AUDITOR') AND p.code IN ('VIEW_BUDGET', 'VIEW_SALES_TARGETS'))
   OR (r.code IN ('ORDER_DESK', 'SALES_RECORDING', 'CREDIT_CONTROL') AND p.code = 'VIEW_SALES_TARGETS')
ON CONFLICT DO NOTHING;
