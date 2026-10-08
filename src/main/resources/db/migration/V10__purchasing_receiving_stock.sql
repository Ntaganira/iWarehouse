-- =====================================================================
-- V10: Purchasing, receiving and stock units
-- PRC-01: purchase orders in the supplier's currency, sheets by product,
--         size and quantity, priced per m².
-- PRC-02: goods receipts against a PO, one crate batch per crate; posting
--         creates one stock unit per sheet (INV-01, INV-02) on its rack.
-- PRC-06: sheets broken on arrival are counted on the crate and never
--         enter stock.
-- INV-03: every unit has a label code (U-WH-000001) printed as a QR.
-- INV-04: every change of a unit's location or status is an append-only
--         stock movement.
-- PRC-05: products keep a moving average cost per m² in RWF, updated
--         when a receipt is posted (at the PO price; the import cost sheet
--         adds freight, duty and clearing later).
-- =====================================================================

ALTER TABLE products ADD COLUMN mac_per_m2 NUMERIC(18,4);  -- RWF; NULL until the first receipt
ALTER TABLE products ADD CONSTRAINT chk_products_mac CHECK (mac_per_m2 IS NULL OR mac_per_m2 >= 0);

-- ---------------------------------------------------------------------
-- Purchase orders (PRC-01)
-- ---------------------------------------------------------------------
CREATE TABLE purchase_orders (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version         BIGINT        NOT NULL DEFAULT 0,
    number          VARCHAR(30)   NOT NULL,          -- PO-WH-2026-000001
    supplier_id     UUID          NOT NULL REFERENCES suppliers(id),
    status          VARCHAR(20)   NOT NULL DEFAULT 'DRAFT',
    order_date      DATE          NOT NULL,
    expected_date   DATE,
    currency_code   VARCHAR(3)    NOT NULL REFERENCES currencies(code), -- the supplier's, fixed on the order
    incoterm        VARCHAR(3),
    supplier_ref    VARCHAR(60),                     -- proforma or quotation number
    notes           VARCHAR(500),
    ordered_at      TIMESTAMP,                       -- when it was placed with the supplier
    ordered_by      VARCHAR(50),
    closed_reason   VARCHAR(255),                    -- why it was cancelled or closed short
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by      VARCHAR(50),
    updated_at      TIMESTAMP,
    updated_by      VARCHAR(50),
    CONSTRAINT uk_purchase_orders_number UNIQUE (number),
    CONSTRAINT chk_purchase_orders_status CHECK (status IN
        ('DRAFT', 'ORDERED', 'PARTIALLY_RECEIVED', 'RECEIVED', 'CLOSED', 'CANCELLED')),
    CONSTRAINT chk_purchase_orders_incoterm CHECK (incoterm IS NULL OR incoterm IN
        ('EXW', 'FCA', 'FAS', 'FOB', 'CFR', 'CIF', 'CPT', 'CIP', 'DAP', 'DPU', 'DDP')),
    CONSTRAINT chk_purchase_orders_dates CHECK (expected_date IS NULL OR expected_date >= order_date),
    CONSTRAINT chk_purchase_orders_placed CHECK (status IN ('DRAFT', 'CANCELLED') OR ordered_at IS NOT NULL),
    CONSTRAINT chk_purchase_orders_reason CHECK (status NOT IN ('CLOSED', 'CANCELLED') OR closed_reason IS NOT NULL)
);

CREATE INDEX idx_purchase_orders_supplier ON purchase_orders (supplier_id);
CREATE INDEX idx_purchase_orders_status ON purchase_orders (status);

-- Sheets ordered: product, size, quantity and price per m² in the order's currency.
CREATE TABLE purchase_order_lines (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version            BIGINT         NOT NULL DEFAULT 0,
    purchase_order_id  UUID           NOT NULL REFERENCES purchase_orders(id),
    line_no            INTEGER        NOT NULL,
    product_id         UUID           NOT NULL REFERENCES products(id),
    width_mm           INTEGER        NOT NULL,
    height_mm          INTEGER        NOT NULL,
    quantity           INTEGER        NOT NULL,      -- sheets
    price_per_m2       NUMERIC(18,4)  NOT NULL,
    received_qty       INTEGER        NOT NULL DEFAULT 0,  -- sheets delivered (good + broken) by posted receipts
    created_at         TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by         VARCHAR(50),
    updated_at         TIMESTAMP,
    updated_by         VARCHAR(50),
    -- Checked at commit: editing a draft renumbers its lines 1..n.
    CONSTRAINT uk_purchase_order_lines_no UNIQUE (purchase_order_id, line_no) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT chk_purchase_order_lines_size CHECK (width_mm BETWEEN 1 AND 10000 AND height_mm BETWEEN 1 AND 10000),
    CONSTRAINT chk_purchase_order_lines_qty CHECK (quantity > 0),
    CONSTRAINT chk_purchase_order_lines_price CHECK (price_per_m2 > 0),
    CONSTRAINT chk_purchase_order_lines_received CHECK (received_qty BETWEEN 0 AND quantity)
);

CREATE INDEX idx_purchase_order_lines_product ON purchase_order_lines (product_id);

-- ---------------------------------------------------------------------
-- Goods receipts and crate batches (PRC-02, PRC-06)
-- ---------------------------------------------------------------------
CREATE TABLE goods_receipts (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version            BIGINT         NOT NULL DEFAULT 0,
    number             VARCHAR(30)    NOT NULL,      -- GRN-WH-2026-000001
    purchase_order_id  UUID           NOT NULL REFERENCES purchase_orders(id),
    status             VARCHAR(20)    NOT NULL DEFAULT 'DRAFT',
    received_date      DATE           NOT NULL,
    delivery_ref       VARCHAR(60),                  -- delivery note, packing list or container number
    notes              VARCHAR(500),
    -- Rate from the order's currency to RWF on the receipt date, fixed when posted (ACC-02)
    currency_code      VARCHAR(3)     NOT NULL REFERENCES currencies(code),
    rate               NUMERIC(18,6),
    rate_date          DATE,
    rate_source        VARCHAR(10),
    posted_at          TIMESTAMP,
    posted_by          VARCHAR(50),
    cancel_reason      VARCHAR(255),
    created_at         TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by         VARCHAR(50),
    updated_at         TIMESTAMP,
    updated_by         VARCHAR(50),
    CONSTRAINT uk_goods_receipts_number UNIQUE (number),
    CONSTRAINT chk_goods_receipts_status CHECK (status IN ('DRAFT', 'POSTED', 'CANCELLED')),
    CONSTRAINT chk_goods_receipts_posted CHECK (status <> 'POSTED' OR (rate IS NOT NULL AND rate_date IS NOT NULL AND posted_at IS NOT NULL)),
    CONSTRAINT chk_goods_receipts_rate CHECK (rate IS NULL OR rate > 0),
    CONSTRAINT chk_goods_receipts_cancelled CHECK (status <> 'CANCELLED' OR cancel_reason IS NOT NULL)
);

CREATE INDEX idx_goods_receipts_po ON goods_receipts (purchase_order_id);
CREATE INDEX idx_goods_receipts_status ON goods_receipts (status);

-- One crate: sheets of one PO line, all the same size, put on one rack.
CREATE TABLE crate_batches (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version             BIGINT         NOT NULL DEFAULT 0,
    goods_receipt_id    UUID           NOT NULL REFERENCES goods_receipts(id),
    po_line_id          UUID           NOT NULL REFERENCES purchase_order_lines(id),
    product_id          UUID           NOT NULL REFERENCES products(id),
    batch_no            VARCHAR(40)    NOT NULL,     -- crate marking
    width_mm            INTEGER        NOT NULL,
    height_mm           INTEGER        NOT NULL,
    sheets              INTEGER        NOT NULL,     -- good sheets: one stock unit each
    broken              INTEGER        NOT NULL DEFAULT 0,  -- broken on arrival (PRC-06): no stock unit
    location_id         UUID           NOT NULL REFERENCES locations(id),
    cost_per_m2         NUMERIC(18,4),               -- RWF at the PO price, set when posted
    created_at          TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          VARCHAR(50),
    updated_at          TIMESTAMP,
    updated_by          VARCHAR(50),
    CONSTRAINT uk_crate_batches_no UNIQUE (goods_receipt_id, batch_no) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT chk_crate_batches_size CHECK (width_mm BETWEEN 1 AND 10000 AND height_mm BETWEEN 1 AND 10000),
    CONSTRAINT chk_crate_batches_counts CHECK (sheets >= 0 AND broken >= 0 AND sheets + broken > 0),
    CONSTRAINT chk_crate_batches_cost CHECK (cost_per_m2 IS NULL OR cost_per_m2 >= 0)
);

CREATE INDEX idx_crate_batches_po_line ON crate_batches (po_line_id);
CREATE INDEX idx_crate_batches_product ON crate_batches (product_id);

-- ---------------------------------------------------------------------
-- Stock units (INV-01, INV-02; SRS 6.2)
-- ---------------------------------------------------------------------
CREATE TABLE stock_units (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version          BIGINT         NOT NULL DEFAULT 0,
    code             VARCHAR(30)    NOT NULL,        -- on the label: U-WH-000001
    product_id       UUID           NOT NULL REFERENCES products(id),
    kind             VARCHAR(10)    NOT NULL,        -- SHEET, CUT_PIECE, OFFCUT
    crate_batch_id   UUID           REFERENCES crate_batches(id),
    parent_unit_id   UUID           REFERENCES stock_units(id),   -- the unit it was cut from
    width_mm         INTEGER        NOT NULL,
    height_mm        INTEGER        NOT NULL,
    area_m2          NUMERIC(10,4)  NOT NULL,
    weight_kg        NUMERIC(10,2)  NOT NULL,
    status           VARCHAR(12)    NOT NULL,
    location_id      UUID           REFERENCES locations(id),
    unit_cost        NUMERIC(18,2)  NOT NULL,        -- RWF
    created_at       TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by       VARCHAR(50),
    updated_at       TIMESTAMP,
    updated_by       VARCHAR(50),
    CONSTRAINT uk_stock_units_code UNIQUE (code),
    CONSTRAINT chk_stock_units_kind CHECK (kind IN ('SHEET', 'CUT_PIECE', 'OFFCUT')),
    CONSTRAINT chk_stock_units_status CHECK (status IN
        ('RECEIVED', 'AVAILABLE', 'RESERVED', 'IN_CUTTING', 'CONSUMED', 'ON_VEHICLE', 'SOLD', 'BROKEN')),
    CONSTRAINT chk_stock_units_size CHECK (width_mm BETWEEN 1 AND 10000 AND height_mm BETWEEN 1 AND 10000),
    CONSTRAINT chk_stock_units_measures CHECK (area_m2 > 0 AND weight_kg >= 0 AND unit_cost >= 0),
    -- A unit in stock is always somewhere.
    CONSTRAINT chk_stock_units_location CHECK (status IN ('CONSUMED', 'SOLD', 'BROKEN') OR location_id IS NOT NULL)
);

CREATE INDEX idx_stock_units_product_status ON stock_units (product_id, status);
CREATE INDEX idx_stock_units_location_status ON stock_units (location_id, status);
CREATE INDEX idx_stock_units_crate ON stock_units (crate_batch_id);
CREATE INDEX idx_stock_units_parent ON stock_units (parent_unit_id);

-- Every change of a unit's location or status (INV-04). Append-only, like the audit tables.
CREATE TABLE stock_movements (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    stock_unit_id     UUID          NOT NULL REFERENCES stock_units(id),
    moved_at          TIMESTAMP     NOT NULL,
    movement_type     VARCHAR(20)   NOT NULL,
    from_location_id  UUID          REFERENCES locations(id),
    to_location_id    UUID          REFERENCES locations(id),
    from_status       VARCHAR(12),
    to_status         VARCHAR(12)   NOT NULL,
    reason            VARCHAR(255),
    ref_type          VARCHAR(30),                  -- GOODS_RECEIPT, ...
    ref_id            UUID,
    ref_number        VARCHAR(30),
    user_id           BIGINT,
    username          VARCHAR(50)   NOT NULL,
    CONSTRAINT chk_stock_movements_type CHECK (movement_type IN ('RECEIPT'))
);

CREATE INDEX idx_stock_movements_unit ON stock_movements (stock_unit_id, moved_at);
CREATE INDEX idx_stock_movements_ref ON stock_movements (ref_id);

CREATE OR REPLACE FUNCTION forbid_ledger_modification() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Ledger table % is append-only: % is not allowed; post a reversing entry instead', TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_stock_movements_append_only
    BEFORE UPDATE OR DELETE ON stock_movements
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_modification();

-- Label codes of stock units (INV-03): never reset, so a code is never reused.
INSERT INTO number_sequences (doc_type, branch_code, prefix, reset_policy, padding, created_by) VALUES
('STOCK_UNIT', 'WH', 'U', 'NEVER', 6, 'system');

-- ---------------------------------------------------------------------
-- Security (SRS 2.2). PROCUREMENT and STOCK pages exist since V1.
-- ---------------------------------------------------------------------
INSERT INTO pages (code, name, module, path, icon, sort_order) VALUES
('RECEIVING', 'Goods Receipts', 'Procurement', '/goods-receipts', 'inbox', 4);

INSERT INTO permissions (code, name, module, action, description) VALUES
('VIEW_PURCHASE_ORDER',   'View Purchase Orders',   'Purchase Orders', 'VIEW',    'View purchase orders and their prices'),
('MANAGE_PURCHASE_ORDER', 'Manage Purchase Orders', 'Purchase Orders', 'MANAGE',  'Add and edit draft orders, place, cancel and close orders'),
('VIEW_GOODS_RECEIPT',    'View Goods Receipts',    'Goods Receipts',  'VIEW',    'View goods receipts and their crates'),
('RECEIVE_GOODS',         'Receive Goods',          'Goods Receipts',  'RECEIVE', 'Record crates against a purchase order and post them into stock'),
('VIEW_STOCK',            'View Stock',             'Inventory',       'VIEW',    'View stock units and their movements'),
('VIEW_STOCK_COST',       'View Stock Cost',        'Inventory',       'VIEW',    'See unit costs and moving average cost'),
('PRINT_LABEL',           'Print Labels',           'Inventory',       'PRINT',   'Print stock unit labels');

-- ADMIN: everything.
INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE r.code = 'ADMIN' AND p.code IN ('PROCUREMENT', 'RECEIVING', 'STOCK')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code = 'ADMIN' AND p.module IN ('Purchase Orders', 'Goods Receipts', 'Inventory')
ON CONFLICT DO NOTHING;

-- Procurement orders and receives; the warehouse supervisor receives against
-- placed orders; cutting operators and cashiers see stock (no costs); owner,
-- accountant and auditor read everything.
INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE (p.code IN ('PROCUREMENT', 'RECEIVING') AND r.code IN ('PROCUREMENT', 'WAREHOUSE_SUPERVISOR', 'OWNER', 'ACCOUNTANT', 'AUDITOR'))
   OR (p.code = 'STOCK' AND r.code IN ('PROCUREMENT', 'WAREHOUSE_SUPERVISOR', 'CUTTING_OPERATOR', 'CASHIER',
                                        'OWNER', 'ACCOUNTANT', 'AUDITOR'))
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE (p.code IN ('VIEW_PURCHASE_ORDER', 'VIEW_GOODS_RECEIPT')
           AND r.code IN ('PROCUREMENT', 'WAREHOUSE_SUPERVISOR', 'OWNER', 'ACCOUNTANT', 'AUDITOR'))
   OR (p.code = 'MANAGE_PURCHASE_ORDER' AND r.code = 'PROCUREMENT')
   OR (p.code = 'RECEIVE_GOODS'         AND r.code IN ('PROCUREMENT', 'WAREHOUSE_SUPERVISOR'))
   OR (p.code = 'VIEW_STOCK'            AND r.code IN ('PROCUREMENT', 'WAREHOUSE_SUPERVISOR', 'CUTTING_OPERATOR', 'CASHIER',
                                                       'OWNER', 'ACCOUNTANT', 'AUDITOR'))
   OR (p.code = 'VIEW_STOCK_COST'       AND r.code IN ('PROCUREMENT', 'WAREHOUSE_SUPERVISOR', 'OWNER', 'ACCOUNTANT', 'AUDITOR'))
   OR (p.code = 'PRINT_LABEL'           AND r.code IN ('PROCUREMENT', 'WAREHOUSE_SUPERVISOR', 'CUTTING_OPERATOR'))
ON CONFLICT DO NOTHING;
