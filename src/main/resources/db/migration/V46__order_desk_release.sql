-- =====================================================================
-- V46: the Order Desk, the release of goods and the delivery note (M13 of the plan to cover the client's restructuring
-- documents; not in SRS v2.1)
--   - sales orders raised by the Frontline Order Desk (BP 7.1, Annex G, F-01; decision D4): a quotation's twin, with the
--     channel, market, territory, salesperson and agent; the cashier takes payment against it at a till
--   - territories with their regional sales manager (BP 6, Res. 7); customers keep their territory, market and agent flag
--   - the delivery note and goods release authority (F-04, DN-005, IMP 8 and 9): prepared by Dispatch, released by
--     another person once the EBM receipt is signed (decision D3, with a logged override), the signed copy recorded
--     with the customer's remarks; overdue copies reported
--   - the cut pieces verified by another person before release, pieces out of specification cut again (IMP 10, PF-03)
-- =====================================================================

-- ---------------------------------------------------------------------
-- Territories (BP 6, Res. 7)
-- ---------------------------------------------------------------------
CREATE TABLE territories (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version          BIGINT        NOT NULL DEFAULT 0,
    code             VARCHAR(20)   NOT NULL,
    name             VARCHAR(100)  NOT NULL,
    manager_user_id  BIGINT        REFERENCES users(id),
    manager_name     VARCHAR(100),
    enabled          BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by       VARCHAR(50),
    updated_at       TIMESTAMP,
    updated_by       VARCHAR(50),
    CONSTRAINT uk_territories_code UNIQUE (code),
    CONSTRAINT chk_territories_code CHECK (code = UPPER(code))
);

ALTER TABLE customers ADD COLUMN territory_id UUID REFERENCES territories(id);
ALTER TABLE customers ADD COLUMN market VARCHAR(10) NOT NULL DEFAULT 'DOMESTIC';
ALTER TABLE customers ADD COLUMN agent BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE customers ADD CONSTRAINT chk_customers_market CHECK (market IN ('DOMESTIC', 'EXPORT'));

-- ---------------------------------------------------------------------
-- Sales orders: quotations of kind SALES_ORDER (F-01)
-- ---------------------------------------------------------------------
ALTER TABLE quotations ADD COLUMN doc_kind VARCHAR(12) NOT NULL DEFAULT 'QUOTATION';
ALTER TABLE quotations ADD COLUMN channel VARCHAR(12);
ALTER TABLE quotations ADD COLUMN market VARCHAR(10);
ALTER TABLE quotations ADD COLUMN territory_id UUID REFERENCES territories(id);
ALTER TABLE quotations ADD COLUMN agent_id UUID REFERENCES customers(id);
ALTER TABLE quotations ADD COLUMN site_id UUID REFERENCES locations(id);
ALTER TABLE quotations ADD COLUMN salesperson VARCHAR(50);
ALTER TABLE quotations ADD COLUMN salesperson_id BIGINT;
ALTER TABLE quotations ADD COLUMN source_id UUID REFERENCES quotations(id);   -- the quotation an order was made from
ALTER TABLE quotations ADD CONSTRAINT chk_quotations_kind CHECK (doc_kind IN ('QUOTATION', 'SALES_ORDER'));
ALTER TABLE quotations ADD CONSTRAINT chk_quotations_channel CHECK (channel IS NULL OR channel IN
    ('COUNTER', 'BRANCH', 'AGENT', 'TELEPHONE', 'WHATSAPP', 'FIELD', 'EXPORT', 'OTHER'));
ALTER TABLE quotations ADD CONSTRAINT chk_quotations_market CHECK (market IS NULL OR market IN ('DOMESTIC', 'EXPORT'));
ALTER TABLE quotations ADD CONSTRAINT chk_quotations_order CHECK (doc_kind = 'QUOTATION'
    OR (channel IS NOT NULL AND market IS NOT NULL AND salesperson IS NOT NULL));
CREATE INDEX idx_quotations_kind_status ON quotations (doc_kind, status);

-- What each sale came through (G.19, G.20): copied from its order, or the counter's own
ALTER TABLE sales_invoices ADD COLUMN order_channel VARCHAR(12);
ALTER TABLE sales_invoices ADD COLUMN market VARCHAR(10);
ALTER TABLE sales_invoices ADD COLUMN territory_id UUID REFERENCES territories(id);
ALTER TABLE sales_invoices ADD COLUMN salesperson VARCHAR(50);
ALTER TABLE sales_invoices ADD COLUMN agent_id UUID REFERENCES customers(id);
ALTER TABLE sales_invoices ADD CONSTRAINT chk_sales_invoices_order_channel CHECK (order_channel IS NULL OR order_channel IN
    ('COUNTER', 'BRANCH', 'AGENT', 'TELEPHONE', 'WHATSAPP', 'FIELD', 'EXPORT', 'OTHER'));
ALTER TABLE sales_invoices ADD CONSTRAINT chk_sales_invoices_market CHECK (market IS NULL OR market IN ('DOMESTIC', 'EXPORT'));

-- ---------------------------------------------------------------------
-- Delivery notes and goods release (F-04, DN-005, IMP 8 and 9)
-- ---------------------------------------------------------------------
CREATE TABLE delivery_notes (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version             BIGINT        NOT NULL DEFAULT 0,
    number              VARCHAR(30)   NOT NULL,           -- DN-WH-2026-000001
    invoice_id          UUID          NOT NULL REFERENCES sales_invoices(id),
    site_id             UUID          REFERENCES locations(id),
    status              VARCHAR(10)   NOT NULL,
    collected           BOOLEAN       NOT NULL DEFAULT FALSE,   -- the customer collects at the counter
    vehicle_plate       VARCHAR(20),
    driver_name         VARCHAR(100),
    destination         VARCHAR(150),
    short_reason        VARCHAR(255),                     -- released less than was ready
    prepared_by         VARCHAR(50)   NOT NULL,
    prepared_by_id      BIGINT,
    prepared_at         TIMESTAMP     NOT NULL,
    released_by         VARCHAR(50),
    released_by_id      BIGINT,
    released_at         TIMESTAMP,
    ebm_receipt         VARCHAR(40),                      -- the signed receipt's number when released
    ebm_override_reason VARCHAR(255),                     -- released before the signature (VSDC down), logged
    ebm_override_by     VARCHAR(50),
    copy_recorded_by    VARCHAR(50),
    copy_recorded_at    TIMESTAMP,
    received_by_name    VARCHAR(100),
    received_on         DATE,
    remarks             VARCHAR(500),
    has_issue           BOOLEAN       NOT NULL DEFAULT FALSE,   -- shortage or damage noted by the customer
    cancel_reason       VARCHAR(255),
    cancelled_by        VARCHAR(50),
    cancelled_at        TIMESTAMP,
    created_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          VARCHAR(50),
    updated_at          TIMESTAMP,
    updated_by          VARCHAR(50),
    CONSTRAINT uk_delivery_notes_number UNIQUE (number),
    CONSTRAINT chk_delivery_notes_status CHECK (status IN ('DRAFT', 'RELEASED', 'CANCELLED')),
    CONSTRAINT chk_delivery_notes_transport CHECK (collected OR (vehicle_plate IS NOT NULL AND driver_name IS NOT NULL)),
    CONSTRAINT chk_delivery_notes_released CHECK (status <> 'RELEASED' OR (released_at IS NOT NULL AND released_by IS NOT NULL)),
    CONSTRAINT chk_delivery_notes_copy CHECK (copy_recorded_at IS NULL OR (status = 'RELEASED' AND received_by_name IS NOT NULL
        AND received_on IS NOT NULL)),
    CONSTRAINT chk_delivery_notes_cancelled CHECK (status <> 'CANCELLED' OR (cancel_reason IS NOT NULL AND cancelled_at IS NOT NULL))
);
CREATE INDEX idx_delivery_notes_invoice ON delivery_notes (invoice_id);
CREATE INDEX idx_delivery_notes_status ON delivery_notes (status);

-- What a note releases: the invoice's sheets (sold at payment, still in the warehouse) and its cut pieces. Append-only.
CREATE TABLE delivery_note_lines (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    delivery_note_id  UUID          NOT NULL REFERENCES delivery_notes(id),
    line_no           INTEGER       NOT NULL,
    invoice_line_id   UUID          NOT NULL REFERENCES sales_invoice_lines(id),
    stock_unit_id     UUID          NOT NULL REFERENCES stock_units(id),
    unit_code         VARCHAR(30)   NOT NULL,
    kind              VARCHAR(6)    NOT NULL,
    width_mm          INTEGER       NOT NULL,
    height_mm         INTEGER       NOT NULL,
    CONSTRAINT uk_delivery_note_lines_no UNIQUE (delivery_note_id, line_no),
    CONSTRAINT uk_delivery_note_lines_unit UNIQUE (delivery_note_id, stock_unit_id),
    CONSTRAINT chk_delivery_note_lines_kind CHECK (kind IN ('SHEET', 'PIECE'))
);
CREATE INDEX idx_delivery_note_lines_unit ON delivery_note_lines (stock_unit_id);
CREATE TRIGGER trg_delivery_note_lines_append_only BEFORE UPDATE OR DELETE ON delivery_note_lines
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_modification();

-- ---------------------------------------------------------------------
-- Cut pieces verified before release (IMP 10 step 3, PF-03)
-- ---------------------------------------------------------------------
ALTER TABLE cutting_jobs ADD COLUMN verified_by VARCHAR(50);
ALTER TABLE cutting_jobs ADD COLUMN verified_by_id BIGINT;
ALTER TABLE cutting_jobs ADD COLUMN verified_at TIMESTAMP;
ALTER TABLE cutting_jobs ADD COLUMN verify_note VARCHAR(500);
ALTER TABLE cutting_jobs ADD CONSTRAINT chk_cutting_jobs_verified CHECK (verified_at IS NULL
    OR (status = 'COMPLETED' AND verified_by IS NOT NULL));

CREATE TABLE cutting_piece_checks (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cutting_job_id   UUID          NOT NULL REFERENCES cutting_jobs(id),
    stock_unit_id    UUID          NOT NULL REFERENCES stock_units(id),
    unit_code        VARCHAR(30)   NOT NULL,
    outcome          VARCHAR(10)   NOT NULL,
    reason           VARCHAR(255),
    rework_job_id    UUID          REFERENCES cutting_jobs(id),   -- the job its size was cut again on
    created_at       TIMESTAMP     NOT NULL,
    user_id          BIGINT,
    username         VARCHAR(50)   NOT NULL,
    CONSTRAINT uk_cutting_piece_checks_unit UNIQUE (stock_unit_id),
    CONSTRAINT chk_cutting_piece_checks_outcome CHECK (outcome IN ('PASSED', 'REJECTED')),
    CONSTRAINT chk_cutting_piece_checks_reason CHECK (outcome = 'PASSED' OR reason IS NOT NULL)
);
CREATE INDEX idx_cutting_piece_checks_job ON cutting_piece_checks (cutting_job_id);
CREATE TRIGGER trg_cutting_piece_checks_append_only BEFORE UPDATE OR DELETE ON cutting_piece_checks
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_modification();

-- ---------------------------------------------------------------------
-- Documents (the cutting drawing, the signed delivery note), notifications
-- ---------------------------------------------------------------------
ALTER TABLE attachments DROP CONSTRAINT chk_attachments_owner;
ALTER TABLE attachments ADD CONSTRAINT chk_attachments_owner CHECK (owner_type IN
    ('GOODS_RECEIPT', 'SHIPMENT', 'SUPPLIER_INVOICE', 'STOCK_ADJUSTMENT', 'VEHICLE', 'DRIVER', 'TRIP', 'VEHICLE_COST',
     'CREDIT_NOTE_REQUEST', 'RECEIVABLE_WRITE_OFF', 'CUSTOMER', 'SUPPLIER', 'CREDIT_APPLICATION', 'SITE_TRANSFER',
     'CUTTING_JOB', 'DELIVERY_NOTE'));

ALTER TABLE notifications DROP CONSTRAINT chk_notifications_kind;
ALTER TABLE notifications ADD CONSTRAINT chk_notifications_kind
    CHECK (kind IN ('LOW_STOCK', 'APPROVAL', 'DECISION', 'SYSTEM', 'EBM', 'FLEET', 'SYNC', 'EOD', 'CREDIT', 'TRANSFER',
                    'ORDER', 'DELIVERY'));

-- ---------------------------------------------------------------------
-- Numbers and settings
-- ---------------------------------------------------------------------
-- SALES_ORDER (SO) is numbered since V5
INSERT INTO number_sequences (doc_type, branch_code, prefix, reset_policy, padding, created_by) VALUES
('DELIVERY_NOTE',  'WH', 'DN', 'YEARLY', 6, 'system');

INSERT INTO settings (setting_key, setting_value, created_by) VALUES
('sales.order-desk',          'true',  'system'),   -- the till takes payment only for orders the Order Desk raised (D4)
('sales.order-valid-days',    '3',     'system'),   -- an order not paid within this many days lapses
('sales.release-by-note',     'true',  'system'),   -- goods leave only on a delivery note released by the warehouse (F-04)
('ebm.before-release',        'true',  'system'),   -- no goods before the EBM receipt is signed, unless overridden (D3)
('delivery.signed-copy-days', '3',     'system'),   -- working days for the signed copy to come back (IMP 9.3)
('cutting.verify',            'true',  'system');   -- cut pieces are verified by another person before release (IMP 10)

-- ---------------------------------------------------------------------
-- Security
-- ---------------------------------------------------------------------
INSERT INTO pages (code, name, module, path, icon, sort_order) VALUES
('SALES_ORDERS',    'Sales Orders',    'Sales',     '/sales-orders',    'clipboard', 22),
('DELIVERY_NOTES',  'Delivery Notes',  'Inventory', '/delivery-notes',  'truck',     14),
('TERRITORIES',     'Territories',     'Sales',     '/territories',     'map',       29);

INSERT INTO permissions (code, name, module, action, description) VALUES
('VIEW_SALES_ORDER',      'View Sales Orders',           'Sales',     'VIEW',     'See the sales orders raised by the Order Desk'),
('CREATE_SALES_ORDER',    'Raise Sales Orders',          'Sales',     'CREATE',   'Take an order: customer, channel, glass, sizes and prices, checked against stock and credit'),
('VIEW_DELIVERY_NOTE',    'View Delivery Notes',         'Inventory', 'VIEW',     'See delivery notes and what each released'),
('PREPARE_DELIVERY',      'Prepare Delivery Notes',      'Inventory', 'CREATE',   'Prepare the release of a paid invoice''s goods and record the signed copy'),
('RELEASE_GOODS',         'Release Goods',               'Inventory', 'RELEASE',  'Release a delivery note prepared by someone else: the goods leave'),
('OVERRIDE_EBM_RELEASE',  'Release Before the EBM Signature', 'Inventory', 'OVERRIDE', 'Release goods while the EBM receipt waits for its signature (VSDC down), with a reason'),
('VERIFY_CUT',            'Verify Cut Pieces',           'Production', 'VERIFY',  'Measure and accept the pieces of a cut recorded by someone else, or send them back to be cut again'),
('MANAGE_TERRITORIES',    'Manage Territories',          'Sales',     'MANAGE',   'Add and change territories and their regional sales managers'),
('ALERT_DELIVERY',        'Delivery Alerts',             'Inventory', 'ALERT',    'Be told of goods waiting for release and of signed delivery notes not back');

INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE (p.code = 'SALES_ORDERS' AND r.code IN ('ADMIN', 'OWNER', 'CASHIER', 'BOOKKEEPING_CASHIER', 'ACCOUNTANT', 'AUDITOR', 'COO',
        'DIRECTOR_COMMERCIAL', 'ORDER_DESK', 'SALES_RECORDING', 'CREDIT_CONTROL', 'INTERNAL_CONTROLLER', 'DIRECTOR_FINANCE'))
   OR (p.code = 'DELIVERY_NOTES' AND r.code IN ('ADMIN', 'OWNER', 'CASHIER', 'WAREHOUSE_SUPERVISOR', 'ACCOUNTANT', 'AUDITOR', 'COO',
        'DIRECTOR_SUPPLY_CHAIN', 'WAREHOUSE_MANAGER', 'DISPATCH_OFFICER', 'INVENTORY_TRANSACTIONS', 'INTERNAL_CONTROLLER',
        'ORDER_DESK', 'SALES_RECORDING', 'DIRECTOR_COMMERCIAL'))
   OR (p.code = 'TERRITORIES' AND r.code IN ('ADMIN', 'OWNER', 'COO', 'DIRECTOR_COMMERCIAL', 'AUDITOR', 'INTERNAL_CONTROLLER'))
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE (r.code = 'ADMIN' AND p.code IN ('VIEW_SALES_ORDER', 'CREATE_SALES_ORDER', 'VIEW_DELIVERY_NOTE', 'PREPARE_DELIVERY',
        'RELEASE_GOODS', 'OVERRIDE_EBM_RELEASE', 'VERIFY_CUT', 'MANAGE_TERRITORIES', 'ALERT_DELIVERY'))
   OR (r.code = 'OWNER' AND p.code IN ('VIEW_SALES_ORDER', 'VIEW_DELIVERY_NOTE', 'OVERRIDE_EBM_RELEASE', 'MANAGE_TERRITORIES'))
   OR (r.code IN ('CASHIER', 'BOOKKEEPING_CASHIER') AND p.code IN ('VIEW_SALES_ORDER', 'VIEW_DELIVERY_NOTE'))
   OR (r.code = 'ORDER_DESK' AND p.code IN ('VIEW_SALES_ORDER', 'CREATE_SALES_ORDER', 'VIEW_DELIVERY_NOTE'))
   OR (r.code IN ('ACCOUNTANT', 'AUDITOR', 'INTERNAL_CONTROLLER', 'SALES_RECORDING', 'CREDIT_CONTROL', 'DIRECTOR_FINANCE')
        AND p.code IN ('VIEW_SALES_ORDER', 'VIEW_DELIVERY_NOTE'))
   OR (r.code = 'INTERNAL_CONTROLLER' AND p.code = 'ALERT_DELIVERY')
   OR (r.code = 'COO' AND p.code IN ('VIEW_SALES_ORDER', 'VIEW_DELIVERY_NOTE', 'OVERRIDE_EBM_RELEASE', 'MANAGE_TERRITORIES'))
   OR (r.code = 'DIRECTOR_COMMERCIAL' AND p.code IN ('VIEW_SALES_ORDER', 'VIEW_DELIVERY_NOTE', 'MANAGE_TERRITORIES'))
   OR (r.code = 'DIRECTOR_SUPPLY_CHAIN' AND p.code IN ('VIEW_DELIVERY_NOTE', 'RELEASE_GOODS', 'OVERRIDE_EBM_RELEASE', 'VERIFY_CUT',
        'ALERT_DELIVERY'))
   OR (r.code IN ('WAREHOUSE_MANAGER', 'WAREHOUSE_SUPERVISOR') AND p.code IN ('VIEW_DELIVERY_NOTE', 'RELEASE_GOODS', 'VERIFY_CUT',
        'ALERT_DELIVERY'))
   OR (r.code IN ('DISPATCH_OFFICER', 'INVENTORY_TRANSACTIONS') AND p.code IN ('VIEW_DELIVERY_NOTE', 'PREPARE_DELIVERY'))
   OR (r.code = 'DISPATCH_OFFICER' AND p.code = 'ALERT_DELIVERY')
ON CONFLICT DO NOTHING;
