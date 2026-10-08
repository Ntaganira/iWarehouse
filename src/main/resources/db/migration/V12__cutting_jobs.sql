-- =====================================================================
-- V12: Production - cutting jobs, off-cuts and cullet (SRS 4.4, PRD-01..09)
--
-- A cutting job lists the pieces wanted (PRD-01). The operator takes one
-- source unit for it (sheet or off-cut, PRD-02); the unit goes IN_CUTTING.
-- Recording the cut consumes the source and creates a unit per cut piece
-- and per usable off-cut (PRD-03, PRD-04); smaller leftovers and the trim
-- are cullet (PRD-05). Areas must balance within 1% (PRD-06) and the source
-- cost is shared by area: units carry their part, cullet and breakage are
-- expensed (PRD-07, PRD-08). What a cut produced is an append-only ledger.
-- =====================================================================

CREATE TABLE cutting_jobs (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version          BIGINT         NOT NULL DEFAULT 0,
    number           VARCHAR(30)    NOT NULL,          -- CUT-WH-2026-000001
    status           VARCHAR(12)    NOT NULL DEFAULT 'DRAFT',
    purpose          VARCHAR(10)    NOT NULL,          -- STOCK, CUSTOMER
    customer_id      UUID           REFERENCES customers(id),
    customer_ref     VARCHAR(60),                      -- quote or order the customer was given
    product_id       UUID           NOT NULL REFERENCES products(id),
    due_date         DATE,
    notes            VARCHAR(500),
    parent_job_id    UUID           REFERENCES cutting_jobs(id),   -- the job this one cuts the rest of
    -- The source taken (PRD-02); cleared again if the operator puts it back.
    source_unit_id   UUID           REFERENCES stock_units(id),
    source_code      VARCHAR(30),
    source_width_mm  INTEGER,
    source_height_mm INTEGER,
    source_area_m2   NUMERIC(10,4),
    operator_id      BIGINT,
    operator_name    VARCHAR(50),
    started_at       TIMESTAMP,
    -- The cut (PRD-03..07): set once, never changed.
    source_cost      NUMERIC(18,2),                    -- RWF, when it was cut
    pieces_area_m2   NUMERIC(10,4),
    offcut_area_m2   NUMERIC(10,4),
    cullet_area_m2   NUMERIC(10,4),                    -- small leftovers and trim
    cullet_kg        NUMERIC(10,2),
    broken_area_m2   NUMERIC(10,4),
    cullet_cost      NUMERIC(18,2),                    -- expensed to spoilage (RWF)
    broken_cost      NUMERIC(18,2),                    -- expensed to spoilage (RWF)
    yield_percent    NUMERIC(5,2),                     -- pieces + off-cuts / source (PRD-09)
    completed_at     TIMESTAMP,
    completed_by     VARCHAR(50),
    cancel_reason    VARCHAR(255),
    created_at       TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by       VARCHAR(50),
    updated_at       TIMESTAMP,
    updated_by       VARCHAR(50),
    CONSTRAINT uk_cutting_jobs_number UNIQUE (number),
    CONSTRAINT chk_cutting_jobs_status CHECK (status IN ('DRAFT', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT chk_cutting_jobs_purpose CHECK (purpose IN ('STOCK', 'CUSTOMER')),
    -- Pieces for a customer name the customer; pieces for stock name none.
    CONSTRAINT chk_cutting_jobs_customer CHECK ((purpose = 'CUSTOMER') = (customer_id IS NOT NULL)),
    CONSTRAINT chk_cutting_jobs_started CHECK (status NOT IN ('IN_PROGRESS', 'COMPLETED')
        OR (source_unit_id IS NOT NULL AND source_area_m2 > 0 AND operator_name IS NOT NULL AND started_at IS NOT NULL)),
    CONSTRAINT chk_cutting_jobs_completed CHECK (status <> 'COMPLETED'
        OR (completed_at IS NOT NULL AND source_cost IS NOT NULL AND pieces_area_m2 IS NOT NULL
            AND offcut_area_m2 IS NOT NULL AND cullet_area_m2 IS NOT NULL AND cullet_kg IS NOT NULL
            AND broken_area_m2 IS NOT NULL AND cullet_cost IS NOT NULL AND broken_cost IS NOT NULL
            AND yield_percent IS NOT NULL)),
    CONSTRAINT chk_cutting_jobs_cancelled CHECK (status <> 'CANCELLED' OR cancel_reason IS NOT NULL),
    CONSTRAINT chk_cutting_jobs_measures CHECK (
        COALESCE(pieces_area_m2, 0) >= 0 AND COALESCE(offcut_area_m2, 0) >= 0 AND COALESCE(cullet_area_m2, 0) >= 0
        AND COALESCE(cullet_kg, 0) >= 0 AND COALESCE(broken_area_m2, 0) >= 0 AND COALESCE(source_cost, 0) >= 0
        AND COALESCE(cullet_cost, 0) >= 0 AND COALESCE(broken_cost, 0) >= 0
        AND COALESCE(yield_percent, 0) BETWEEN 0 AND 100)
);

CREATE INDEX idx_cutting_jobs_status ON cutting_jobs (status);
CREATE INDEX idx_cutting_jobs_completed ON cutting_jobs (completed_at);
CREATE INDEX idx_cutting_jobs_parent ON cutting_jobs (parent_job_id);
-- A unit is the source of one job at a time, and is cut only once (INV-05).
CREATE UNIQUE INDEX uk_cutting_jobs_active_source ON cutting_jobs (source_unit_id)
    WHERE status IN ('IN_PROGRESS', 'COMPLETED');

-- The pieces wanted (PRD-01). Fixed once a source is taken.
CREATE TABLE cutting_job_lines (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version          BIGINT         NOT NULL DEFAULT 0,
    cutting_job_id   UUID           NOT NULL REFERENCES cutting_jobs(id),
    line_no          INTEGER        NOT NULL,
    width_mm         INTEGER        NOT NULL,
    height_mm        INTEGER        NOT NULL,
    quantity         INTEGER        NOT NULL,
    processing       VARCHAR(200),                     -- processing service codes: EDGING,DRILLING
    mark             VARCHAR(60),                      -- the customer's mark, e.g. "Kitchen window"
    cut_qty          INTEGER,                          -- set when the cut is recorded
    created_at       TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by       VARCHAR(50),
    updated_at       TIMESTAMP,
    updated_by       VARCHAR(50),
    CONSTRAINT uk_cutting_job_lines_no UNIQUE (cutting_job_id, line_no) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT chk_cutting_job_lines_size CHECK (width_mm BETWEEN 1 AND 10000 AND height_mm BETWEEN 1 AND 10000),
    CONSTRAINT chk_cutting_job_lines_qty CHECK (quantity BETWEEN 1 AND 999),
    CONSTRAINT chk_cutting_job_lines_cut CHECK (cut_qty IS NULL OR cut_qty BETWEEN 0 AND quantity)
);

CREATE INDEX idx_cutting_job_lines_job ON cutting_job_lines (cutting_job_id);

-- What a cut produced, one row per unit created, leftover entered or breakage (PRD-03..08), with its part
-- of the source cost (PRD-07). The parts add up to the source cost. Append-only.
CREATE TABLE cutting_job_outputs (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cutting_job_id   UUID           NOT NULL REFERENCES cutting_jobs(id),
    kind             VARCHAR(8)     NOT NULL,          -- PIECE, OFFCUT, CULLET, BROKEN
    job_line_id      UUID           REFERENCES cutting_job_lines(id),
    width_mm         INTEGER,                          -- none for the trim
    height_mm        INTEGER,
    quantity         INTEGER        NOT NULL,
    area_m2          NUMERIC(10,4)  NOT NULL,          -- of all of them
    weight_kg        NUMERIC(10,2)  NOT NULL,
    stock_unit_id    UUID           REFERENCES stock_units(id),
    reason           VARCHAR(20),                      -- breakage reason (PRD-08)
    note             VARCHAR(255),
    cost             NUMERIC(18,2)  NOT NULL,          -- RWF part of the source cost
    created_at       TIMESTAMP      NOT NULL,
    user_id          BIGINT,
    username         VARCHAR(50)    NOT NULL,
    CONSTRAINT chk_cutting_job_outputs_kind CHECK (kind IN ('PIECE', 'OFFCUT', 'CULLET', 'BROKEN')),
    CONSTRAINT chk_cutting_job_outputs_unit CHECK ((kind IN ('PIECE', 'OFFCUT')) = (stock_unit_id IS NOT NULL)),
    CONSTRAINT chk_cutting_job_outputs_one CHECK (kind NOT IN ('PIECE', 'OFFCUT') OR quantity = 1),
    CONSTRAINT chk_cutting_job_outputs_reason CHECK ((kind = 'BROKEN') = (reason IS NOT NULL)),
    CONSTRAINT chk_cutting_job_outputs_reason_code CHECK (reason IS NULL
        OR reason IN ('HANDLING', 'CUTTING_ERROR', 'GLASS_DEFECT', 'TOOL', 'OTHER')),
    CONSTRAINT chk_cutting_job_outputs_size CHECK ((width_mm IS NULL AND height_mm IS NULL AND kind = 'CULLET')
        OR (width_mm BETWEEN 1 AND 10000 AND height_mm BETWEEN 1 AND 10000)),
    CONSTRAINT chk_cutting_job_outputs_measures CHECK (quantity > 0 AND area_m2 >= 0 AND weight_kg >= 0 AND cost >= 0)
);

CREATE INDEX idx_cutting_job_outputs_job ON cutting_job_outputs (cutting_job_id);
CREATE INDEX idx_cutting_job_outputs_unit ON cutting_job_outputs (stock_unit_id);

CREATE TRIGGER trg_cutting_job_outputs_append_only
    BEFORE UPDATE OR DELETE ON cutting_job_outputs
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_modification();

-- New stock movements (INV-04) and cost entries (PRD-07).
ALTER TABLE stock_movements DROP CONSTRAINT chk_stock_movements_type;
ALTER TABLE stock_movements ADD CONSTRAINT chk_stock_movements_type CHECK (movement_type IN
    ('RECEIPT', 'CUTTING_START', 'CUTTING_RELEASE', 'CUTTING_CONSUMED', 'CUTTING_OUTPUT'));

ALTER TABLE stock_cost_entries DROP CONSTRAINT chk_stock_cost_entries_type;
ALTER TABLE stock_cost_entries ADD CONSTRAINT chk_stock_cost_entries_type CHECK (entry_type IN
    ('RECEIPT', 'LANDED_COST', 'CUTTING'));

-- ---------------------------------------------------------------------
-- Security (SRS 2.2). The PRODUCTION page (/cutting-jobs) and the
-- CUTTING_JOB number sequence exist since V1 and V5.
-- ---------------------------------------------------------------------
INSERT INTO pages (code, name, module, path, icon, sort_order) VALUES
('CUTTING_YIELD', 'Cutting Yield', 'Production', '/cutting-jobs/yield', 'chart', 3);

INSERT INTO permissions (code, name, module, action, description) VALUES
('VIEW_CUTTING_JOB',    'View Cutting Jobs',    'Cutting Jobs', 'VIEW',    'View cutting jobs, the pieces wanted and what each cut produced'),
('MANAGE_CUTTING_JOB',  'Manage Cutting Jobs',  'Cutting Jobs', 'MANAGE',  'Add cutting jobs, edit them until a sheet is taken, cancel them'),
('EXECUTE_CUTTING_JOB', 'Cut Glass',            'Cutting Jobs', 'EXECUTE', 'Take a sheet or off-cut for a job, put it back, record the cut: pieces, off-cuts, cullet and breakage'),
('VIEW_CUTTING_YIELD',  'View Cutting Yield',   'Cutting Jobs', 'VIEW',    'Yield, cullet and breakage by operator, product and period');

-- ADMIN: everything.
INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE r.code = 'ADMIN' AND p.code IN ('PRODUCTION', 'CUTTING_YIELD')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code = 'ADMIN' AND p.module = 'Cutting Jobs'
ON CONFLICT DO NOTHING;

-- Cutting operators and the warehouse supervisor run the cuts; the cashier
-- adds jobs for customers at the counter; owner, accountant and auditor read.
-- Yield is for the supervisor, owner, accountant and auditor.
INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE (p.code = 'PRODUCTION' AND r.code IN ('WAREHOUSE_SUPERVISOR', 'CUTTING_OPERATOR', 'CASHIER', 'OWNER', 'ACCOUNTANT', 'AUDITOR'))
   OR (p.code = 'CUTTING_YIELD' AND r.code IN ('WAREHOUSE_SUPERVISOR', 'OWNER', 'ACCOUNTANT', 'AUDITOR'))
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE (p.code = 'VIEW_CUTTING_JOB'    AND r.code IN ('WAREHOUSE_SUPERVISOR', 'CUTTING_OPERATOR', 'CASHIER', 'OWNER', 'ACCOUNTANT', 'AUDITOR'))
   OR (p.code = 'MANAGE_CUTTING_JOB'  AND r.code IN ('WAREHOUSE_SUPERVISOR', 'CUTTING_OPERATOR', 'CASHIER'))
   OR (p.code = 'EXECUTE_CUTTING_JOB' AND r.code IN ('WAREHOUSE_SUPERVISOR', 'CUTTING_OPERATOR'))
   OR (p.code = 'VIEW_CUTTING_YIELD'  AND r.code IN ('WAREHOUSE_SUPERVISOR', 'OWNER', 'ACCOUNTANT', 'AUDITOR'))
ON CONFLICT DO NOTHING;
