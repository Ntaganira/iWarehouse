-- =====================================================================
-- V14: Stock counts by scanning (SRS 4.3: INV-08)
--
-- A count covers a place (a rack or slot for a cycle count, a zone or
-- the site for a full count) with all its sub-locations, optionally one
-- glass. While it is open the units on those places are held (INV-05):
-- nothing moves, cuts or adjusts them. Counters scan the labels where
-- they find them. Closing compares the scans with the stock records and
-- keeps one result line per unit: matched, misplaced (its location is
-- corrected by a COUNT movement), missing (written off as LOST through an
-- adjustment, with its approval), a lost unit found (same adjustment),
-- or a label the records place elsewhere, show as gone or do not know.
-- =====================================================================

ALTER TABLE stock_movements DROP CONSTRAINT chk_stock_movements_type;
ALTER TABLE stock_movements ADD CONSTRAINT chk_stock_movements_type CHECK (movement_type IN
    ('RECEIPT', 'CUTTING_START', 'CUTTING_RELEASE', 'CUTTING_CONSUMED', 'CUTTING_OUTPUT',
     'TRANSFER', 'ADJUSTMENT', 'RESERVE', 'RELEASE', 'COUNT'));

CREATE TABLE stock_counts (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version           BIGINT        NOT NULL DEFAULT 0,
    number            VARCHAR(30)   NOT NULL,           -- CNT-WH-2026-000001
    location_id       UUID          NOT NULL REFERENCES locations(id),
    product_id        UUID          REFERENCES products(id),
    status            VARCHAR(20)   NOT NULL DEFAULT 'OPEN',
    note              VARCHAR(255),
    started_at        TIMESTAMP     NOT NULL,
    started_by        VARCHAR(50),
    closed_at         TIMESTAMP,
    closed_by         VARCHAR(50),
    cancel_reason     VARCHAR(255),
    -- the result, set when the count closes
    expected_units    INTEGER,
    counted_units     INTEGER,
    matched_units     INTEGER,
    missing_units     INTEGER,
    misplaced_units   INTEGER,
    extra_units       INTEGER,
    adjustment_id     UUID          REFERENCES stock_adjustments(id),
    adjustment_number VARCHAR(30),
    created_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by        VARCHAR(50),
    updated_at        TIMESTAMP,
    updated_by        VARCHAR(50),
    CONSTRAINT uk_stock_counts_number UNIQUE (number),
    CONSTRAINT chk_stock_counts_status CHECK (status IN ('OPEN', 'CLOSED', 'CANCELLED')),
    CONSTRAINT chk_stock_counts_closed CHECK (status <> 'CLOSED' OR (closed_at IS NOT NULL AND expected_units IS NOT NULL
        AND counted_units IS NOT NULL AND matched_units IS NOT NULL AND missing_units IS NOT NULL
        AND misplaced_units IS NOT NULL AND extra_units IS NOT NULL)),
    CONSTRAINT chk_stock_counts_cancelled CHECK (status <> 'CANCELLED' OR (closed_at IS NOT NULL AND cancel_reason IS NOT NULL)),
    CONSTRAINT chk_stock_counts_open CHECK (status <> 'OPEN' OR closed_at IS NULL)
);

CREATE INDEX idx_stock_counts_status ON stock_counts (status);
CREATE INDEX idx_stock_counts_started ON stock_counts (started_at);

-- The places a count covers: the chosen place and every place under it when the count started.
CREATE TABLE stock_count_places (
    count_id    UUID NOT NULL REFERENCES stock_counts(id),
    location_id UUID NOT NULL REFERENCES locations(id),
    CONSTRAINT pk_stock_count_places PRIMARY KEY (count_id, location_id)
);

CREATE INDEX idx_stock_count_places_location ON stock_count_places (location_id);

-- Labels scanned while the count is open: where each was found. A scan can be removed while open.
CREATE TABLE stock_count_scans (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version       BIGINT       NOT NULL DEFAULT 0,
    count_id      UUID         NOT NULL REFERENCES stock_counts(id),
    code          VARCHAR(30)  NOT NULL,
    stock_unit_id UUID         REFERENCES stock_units(id),   -- none when no unit has that code
    location_id   UUID         NOT NULL REFERENCES locations(id),
    scanned_at    TIMESTAMP    NOT NULL,
    scanned_by    VARCHAR(50),
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by    VARCHAR(50),
    updated_at    TIMESTAMP,
    updated_by    VARCHAR(50),
    CONSTRAINT uk_stock_count_scans_code UNIQUE (count_id, code)
);

CREATE INDEX idx_stock_count_scans_count ON stock_count_scans (count_id);

-- The result: one line per unit expected or scanned, written when the count closes, never changed.
CREATE TABLE stock_count_lines (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    count_id             UUID         NOT NULL REFERENCES stock_counts(id),
    line_no              INTEGER      NOT NULL,
    outcome              VARCHAR(20)  NOT NULL,
    code                 VARCHAR(30)  NOT NULL,
    stock_unit_id        UUID         REFERENCES stock_units(id),
    unit_status          VARCHAR(20),               -- the unit's state when the count closed
    expected_location_id UUID         REFERENCES locations(id),
    found_location_id    UUID         REFERENCES locations(id),
    action               VARCHAR(20)  NOT NULL,
    note                 VARCHAR(255),              -- why nothing was done (held by another document...)
    created_at           TIMESTAMP    NOT NULL,
    username             VARCHAR(50)  NOT NULL,
    CONSTRAINT uk_stock_count_lines_no UNIQUE (count_id, line_no),
    CONSTRAINT chk_stock_count_lines_outcome CHECK (outcome IN
        ('MATCHED', 'MISPLACED', 'MISSING', 'FOUND_LOST', 'ELSEWHERE', 'NOT_IN_STOCK', 'UNKNOWN')),
    CONSTRAINT chk_stock_count_lines_action CHECK (action IN ('NONE', 'MOVED', 'ADJUSTMENT')),
    CONSTRAINT chk_stock_count_lines_unit CHECK (outcome = 'UNKNOWN' OR stock_unit_id IS NOT NULL)
);

CREATE INDEX idx_stock_count_lines_count ON stock_count_lines (count_id);
CREATE INDEX idx_stock_count_lines_unit ON stock_count_lines (stock_unit_id);

CREATE TRIGGER trg_stock_count_lines_append_only
    BEFORE UPDATE OR DELETE ON stock_count_lines
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_modification();

-- ---------------------------------------------------------------------
-- Security (SRS 2.2). The STOCK_COUNT number sequence (CNT) exists since V5.
-- ---------------------------------------------------------------------
INSERT INTO pages (code, name, module, path, icon, sort_order) VALUES
('STOCK_COUNTS', 'Stock Counts', 'Inventory', '/stock-counts', 'clipboard', 2);

INSERT INTO permissions (code, name, module, action, description) VALUES
('VIEW_STOCK_COUNT', 'View Stock Counts', 'Stock Operations', 'VIEW',  'View stock counts and their results'),
('COUNT_STOCK',      'Count Stock',       'Stock Operations', 'COUNT', 'Start a count, scan labels, close or cancel it');

-- ADMIN: everything.
INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE r.code = 'ADMIN' AND p.code = 'STOCK_COUNTS'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code = 'ADMIN' AND p.code IN ('VIEW_STOCK_COUNT', 'COUNT_STOCK')
ON CONFLICT DO NOTHING;

-- The supervisor and cutting operators count the racks they work on; the owner,
-- accountant and auditor read the results (missing glass is written off through
-- an adjustment, approved as any other).
INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE p.code = 'STOCK_COUNTS' AND r.code IN ('WAREHOUSE_SUPERVISOR', 'CUTTING_OPERATOR', 'OWNER', 'ACCOUNTANT', 'AUDITOR')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE (p.code = 'VIEW_STOCK_COUNT' AND r.code IN ('WAREHOUSE_SUPERVISOR', 'CUTTING_OPERATOR', 'OWNER', 'ACCOUNTANT', 'AUDITOR'))
   OR (p.code = 'COUNT_STOCK'      AND r.code IN ('WAREHOUSE_SUPERVISOR', 'CUTTING_OPERATOR'))
ON CONFLICT DO NOTHING;
