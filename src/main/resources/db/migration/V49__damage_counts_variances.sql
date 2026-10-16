-- =====================================================================
-- M16 of the coverage plan: damage reports and the damage register, counts by type with a blind first count, a second
-- count and a recount, participants who sign, and the follow-up of count variances (IMP 13, 15, 16; F-07, F-08;
-- DMG-009, VR-010, PF-06, PF-07, CSH-012; decisions D5 and D14; not in SRS v2.1).
-- =====================================================================

-- ---------------------------------------------------------------------
-- Damage reports (F-07, DMG-009): the units damaged, why, who was there, whether it can be repaired, what it is worth;
-- reported, inspected by Dispatch, approved by the Director of Supply Chain. While reported or inspected the report
-- holds its units (StockService.holds), so nothing sells, loads, cuts or moves them.
-- ---------------------------------------------------------------------
CREATE TABLE damage_reports (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version            BIGINT        NOT NULL DEFAULT 0,
    number             VARCHAR(30)   NOT NULL,           -- DMG-WH-2026-000001
    location_id        UUID          NOT NULL REFERENCES locations(id),
    occurred_on        DATE          NOT NULL,
    cause              VARCHAR(20)   NOT NULL,
    description        VARCHAR(500)  NOT NULL,
    people_present     VARCHAR(255),
    repairable         BOOLEAN       NOT NULL DEFAULT FALSE,
    estimated_loss     NUMERIC(18,2) NOT NULL DEFAULT 0, -- the units' cost when reported
    credit_note_id     UUID          REFERENCES credit_notes(id),
    status             VARCHAR(20)   NOT NULL DEFAULT 'REPORTED',
    reported_by        VARCHAR(50)   NOT NULL,
    reported_by_id     BIGINT,
    reported_at        TIMESTAMP     NOT NULL,
    inspected_by       VARCHAR(50),
    inspected_by_id    BIGINT,
    inspected_at       TIMESTAMP,
    inspection_note    VARCHAR(500),
    decided_by         VARCHAR(50),
    decided_by_id      BIGINT,
    decided_at         TIMESTAMP,
    decision_note      VARCHAR(500),
    -- who may pay the loss back (F-07 C): a customer, an employee, an insurer, a supplier or a driver
    recover_from       VARCHAR(20)   NOT NULL DEFAULT 'NONE',
    recover_party      VARCHAR(150),
    recover_customer_id UUID         REFERENCES customers(id),
    recover_supplier_id UUID         REFERENCES suppliers(id),
    recover_driver_id  UUID          REFERENCES drivers(id),
    recover_amount     NUMERIC(18,2),
    recovery_journal   VARCHAR(30),
    adjustment_id      UUID          REFERENCES stock_adjustments(id),
    adjustment_number  VARCHAR(30),
    created_at         TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by         VARCHAR(50),
    updated_at         TIMESTAMP,
    updated_by         VARCHAR(50),
    CONSTRAINT uk_damage_reports_number UNIQUE (number),
    CONSTRAINT chk_damage_reports_cause CHECK (cause IN ('HANDLING', 'TRANSPORT', 'ENVIRONMENT', 'SUPPLIER', 'ACCIDENT',
        'CUSTOMER_RETURN', 'UNKNOWN', 'OTHER')),
    CONSTRAINT chk_damage_reports_status CHECK (status IN ('REPORTED', 'INSPECTED', 'APPROVED', 'REJECTED', 'CANCELLED')),
    CONSTRAINT chk_damage_reports_inspected CHECK (status <> 'INSPECTED' OR inspected_at IS NOT NULL),
    CONSTRAINT chk_damage_reports_decided CHECK (status NOT IN ('APPROVED', 'REJECTED', 'CANCELLED')
        OR (decided_at IS NOT NULL AND decided_by IS NOT NULL)),
    CONSTRAINT chk_damage_reports_reason CHECK (status NOT IN ('REJECTED', 'CANCELLED') OR decision_note IS NOT NULL),
    CONSTRAINT chk_damage_reports_recover CHECK (recover_from IN ('NONE', 'CUSTOMER', 'EMPLOYEE', 'INSURER', 'SUPPLIER', 'DRIVER')),
    CONSTRAINT chk_damage_reports_recover_amount CHECK (recover_from = 'NONE' OR (recover_amount IS NOT NULL AND recover_amount > 0)),
    CONSTRAINT chk_damage_reports_loss CHECK (estimated_loss >= 0)
);
CREATE INDEX idx_damage_reports_status ON damage_reports (status);
CREATE INDEX idx_damage_reports_occurred ON damage_reports (occurred_on);

CREATE TABLE damage_report_lines (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version         BIGINT        NOT NULL DEFAULT 0,
    report_id       UUID          NOT NULL REFERENCES damage_reports(id),
    line_no         INTEGER       NOT NULL,
    stock_unit_id   UUID          NOT NULL REFERENCES stock_units(id),
    unit_code       VARCHAR(30)   NOT NULL,
    product_id      UUID          NOT NULL REFERENCES products(id),
    location_id     UUID          REFERENCES locations(id),
    area_m2         NUMERIC(10,4) NOT NULL,
    unit_cost       NUMERIC(18,2) NOT NULL,
    outcome         VARCHAR(20),                        -- set when the report is approved
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by      VARCHAR(50),
    updated_at      TIMESTAMP,
    updated_by      VARCHAR(50),
    CONSTRAINT uk_damage_report_lines_no UNIQUE (report_id, line_no) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT uk_damage_report_lines_unit UNIQUE (report_id, stock_unit_id),
    CONSTRAINT chk_damage_report_lines_outcome CHECK (outcome IS NULL OR outcome IN ('WRITE_OFF', 'BACK_TO_STOCK'))
);
CREATE INDEX idx_damage_report_lines_unit ON damage_report_lines (stock_unit_id);

-- ---------------------------------------------------------------------
-- Counts (IMP 15, CSH-012, PF-06; decision D14): the type, the phase (first count, second count, recount), each scan's
-- pass; who took part and who signed the result.
-- ---------------------------------------------------------------------
ALTER TABLE stock_counts ADD COLUMN count_type VARCHAR(20) NOT NULL DEFAULT 'CYCLE';
ALTER TABLE stock_counts ADD COLUMN phase VARCHAR(20) NOT NULL DEFAULT 'FIRST';
ALTER TABLE stock_counts ADD COLUMN blind BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE stock_counts ADD COLUMN first_done_by VARCHAR(50);
ALTER TABLE stock_counts ADD COLUMN first_done_at TIMESTAMP;
ALTER TABLE stock_counts ADD COLUMN second_done_by VARCHAR(50);
ALTER TABLE stock_counts ADD COLUMN second_done_at TIMESTAMP;
ALTER TABLE stock_counts ADD COLUMN recount_codes INTEGER;
ALTER TABLE stock_counts ADD CONSTRAINT chk_stock_counts_type CHECK (count_type IN ('CYCLE', 'QUARTERLY', 'ANNUAL', 'SURPRISE'));
ALTER TABLE stock_counts ADD CONSTRAINT chk_stock_counts_phase CHECK (phase IN ('FIRST', 'SECOND', 'RECOUNT'));
ALTER TABLE stock_counts ADD CONSTRAINT chk_stock_counts_second CHECK (phase = 'FIRST' OR first_done_at IS NOT NULL);
ALTER TABLE stock_counts ADD CONSTRAINT chk_stock_counts_recount CHECK (phase <> 'RECOUNT' OR second_done_at IS NOT NULL);

ALTER TABLE stock_count_scans ADD COLUMN pass INTEGER NOT NULL DEFAULT 1;
ALTER TABLE stock_count_scans DROP CONSTRAINT uk_stock_count_scans_code;
ALTER TABLE stock_count_scans ADD CONSTRAINT uk_stock_count_scans_code UNIQUE (count_id, pass, code);
ALTER TABLE stock_count_scans ADD CONSTRAINT chk_stock_count_scans_pass CHECK (pass IN (1, 2, 3));

CREATE TABLE stock_count_participants (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version     BIGINT       NOT NULL DEFAULT 0,
    count_id    UUID         NOT NULL REFERENCES stock_counts(id),
    role        VARCHAR(30)  NOT NULL,
    name        VARCHAR(100) NOT NULL,
    user_id     BIGINT       REFERENCES users(id),
    signed_at   TIMESTAMP,
    signed_by   VARCHAR(50),                         -- the user who signed, or who recorded a signature on the sheet
    on_paper    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by  VARCHAR(50),
    updated_at  TIMESTAMP,
    updated_by  VARCHAR(50),
    CONSTRAINT chk_stock_count_participants_role CHECK (role IN ('WAREHOUSE', 'INTERNAL_CONTROLLER', 'FINANCE',
        'EXTERNAL_AUDITOR', 'OTHER')),
    CONSTRAINT chk_stock_count_participants_signed CHECK (signed_at IS NULL OR signed_by IS NOT NULL),
    CONSTRAINT uk_stock_count_participants_user UNIQUE (count_id, user_id)
);
CREATE INDEX idx_stock_count_participants_count ON stock_count_participants (count_id);

-- ---------------------------------------------------------------------
-- Count variances (IMP 16, VR-010): one per closed count with differences, its value and class, the investigation
-- (root cause, corrective action, responsible officer) due within the Settings' working days, escalated when late.
-- ---------------------------------------------------------------------
CREATE TABLE stock_variances (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version             BIGINT        NOT NULL DEFAULT 0,
    number              VARCHAR(30)   NOT NULL,          -- VAR-WH-2026-000001
    count_id            UUID          NOT NULL REFERENCES stock_counts(id),
    missing_units       INTEGER       NOT NULL,
    found_units         INTEGER       NOT NULL,
    misplaced_units     INTEGER       NOT NULL,
    unknown_units       INTEGER       NOT NULL,
    missing_value       NUMERIC(18,2) NOT NULL,
    found_value         NUMERIC(18,2) NOT NULL,
    expected_value      NUMERIC(18,2) NOT NULL,
    variance_percent    NUMERIC(9,2),
    classification      VARCHAR(20)   NOT NULL,
    above_tolerance     BOOLEAN       NOT NULL,
    due_on              DATE          NOT NULL,
    status              VARCHAR(20)   NOT NULL DEFAULT 'OPEN',
    root_cause          VARCHAR(30),
    corrective_action   VARCHAR(500),
    responsible_officer VARCHAR(100),
    resolution_note     VARCHAR(500),
    resolved_by         VARCHAR(50),
    resolved_at         TIMESTAMP,
    escalated_at        TIMESTAMP,
    board_notified_at   TIMESTAMP,
    created_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          VARCHAR(50),
    updated_at          TIMESTAMP,
    updated_by          VARCHAR(50),
    CONSTRAINT uk_stock_variances_number UNIQUE (number),
    CONSTRAINT uk_stock_variances_count UNIQUE (count_id),
    CONSTRAINT chk_stock_variances_class CHECK (classification IN ('MINOR', 'MODERATE', 'MATERIAL')),
    CONSTRAINT chk_stock_variances_status CHECK (status IN ('OPEN', 'ESCALATED', 'RESOLVED')),
    CONSTRAINT chk_stock_variances_cause CHECK (root_cause IS NULL OR root_cause IN ('COUNTING_ERROR', 'RECORDING_ERROR',
        'MISPLACEMENT', 'THEFT', 'DAMAGE', 'SUPPLIER', 'UNKNOWN', 'OTHER')),
    CONSTRAINT chk_stock_variances_resolved CHECK (status <> 'RESOLVED' OR (resolved_at IS NOT NULL AND root_cause IS NOT NULL
        AND corrective_action IS NOT NULL AND responsible_officer IS NOT NULL)),
    CONSTRAINT chk_stock_variances_escalated CHECK (status <> 'ESCALATED' OR escalated_at IS NOT NULL)
);
CREATE INDEX idx_stock_variances_status ON stock_variances (status);

-- ---------------------------------------------------------------------
-- The recovery of a damage loss posts (F-07 C): Dr what is owed / Cr Damage Recoveries
-- ---------------------------------------------------------------------
INSERT INTO accounts (code, name, account_type, system_key, description, created_by) VALUES
('4090', 'Damage Recoveries', 'REVENUE', 'DAMAGE_RECOVERIES', 'Damage losses charged to a customer, an employee, an insurer, a supplier or a driver', 'system');

ALTER TABLE journal_entries DROP CONSTRAINT chk_journal_entries_source;
ALTER TABLE journal_entries ADD CONSTRAINT chk_journal_entries_source CHECK (source_type IN
    ('GOODS_RECEIPT', 'SHIPMENT', 'CLAIM_OPENED', 'CLAIM_SETTLED', 'CLAIM_REJECTED', 'CUTTING_JOB', 'ADJUSTMENT',
     'OPENING_STOCK', 'SALES_INVOICE', 'TILL_OPENED', 'TILL_CLOSED', 'SALES_DELIVERY', 'SALES_BALANCE', 'CREDIT_NOTE',
     'CUSTOMER_PAYMENT', 'SUPPLIER_INVOICE', 'SUPPLIER_PAYMENT', 'FX_REVALUATION', 'MANUAL_JOURNAL', 'MOBILE_SALE',
     'TRIP_RETURN', 'AUDIT_CASE', 'FLOAT_CLEARANCE', 'PAYROLL', 'PAYROLL_PAYMENT', 'VEHICLE_COST', 'RECEIVABLE_WRITE_OFF',
     'CASH_DEPOSIT', 'TRIP_DEPOSIT', 'DAMAGE_RECOVERY'));

ALTER TABLE attachments DROP CONSTRAINT chk_attachments_owner;
ALTER TABLE attachments ADD CONSTRAINT chk_attachments_owner CHECK (owner_type IN
    ('GOODS_RECEIPT', 'SHIPMENT', 'SUPPLIER_INVOICE', 'STOCK_ADJUSTMENT', 'VEHICLE', 'DRIVER', 'TRIP', 'VEHICLE_COST',
     'CREDIT_NOTE_REQUEST', 'RECEIVABLE_WRITE_OFF', 'CUSTOMER', 'SUPPLIER', 'CREDIT_APPLICATION', 'SITE_TRANSFER',
     'CUTTING_JOB', 'DELIVERY_NOTE', 'CASH_DEPOSIT', 'BALANCE_CONFIRMATION', 'DAILY_CLOSE', 'DAMAGE_REPORT', 'STOCK_COUNT',
     'STOCK_VARIANCE'));

ALTER TABLE notifications DROP CONSTRAINT chk_notifications_kind;
ALTER TABLE notifications ADD CONSTRAINT chk_notifications_kind
    CHECK (kind IN ('LOW_STOCK', 'APPROVAL', 'DECISION', 'SYSTEM', 'EBM', 'FLEET', 'SYNC', 'EOD', 'CREDIT', 'TRANSFER',
                    'ORDER', 'DELIVERY', 'CLOSE', 'DAMAGE', 'VARIANCE'));

-- ---------------------------------------------------------------------
-- Numbers and settings (D14: blind first counts; a second count for annual and surprise counts and wherever the first
-- finds differences)
-- ---------------------------------------------------------------------
INSERT INTO number_sequences (doc_type, branch_code, prefix, reset_policy, padding, created_by) VALUES
('DAMAGE_REPORT',  'WH', 'DMG', 'YEARLY', 6, 'system'),
('STOCK_VARIANCE', 'WH', 'VAR', 'YEARLY', 6, 'system');

INSERT INTO settings (setting_key, setting_value, created_by) VALUES
('damage.inspection',                 'true',            'system'),
('damage.alert-value',                '500000',          'system'),
('count.blind',                       'true',            'system'),
('count.second-types',                'ANNUAL,SURPRISE', 'system'),
('count.second-on-differences',       'true',            'system'),
('count.variance-tolerance-percent',  '0.5',             'system'),
('count.variance-moderate-percent',   '0.5',             'system'),
('count.variance-material-percent',   '2',               'system'),
('count.variance-days',               '5',               'system');

-- ---------------------------------------------------------------------
-- Security
-- ---------------------------------------------------------------------
INSERT INTO pages (code, name, module, path, icon, sort_order) VALUES
('DAMAGE',          'Damage',          'Inventory', '/damage',          'alert-triangle', 8),
('STOCK_VARIANCES', 'Stock Variances', 'Inventory', '/stock-variances', 'activity',       9);

INSERT INTO permissions (code, name, module, action, description) VALUES
('VIEW_DAMAGE',             'View Damage',                 'Stock Operations', 'VIEW',    'View damage reports and the damage register'),
('REPORT_DAMAGE',           'Report Damage',               'Stock Operations', 'CREATE',  'Report damaged glass: the units, the cause, who was there, whether it can be repaired'),
('INSPECT_DAMAGE',          'Inspect Damage',              'Stock Operations', 'APPROVE', 'Inspect a damage report before it is decided (Dispatch Officer)'),
('APPROVE_DAMAGE',          'Approve Damage',              'Stock Operations', 'APPROVE', 'Approve or reject a damage report: write the units off or put them back (Director of Supply Chain)'),
('ALERT_DAMAGE',            'Damage Alerts',               'Stock Operations', 'NOTIFY',  'Be told the same day of damage at or above the Board''s tolerance'),
('VIEW_STOCK_VARIANCE',     'View Stock Variances',        'Stock Operations', 'VIEW',    'View count variances and their investigation'),
('RESOLVE_VARIANCE',        'Resolve Stock Variances',     'Stock Operations', 'APPROVE', 'Record a count variance''s root cause, corrective action and responsible officer'),
('ALERT_VARIANCE',          'Variance Alerts',             'Stock Operations', 'NOTIFY',  'Be told of count variances above the tolerance and those not resolved in time'),
('ALERT_MATERIAL_VARIANCE', 'Material Variance Alerts',    'Stock Operations', 'NOTIFY',  'Be told of material count variances (reported to the Board)'),
('SIGN_STOCK_COUNT',        'Sign Stock Counts',           'Stock Operations', 'APPROVE', 'Sign a closed count as one of its participants');

INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE (p.code = 'DAMAGE' AND r.code IN ('ADMIN', 'OWNER', 'COO', 'WAREHOUSE_MANAGER', 'WAREHOUSE_SUPERVISOR', 'INVENTORY_TRANSACTIONS',
        'DIRECTOR_COMMERCIAL', 'DISPATCH_OFFICER', 'DIRECTOR_SUPPLY_CHAIN', 'INTERNAL_CONTROLLER', 'SECURITY_RISK', 'ACCOUNTANT',
        'DIRECTOR_FINANCE', 'AUDITOR'))
   OR (p.code = 'STOCK_VARIANCES' AND r.code IN ('ADMIN', 'OWNER', 'COO', 'WAREHOUSE_MANAGER', 'WAREHOUSE_SUPERVISOR',
        'DIRECTOR_SUPPLY_CHAIN', 'INTERNAL_CONTROLLER', 'ACCOUNTANT', 'DIRECTOR_FINANCE', 'AUDITOR'))
   OR (p.code = 'STOCK_COUNTS' AND r.code IN ('DIRECTOR_FINANCE', 'COO'))
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE (r.code = 'ADMIN' AND p.code IN ('VIEW_DAMAGE', 'REPORT_DAMAGE', 'INSPECT_DAMAGE', 'APPROVE_DAMAGE', 'ALERT_DAMAGE',
        'VIEW_STOCK_VARIANCE', 'RESOLVE_VARIANCE', 'ALERT_VARIANCE', 'ALERT_MATERIAL_VARIANCE', 'SIGN_STOCK_COUNT'))
   OR (r.code = 'OWNER' AND p.code IN ('VIEW_DAMAGE', 'APPROVE_DAMAGE', 'ALERT_DAMAGE', 'VIEW_STOCK_VARIANCE', 'ALERT_MATERIAL_VARIANCE'))
   OR (r.code = 'COO' AND p.code IN ('VIEW_DAMAGE', 'VIEW_STOCK_VARIANCE', 'ALERT_VARIANCE', 'ALERT_MATERIAL_VARIANCE', 'VIEW_STOCK_COUNT'))
   OR (r.code = 'WAREHOUSE_MANAGER' AND p.code IN ('VIEW_DAMAGE', 'REPORT_DAMAGE', 'VIEW_STOCK_VARIANCE', 'RESOLVE_VARIANCE', 'SIGN_STOCK_COUNT'))
   OR (r.code = 'WAREHOUSE_SUPERVISOR' AND p.code IN ('VIEW_DAMAGE', 'REPORT_DAMAGE', 'VIEW_STOCK_VARIANCE', 'SIGN_STOCK_COUNT'))
   OR (r.code IN ('INVENTORY_TRANSACTIONS', 'DIRECTOR_COMMERCIAL') AND p.code IN ('VIEW_DAMAGE', 'REPORT_DAMAGE'))
   OR (r.code = 'DISPATCH_OFFICER' AND p.code IN ('VIEW_DAMAGE', 'REPORT_DAMAGE', 'INSPECT_DAMAGE'))
   OR (r.code = 'DIRECTOR_SUPPLY_CHAIN' AND p.code IN ('VIEW_DAMAGE', 'APPROVE_DAMAGE', 'VIEW_STOCK_VARIANCE', 'RESOLVE_VARIANCE',
        'ALERT_VARIANCE', 'SIGN_STOCK_COUNT'))
   OR (r.code = 'INTERNAL_CONTROLLER' AND p.code IN ('VIEW_DAMAGE', 'ALERT_DAMAGE', 'VIEW_STOCK_VARIANCE', 'ALERT_VARIANCE',
        'ALERT_MATERIAL_VARIANCE', 'SIGN_STOCK_COUNT'))
   OR (r.code = 'SECURITY_RISK' AND p.code IN ('VIEW_DAMAGE', 'ALERT_DAMAGE'))
   OR (r.code IN ('ACCOUNTANT', 'DIRECTOR_FINANCE') AND p.code IN ('VIEW_DAMAGE', 'VIEW_STOCK_VARIANCE', 'SIGN_STOCK_COUNT', 'VIEW_STOCK_COUNT'))
   OR (r.code = 'AUDITOR' AND p.code IN ('VIEW_DAMAGE', 'VIEW_STOCK_VARIANCE'))
ON CONFLICT DO NOTHING;
