-- =====================================================================
-- V11: Shipments, import costs and landed cost
-- PRC-03: import cost components per shipment (freight, insurance, customs
--         duty, clearing agent, port, transport), each in its own currency.
-- PRC-04: allocated to the crates by area (default), value or weight.
-- PRC-05: landed cost per m² in RWF at the rate of each cost's document
--         date (duty at the customs rate); updates unit costs and the MAC.
-- PRC-06: the claim for sheets broken on arrival, against the shipment.
-- Costs can arrive in several rounds: each posting allocates the costs
-- entered since the last one. The share of units no longer in stock is
-- expensed (cost of sales), the share of broken sheets goes to the claim.
-- Every change of a unit's cost is an append-only cost entry.
-- =====================================================================

CREATE TABLE shipments (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version               BIGINT         NOT NULL DEFAULT 0,
    number                VARCHAR(30)    NOT NULL,      -- SHP-WH-2026-000001
    status                VARCHAR(20)    NOT NULL DEFAULT 'OPEN',
    reference             VARCHAR(60),                  -- container or bill of lading number
    arrival_date          DATE           NOT NULL,
    allocation_method     VARCHAR(10)    NOT NULL DEFAULT 'AREA',
    notes                 VARCHAR(500),
    closed_at             TIMESTAMP,                    -- costs complete
    closed_by             VARCHAR(50),
    cancel_reason         VARCHAR(255),
    -- Claim for sheets broken on arrival (PRC-06), RWF
    claim_status          VARCHAR(10)    NOT NULL DEFAULT 'NONE',
    claim_party           VARCHAR(100),                 -- supplier or insurer
    claim_ref             VARCHAR(60),
    claim_date            DATE,
    claim_amount          NUMERIC(18,2),
    claim_settled_amount  NUMERIC(18,2),
    claim_note            VARCHAR(255),                 -- settlement note or why it was rejected
    created_at            TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by            VARCHAR(50),
    updated_at            TIMESTAMP,
    updated_by            VARCHAR(50),
    CONSTRAINT uk_shipments_number UNIQUE (number),
    CONSTRAINT chk_shipments_status CHECK (status IN ('OPEN', 'CLOSED', 'CANCELLED')),
    CONSTRAINT chk_shipments_method CHECK (allocation_method IN ('AREA', 'VALUE', 'WEIGHT')),
    CONSTRAINT chk_shipments_closed CHECK (status <> 'CLOSED' OR closed_at IS NOT NULL),
    CONSTRAINT chk_shipments_cancelled CHECK (status <> 'CANCELLED' OR cancel_reason IS NOT NULL),
    CONSTRAINT chk_shipments_claim_status CHECK (claim_status IN ('NONE', 'OPEN', 'SETTLED', 'REJECTED')),
    CONSTRAINT chk_shipments_claim CHECK (claim_status = 'NONE'
        OR (claim_party IS NOT NULL AND claim_date IS NOT NULL AND claim_amount IS NOT NULL)),
    CONSTRAINT chk_shipments_claim_amounts CHECK ((claim_amount IS NULL OR claim_amount > 0)
        AND (claim_settled_amount IS NULL OR claim_settled_amount >= 0)),
    CONSTRAINT chk_shipments_claim_settled CHECK (claim_status <> 'SETTLED' OR claim_settled_amount IS NOT NULL),
    CONSTRAINT chk_shipments_claim_rejected CHECK (claim_status <> 'REJECTED' OR claim_note IS NOT NULL)
);

CREATE INDEX idx_shipments_status ON shipments (status);

-- Posted goods receipts that came in the shipment. A receipt belongs to one shipment.
CREATE TABLE shipment_receipts (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version           BIGINT         NOT NULL DEFAULT 0,
    shipment_id       UUID           NOT NULL REFERENCES shipments(id),
    goods_receipt_id  UUID           NOT NULL REFERENCES goods_receipts(id),
    receipt_number    VARCHAR(30)    NOT NULL,          -- copy of the GRN number, for the change log
    created_at        TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by        VARCHAR(50),
    updated_at        TIMESTAMP,
    updated_by        VARCHAR(50),
    CONSTRAINT uk_shipment_receipts_receipt UNIQUE (goods_receipt_id)
);

CREATE INDEX idx_shipment_receipts_shipment ON shipment_receipts (shipment_id);

-- One import cost: a bill in its own currency, converted at the rate of its date when posted.
CREATE TABLE shipment_costs (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version        BIGINT         NOT NULL DEFAULT 0,
    shipment_id    UUID           NOT NULL REFERENCES shipments(id),
    line_no        INTEGER        NOT NULL,
    cost_type      VARCHAR(12)    NOT NULL,
    description    VARCHAR(120),
    supplier_id    UUID           REFERENCES suppliers(id),   -- paid to (carrier, insurer, clearing agent, RRA)
    invoice_ref    VARCHAR(60),
    invoice_date   DATE           NOT NULL,          -- the cost's document date: its rate (PRC-05)
    currency_code  VARCHAR(3)     NOT NULL REFERENCES currencies(code),
    amount         NUMERIC(18,2)  NOT NULL,          -- negative for a credit note
    status         VARCHAR(10)    NOT NULL DEFAULT 'DRAFT',
    rate           NUMERIC(18,6),                    -- fixed when posted (ACC-02)
    rate_date      DATE,
    rate_source    VARCHAR(10),
    posting_no     INTEGER,                          -- the posting that allocated it
    posted_at      TIMESTAMP,
    posted_by      VARCHAR(50),
    created_at     TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by     VARCHAR(50),
    updated_at     TIMESTAMP,
    updated_by     VARCHAR(50),
    -- Checked at commit: editing renumbers the draft lines after the posted ones.
    CONSTRAINT uk_shipment_costs_no UNIQUE (shipment_id, line_no) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT chk_shipment_costs_type CHECK (cost_type IN
        ('FREIGHT', 'INSURANCE', 'DUTY', 'CLEARING', 'PORT', 'TRANSPORT', 'OTHER')),
    CONSTRAINT chk_shipment_costs_status CHECK (status IN ('DRAFT', 'POSTED')),
    CONSTRAINT chk_shipment_costs_amount CHECK (amount <> 0),
    CONSTRAINT chk_shipment_costs_rate CHECK (rate IS NULL OR rate > 0),
    CONSTRAINT chk_shipment_costs_posted CHECK (status <> 'POSTED'
        OR (rate IS NOT NULL AND rate_date IS NOT NULL AND posting_no IS NOT NULL AND posted_at IS NOT NULL))
);

CREATE INDEX idx_shipment_costs_supplier ON shipment_costs (supplier_id);

-- What each posting gave each crate (PRC-04). A ledger: never changed.
CREATE TABLE shipment_allocations (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    shipment_id      UUID           NOT NULL REFERENCES shipments(id),
    posting_no       INTEGER        NOT NULL,
    crate_batch_id   UUID           NOT NULL REFERENCES crate_batches(id),
    method           VARCHAR(10)    NOT NULL,
    basis            NUMERIC(18,4)  NOT NULL,        -- the crate's m², RWF value or kg (good + broken sheets)
    amount           NUMERIC(18,2)  NOT NULL,        -- RWF given to the crate
    stock_amount     NUMERIC(18,2)  NOT NULL,        -- added to units still in stock
    expensed_amount  NUMERIC(18,2)  NOT NULL,        -- share of units no longer in stock: cost of sales
    broken_amount    NUMERIC(18,2)  NOT NULL,        -- share of sheets broken on arrival: claim or loss
    posted_at        TIMESTAMP      NOT NULL,
    user_id          BIGINT,
    username         VARCHAR(50)    NOT NULL,
    CONSTRAINT uk_shipment_allocations UNIQUE (shipment_id, posting_no, crate_batch_id),
    CONSTRAINT chk_shipment_allocations_method CHECK (method IN ('AREA', 'VALUE', 'WEIGHT')),
    CONSTRAINT chk_shipment_allocations_split CHECK (amount = stock_amount + expensed_amount + broken_amount)
);

CREATE INDEX idx_shipment_allocations_crate ON shipment_allocations (crate_batch_id);

CREATE TRIGGER trg_shipment_allocations_append_only
    BEFORE UPDATE OR DELETE ON shipment_allocations
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_modification();

-- Every change of a unit's cost: the receipt cost, then each landed cost added. Append-only.
CREATE TABLE stock_cost_entries (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    stock_unit_id   UUID           NOT NULL REFERENCES stock_units(id),
    entry_type      VARCHAR(12)    NOT NULL,
    amount          NUMERIC(18,2)  NOT NULL,         -- RWF added (the full cost for RECEIPT)
    cost_after      NUMERIC(18,2)  NOT NULL,
    ref_type        VARCHAR(30),                     -- GOODS_RECEIPT, SHIPMENT
    ref_id          UUID,
    ref_number      VARCHAR(30),
    created_at      TIMESTAMP      NOT NULL,
    user_id         BIGINT,
    username        VARCHAR(50)    NOT NULL,
    CONSTRAINT chk_stock_cost_entries_type CHECK (entry_type IN ('RECEIPT', 'LANDED_COST')),
    CONSTRAINT chk_stock_cost_entries_after CHECK (cost_after >= 0)
);

CREATE INDEX idx_stock_cost_entries_unit ON stock_cost_entries (stock_unit_id, created_at);
CREATE INDEX idx_stock_cost_entries_ref ON stock_cost_entries (ref_id);

CREATE TRIGGER trg_stock_cost_entries_append_only
    BEFORE UPDATE OR DELETE ON stock_cost_entries
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_modification();

-- Units received before this migration: their receipt cost.
INSERT INTO stock_cost_entries (stock_unit_id, entry_type, amount, cost_after, ref_type, ref_id, ref_number,
                                created_at, username)
SELECT u.id, 'RECEIPT', u.unit_cost, u.unit_cost, 'GOODS_RECEIPT', r.id, r.number,
       u.created_at, COALESCE(u.created_by, 'system')
FROM stock_units u
JOIN crate_batches c ON c.id = u.crate_batch_id
JOIN goods_receipts r ON r.id = c.goods_receipt_id;

-- ---------------------------------------------------------------------
-- Security (SRS 2.2). The SHIPMENT number sequence exists since V5.
-- ---------------------------------------------------------------------
INSERT INTO pages (code, name, module, path, icon, sort_order) VALUES
('SHIPMENTS', 'Shipments', 'Procurement', '/shipments', 'anchor', 4);

INSERT INTO permissions (code, name, module, action, description) VALUES
('VIEW_SHIPMENT',    'View Shipments',     'Shipments', 'VIEW',   'View shipments, their import costs, landed costs and claims'),
('MANAGE_SHIPMENT',  'Manage Shipments',   'Shipments', 'MANAGE', 'Add shipments, link their receipts, enter import costs, close or cancel them, record claims'),
('POST_LANDED_COST', 'Post Landed Costs',  'Shipments', 'POST',   'Allocate import costs to the crates: changes unit costs and the moving average cost');

-- ADMIN: everything.
INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE r.code = 'ADMIN' AND p.code = 'SHIPMENTS'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code = 'ADMIN' AND p.module = 'Shipments'
ON CONFLICT DO NOTHING;

-- Procurement enters import charges and posts them (SRS 2.2); the warehouse
-- supervisor, owner, accountant and auditor read them.
INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE p.code = 'SHIPMENTS' AND r.code IN ('PROCUREMENT', 'WAREHOUSE_SUPERVISOR', 'OWNER', 'ACCOUNTANT', 'AUDITOR')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE (p.code = 'VIEW_SHIPMENT' AND r.code IN ('PROCUREMENT', 'WAREHOUSE_SUPERVISOR', 'OWNER', 'ACCOUNTANT', 'AUDITOR'))
   OR (p.code IN ('MANAGE_SHIPMENT', 'POST_LANDED_COST') AND r.code = 'PROCUREMENT')
ON CONFLICT DO NOTHING;
