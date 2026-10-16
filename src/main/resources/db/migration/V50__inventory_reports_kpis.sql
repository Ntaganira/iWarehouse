-- =====================================================================
-- V50: inventory reports and KPIs (M16 of the coverage plan, second half: Inventory Policy sections 14, 18 and 19,
-- DIR-011, PF-05; SDP 15-16; Board Paper 5.1, 13.1, Table 6, Annex D; not in SRS v2.1)
--   - the daily inventory report countersigned by the Internal Controller (M.01, M.02)
--   - the formal review when inventory accuracy stays below target (IMP 19: two consecutive months)
--   - the inventory report by week, month, quarter or year, the inventory KPIs, the discounts report, collections by
--     territory and salesperson, the monthly management pack
-- =====================================================================

-- ---------------------------------------------------------------------
-- The daily inventory report: the Warehouse Manager signs it off (V45), the Internal Controller countersigns it
-- ---------------------------------------------------------------------
ALTER TABLE stock_day_reviews
    ADD COLUMN countersigned_by    VARCHAR(50),
    ADD COLUMN countersigned_by_id BIGINT,
    ADD COLUMN countersigned_at    TIMESTAMP,
    ADD COLUMN countersign_note    VARCHAR(500),
    ADD CONSTRAINT chk_stock_day_reviews_countersign
        CHECK ((countersigned_by IS NULL) = (countersigned_at IS NULL));

-- ---------------------------------------------------------------------
-- The formal review of inventory accuracy (IMP 19): once per month that closes a run of months below target, by the
-- Head of Inventory or the Managing Director, with the figures it was made on. Never changed once made.
-- ---------------------------------------------------------------------
CREATE TABLE inventory_kpi_reviews (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version          BIGINT        NOT NULL DEFAULT 0,
    period_start     DATE          NOT NULL,              -- the first day of the last month below target
    months_below     INTEGER       NOT NULL,
    accuracy         NUMERIC(6,2),                        -- that month's accuracy (%)
    target           NUMERIC(6,2)  NOT NULL,
    note             VARCHAR(1000) NOT NULL,
    reviewed_by      VARCHAR(50)   NOT NULL,
    reviewed_by_id   BIGINT,
    reviewed_at      TIMESTAMP     NOT NULL,
    created_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by       VARCHAR(50),
    updated_at       TIMESTAMP,
    updated_by       VARCHAR(50),
    CONSTRAINT uk_inventory_kpi_reviews_period UNIQUE (period_start),
    CONSTRAINT chk_inventory_kpi_reviews_months CHECK (months_below >= 1)
);

ALTER TABLE notifications DROP CONSTRAINT chk_notifications_kind;
ALTER TABLE notifications ADD CONSTRAINT chk_notifications_kind
    CHECK (kind IN ('LOW_STOCK', 'APPROVAL', 'DECISION', 'SYSTEM', 'EBM', 'FLEET', 'SYNC', 'EOD', 'CREDIT', 'TRANSFER',
                    'ORDER', 'DELIVERY', 'CLOSE', 'DAMAGE', 'VARIANCE', 'KPI'));

-- ---------------------------------------------------------------------
-- Settings: the policy's targets (IMP 19); turnover is set by management (none until it is); order-to-delivery is the
-- agreed target of Annex D (our recommendation: 3 days); glass held longer than a year is obsolete (IMP 13)
-- ---------------------------------------------------------------------
INSERT INTO settings (setting_key, setting_value, created_by) VALUES
('stock.obsolete-days',                '365',   'system'),
('kpi.accuracy-target',                '99',    'system'),
('kpi.accuracy-months',                '2',     'system'),
('kpi.loss-max-percent',               '0.5',   'system'),
('kpi.damage-max-percent',             '1',     'system'),
('kpi.turnover-target',                NULL,    'system'),
('kpi.delivery-accuracy-target',       '98',    'system'),
('kpi.reconciliation-target',          '100',   'system'),
('kpi.variance-resolution-target',     '100',   'system'),
('kpi.order-days-target',              '3',     'system');

-- ---------------------------------------------------------------------
-- Security
-- ---------------------------------------------------------------------
INSERT INTO pages (code, name, module, path, icon, sort_order) VALUES
('INVENTORY_REPORT',   'Inventory Report',   'Reports', '/reports/inventory',       'list', 43),
('INVENTORY_KPIS',     'Inventory KPIs',     'Reports', '/reports/kpis',            'list', 44),
('DISCOUNTS_REPORT',   'Discounts Report',   'Reports', '/reports/discounts',       'list', 45),
('COLLECTIONS_REPORT', 'Collections Report', 'Reports', '/reports/collections',     'list', 46),
('MANAGEMENT_PACK',    'Management Pack',    'Reports', '/reports/management-pack', 'list', 47);

INSERT INTO permissions (code, name, module, action, description) VALUES
('COUNTERSIGN_DAILY_STOCK', 'Countersign the Daily Inventory Report', 'Reports', 'APPROVE', 'Countersign a day''s inventory report the Warehouse Manager signed off (Internal Controller)'),
('REVIEW_INVENTORY_KPI',    'Review Inventory KPIs',                  'Reports', 'REVIEW',  'Record the formal review when inventory accuracy stays below target (Head of Inventory, Managing Director)'),
('ALERT_KPI',               'KPI Alerts',                             'Reports', 'NOTIFY',  'Be told when inventory accuracy stays below target for the set number of months');

INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE (p.code IN ('INVENTORY_REPORT', 'INVENTORY_KPIS') AND r.code IN ('ADMIN', 'OWNER', 'COO', 'DIRECTOR_SUPPLY_CHAIN',
        'WAREHOUSE_MANAGER', 'INTERNAL_CONTROLLER', 'ACCOUNTANT', 'DIRECTOR_FINANCE', 'AUDITOR', 'REPORTING_TAX_BUDGET'))
   OR (p.code = 'DISCOUNTS_REPORT' AND r.code IN ('ADMIN', 'OWNER', 'COO', 'DIRECTOR_COMMERCIAL', 'DIRECTOR_FINANCE',
        'ACCOUNTANT', 'INTERNAL_CONTROLLER', 'AUDITOR', 'REPORTING_TAX_BUDGET', 'SALES_RECORDING'))
   OR (p.code = 'COLLECTIONS_REPORT' AND r.code IN ('ADMIN', 'OWNER', 'COO', 'DIRECTOR_COMMERCIAL', 'DIRECTOR_FINANCE',
        'ACCOUNTANT', 'CREDIT_CONTROL', 'INTERNAL_CONTROLLER', 'AUDITOR', 'REPORTING_TAX_BUDGET'))
   OR (p.code = 'MANAGEMENT_PACK' AND r.code IN ('ADMIN', 'OWNER', 'COO', 'DIRECTOR_FINANCE', 'REPORTING_TAX_BUDGET',
        'INTERNAL_CONTROLLER', 'AUDITOR'))
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE (r.code = 'ADMIN' AND p.code IN ('COUNTERSIGN_DAILY_STOCK', 'REVIEW_INVENTORY_KPI', 'ALERT_KPI'))
   OR (r.code = 'OWNER' AND p.code IN ('REVIEW_INVENTORY_KPI', 'ALERT_KPI'))
   OR (r.code = 'DIRECTOR_SUPPLY_CHAIN' AND p.code IN ('REVIEW_INVENTORY_KPI', 'ALERT_KPI'))
   OR (r.code = 'WAREHOUSE_MANAGER' AND p.code IN ('ALERT_KPI'))
   OR (r.code = 'INTERNAL_CONTROLLER' AND p.code IN ('COUNTERSIGN_DAILY_STOCK', 'ALERT_KPI'))
ON CONFLICT DO NOTHING;
