-- =====================================================================
-- V13: Inventory operations (SRS 4.3: INV-05, INV-07, INV-09, INV-10)
--
-- Reservations: a unit is reserved for a customer with a note and
-- released with a reason (INV-05). Transfers move units between racks and
-- slots within rack limits (INV-07, MD-03). Adjustments write units off
-- (broken or missing), find lost units again, add pieces nobody recorded
-- and correct wrong sizes; above the approval limit a second person
-- approves them before they post (INV-07). Units on a pending adjustment
-- are held: nothing else may move them (INV-05).
--
-- LOST is a new final status for units written off as missing, apart from
-- BROKEN (damaged): a lost unit found again comes back to stock.
-- =====================================================================

ALTER TABLE stock_units DROP CONSTRAINT chk_stock_units_status;
ALTER TABLE stock_units ADD CONSTRAINT chk_stock_units_status CHECK (status IN
    ('RECEIVED', 'AVAILABLE', 'RESERVED', 'IN_CUTTING', 'CONSUMED', 'ON_VEHICLE', 'SOLD', 'BROKEN', 'LOST'));
ALTER TABLE stock_units DROP CONSTRAINT chk_stock_units_location;
ALTER TABLE stock_units ADD CONSTRAINT chk_stock_units_location
    CHECK (status IN ('CONSUMED', 'SOLD', 'BROKEN', 'LOST') OR location_id IS NOT NULL);

-- Who a reserved unit is held for (INV-05). Pieces reserved before V13 (cut for a customer) keep none.
ALTER TABLE stock_units ADD COLUMN reserved_customer_id UUID REFERENCES customers(id);
ALTER TABLE stock_units ADD COLUMN reserved_note VARCHAR(255);
ALTER TABLE stock_units ADD CONSTRAINT chk_stock_units_reserved
    CHECK (status = 'RESERVED' OR (reserved_customer_id IS NULL AND reserved_note IS NULL));
CREATE INDEX idx_stock_units_reserved_customer ON stock_units (reserved_customer_id);

ALTER TABLE stock_movements DROP CONSTRAINT chk_stock_movements_type;
ALTER TABLE stock_movements ADD CONSTRAINT chk_stock_movements_type CHECK (movement_type IN
    ('RECEIPT', 'CUTTING_START', 'CUTTING_RELEASE', 'CUTTING_CONSUMED', 'CUTTING_OUTPUT',
     'TRANSFER', 'ADJUSTMENT', 'RESERVE', 'RELEASE'));

ALTER TABLE stock_cost_entries DROP CONSTRAINT chk_stock_cost_entries_type;
ALTER TABLE stock_cost_entries ADD CONSTRAINT chk_stock_cost_entries_type CHECK (entry_type IN
    ('RECEIPT', 'LANDED_COST', 'CUTTING', 'ADJUSTMENT'));

-- ---------------------------------------------------------------------
-- Transfers (INV-07): posted when saved, never changed
-- ---------------------------------------------------------------------
CREATE TABLE stock_transfers (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version          BIGINT         NOT NULL DEFAULT 0,
    number           VARCHAR(30)    NOT NULL,          -- TRF-WH-2026-000001
    to_location_id   UUID           NOT NULL REFERENCES locations(id),
    note             VARCHAR(255),
    posted_at        TIMESTAMP      NOT NULL,
    posted_by        VARCHAR(50),
    created_at       TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by       VARCHAR(50),
    updated_at       TIMESTAMP,
    updated_by       VARCHAR(50),
    CONSTRAINT uk_stock_transfers_number UNIQUE (number)
);

CREATE INDEX idx_stock_transfers_posted ON stock_transfers (posted_at);

CREATE TABLE stock_transfer_lines (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version          BIGINT         NOT NULL DEFAULT 0,
    transfer_id      UUID           NOT NULL REFERENCES stock_transfers(id),
    line_no          INTEGER        NOT NULL,
    stock_unit_id    UUID           NOT NULL REFERENCES stock_units(id),
    unit_code        VARCHAR(30)    NOT NULL,
    from_location_id UUID           NOT NULL REFERENCES locations(id),
    created_at       TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by       VARCHAR(50),
    updated_at       TIMESTAMP,
    updated_by       VARCHAR(50),
    CONSTRAINT uk_stock_transfer_lines_no UNIQUE (transfer_id, line_no),
    CONSTRAINT uk_stock_transfer_lines_unit UNIQUE (transfer_id, stock_unit_id)
);

CREATE INDEX idx_stock_transfer_lines_unit ON stock_transfer_lines (stock_unit_id);

-- ---------------------------------------------------------------------
-- Adjustments (INV-07): posted at once within the approval limit,
-- otherwise pending until a second person approves or rejects them
-- ---------------------------------------------------------------------
CREATE TABLE stock_adjustments (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version          BIGINT         NOT NULL DEFAULT 0,
    number           VARCHAR(30)    NOT NULL,          -- ADJ-WH-2026-000001
    status           VARCHAR(20)    NOT NULL,
    reason           VARCHAR(255)   NOT NULL,
    value_change     NUMERIC(18,2)  NOT NULL DEFAULT 0,   -- RWF: stock value after - before
    value_moved      NUMERIC(18,2)  NOT NULL DEFAULT 0,   -- RWF: sum of the lines' absolute values (approval basis)
    requested_by     VARCHAR(50)    NOT NULL,
    requested_by_id  BIGINT,
    decided_by       VARCHAR(50),
    decided_at       TIMESTAMP,
    decision_note    VARCHAR(255),
    posted_at        TIMESTAMP,
    created_at       TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by       VARCHAR(50),
    updated_at       TIMESTAMP,
    updated_by       VARCHAR(50),
    CONSTRAINT uk_stock_adjustments_number UNIQUE (number),
    CONSTRAINT chk_stock_adjustments_status CHECK (status IN ('PENDING_APPROVAL', 'POSTED', 'REJECTED', 'CANCELLED')),
    CONSTRAINT chk_stock_adjustments_posted CHECK (status <> 'POSTED' OR posted_at IS NOT NULL),
    CONSTRAINT chk_stock_adjustments_closed CHECK (status NOT IN ('REJECTED', 'CANCELLED')
        OR (decision_note IS NOT NULL AND decided_by IS NOT NULL AND decided_at IS NOT NULL)),
    CONSTRAINT chk_stock_adjustments_moved CHECK (value_moved >= 0)
);

CREATE INDEX idx_stock_adjustments_status ON stock_adjustments (status);

CREATE TABLE stock_adjustment_lines (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version          BIGINT         NOT NULL DEFAULT 0,
    adjustment_id    UUID           NOT NULL REFERENCES stock_adjustments(id),
    line_no          INTEGER        NOT NULL,
    kind             VARCHAR(10)    NOT NULL,          -- WRITE_OFF, FOUND, NEW_UNIT, RESIZE
    cause            VARCHAR(10),                      -- write-off: DAMAGED or MISSING
    stock_unit_id    UUID           REFERENCES stock_units(id),   -- the unit written off, found or resized
    unit_code        VARCHAR(30),
    product_id       UUID           REFERENCES products(id),      -- new unit
    unit_kind        VARCHAR(10),                                 -- new unit: SHEET, CUT_PIECE, OFFCUT
    width_mm         INTEGER,                                     -- new unit, or the corrected size
    height_mm        INTEGER,
    location_id      UUID           REFERENCES locations(id),     -- where a new or found unit is
    value_change     NUMERIC(18,2)  NOT NULL,                     -- RWF, final when posted
    result_unit_id   UUID           REFERENCES stock_units(id),   -- the unit a new unit or resize created
    created_at       TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by       VARCHAR(50),
    updated_at       TIMESTAMP,
    updated_by       VARCHAR(50),
    CONSTRAINT uk_stock_adjustment_lines_no UNIQUE (adjustment_id, line_no),
    CONSTRAINT chk_stock_adjustment_lines_kind CHECK (kind IN ('WRITE_OFF', 'FOUND', 'NEW_UNIT', 'RESIZE')),
    CONSTRAINT chk_stock_adjustment_lines_cause CHECK ((kind = 'WRITE_OFF') = (cause IS NOT NULL)
        AND (cause IS NULL OR cause IN ('DAMAGED', 'MISSING'))),
    CONSTRAINT chk_stock_adjustment_lines_fields CHECK (
        (kind = 'WRITE_OFF' AND stock_unit_id IS NOT NULL)
        OR (kind = 'FOUND' AND stock_unit_id IS NOT NULL AND location_id IS NOT NULL)
        OR (kind = 'NEW_UNIT' AND product_id IS NOT NULL AND unit_kind IN ('SHEET', 'CUT_PIECE', 'OFFCUT')
            AND width_mm IS NOT NULL AND height_mm IS NOT NULL AND location_id IS NOT NULL)
        OR (kind = 'RESIZE' AND stock_unit_id IS NOT NULL AND width_mm IS NOT NULL AND height_mm IS NOT NULL)),
    CONSTRAINT chk_stock_adjustment_lines_size CHECK (width_mm IS NULL OR (width_mm BETWEEN 1 AND 10000 AND height_mm BETWEEN 1 AND 10000))
);

CREATE INDEX idx_stock_adjustment_lines_adjustment ON stock_adjustment_lines (adjustment_id);
CREATE INDEX idx_stock_adjustment_lines_unit ON stock_adjustment_lines (stock_unit_id);

-- ---------------------------------------------------------------------
-- Security (SRS 2.2). TRANSFER and ADJUSTMENT number sequences exist since V5.
-- ---------------------------------------------------------------------
INSERT INTO pages (code, name, module, path, icon, sort_order) VALUES
('STOCK_TRANSFERS',   'Stock Transfers',   'Inventory', '/stock-transfers',   'shuffle', 2),
('STOCK_ADJUSTMENTS', 'Stock Adjustments', 'Inventory', '/stock-adjustments', 'sliders', 2),
('STOCK_SUMMARY',     'Stock Summary',     'Inventory', '/stock/summary',     'pie',     2);

INSERT INTO permissions (code, name, module, action, description) VALUES
('VIEW_STOCK_TRANSFER',   'View Stock Transfers',   'Stock Operations', 'VIEW',    'View transfers of units between locations'),
('TRANSFER_STOCK',        'Transfer Stock',         'Stock Operations', 'TRANSFER','Move units to another rack or slot'),
('VIEW_STOCK_ADJUSTMENT', 'View Stock Adjustments', 'Stock Operations', 'VIEW',    'View stock adjustments and their approval'),
('ADJUST_STOCK',          'Adjust Stock',           'Stock Operations', 'ADJUST',  'Write units off, find lost units, add unrecorded pieces, correct sizes'),
('APPROVE_ADJUSTMENT',    'Approve Adjustments',    'Stock Operations', 'APPROVE', 'Approve or reject adjustments above the approval limit (not your own)'),
('RESERVE_STOCK',         'Reserve Stock',          'Stock Operations', 'RESERVE', 'Reserve a unit for a customer and release it');

-- ADMIN: everything.
INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE r.code = 'ADMIN' AND p.code IN ('STOCK_TRANSFERS', 'STOCK_ADJUSTMENTS', 'STOCK_SUMMARY')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code = 'ADMIN' AND p.module = 'Stock Operations'
ON CONFLICT DO NOTHING;

-- The supervisor runs the warehouse (SRS 2.2: transfer, adjust with approval);
-- cutting operators move and write off glass they handle; the owner and the
-- supervisor approve (never their own); cashiers reserve for customers;
-- everyone who sees stock sees the summary (values need VIEW_STOCK_COST).
INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE (p.code = 'STOCK_TRANSFERS'   AND r.code IN ('WAREHOUSE_SUPERVISOR', 'CUTTING_OPERATOR', 'PROCUREMENT', 'OWNER', 'ACCOUNTANT', 'AUDITOR'))
   OR (p.code = 'STOCK_ADJUSTMENTS' AND r.code IN ('WAREHOUSE_SUPERVISOR', 'CUTTING_OPERATOR', 'OWNER', 'ACCOUNTANT', 'AUDITOR'))
   OR (p.code = 'STOCK_SUMMARY'     AND r.code IN ('WAREHOUSE_SUPERVISOR', 'CUTTING_OPERATOR', 'PROCUREMENT', 'CASHIER', 'OWNER', 'ACCOUNTANT', 'AUDITOR'))
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE (p.code = 'VIEW_STOCK_TRANSFER'   AND r.code IN ('WAREHOUSE_SUPERVISOR', 'CUTTING_OPERATOR', 'PROCUREMENT', 'OWNER', 'ACCOUNTANT', 'AUDITOR'))
   OR (p.code = 'TRANSFER_STOCK'        AND r.code IN ('WAREHOUSE_SUPERVISOR', 'CUTTING_OPERATOR'))
   OR (p.code = 'VIEW_STOCK_ADJUSTMENT' AND r.code IN ('WAREHOUSE_SUPERVISOR', 'CUTTING_OPERATOR', 'OWNER', 'ACCOUNTANT', 'AUDITOR'))
   OR (p.code = 'ADJUST_STOCK'          AND r.code IN ('WAREHOUSE_SUPERVISOR', 'CUTTING_OPERATOR'))
   OR (p.code = 'APPROVE_ADJUSTMENT'    AND r.code IN ('WAREHOUSE_SUPERVISOR', 'OWNER'))
   OR (p.code = 'RESERVE_STOCK'         AND r.code IN ('WAREHOUSE_SUPERVISOR', 'CASHIER'))
ON CONFLICT DO NOTHING;
