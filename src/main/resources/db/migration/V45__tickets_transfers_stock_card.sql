-- =====================================================================
-- V45: Transaction Tickets, transfers between sites, stock reviews (M12 of the plan to cover the client's restructuring
-- documents; not in SRS v2.1)
--   - Transaction Tickets on every stock movement document but sales (decision D6): raised with the document, verified by
--     Supply Chain against the glass, countersigned by Commercial, posted by Finance; a goods receipt posts only from
--     its ticket when Settings ask (BP 7.4, F-05, Annex I)
--   - transfers between sites: requested, approved, dispatched (in transit) and received label by label, differences
--     reported (IMP 11, TRF-007, TCN-008, PF-04)
--   - the daily review of stock movements (IMP 12) and the monthly stock reconciliation (BP 7.4, Table 9, Annex I step 5)
-- =====================================================================

-- ---------------------------------------------------------------------
-- Stock in transit between sites (IMP 3.2, IMP 11.2)
-- ---------------------------------------------------------------------
ALTER TABLE stock_units DROP CONSTRAINT chk_stock_units_status;
ALTER TABLE stock_units ADD CONSTRAINT chk_stock_units_status CHECK (status IN ('RECEIVED', 'AVAILABLE', 'RESERVED', 'IN_CUTTING',
    'CONSUMED', 'ON_VEHICLE', 'SOLD', 'BROKEN', 'LOST', 'IN_TRANSIT'));

ALTER TABLE stock_movements DROP CONSTRAINT chk_stock_movements_type;
ALTER TABLE stock_movements ADD CONSTRAINT chk_stock_movements_type CHECK (movement_type IN
    ('RECEIPT', 'CUTTING_START', 'CUTTING_RELEASE', 'CUTTING_CONSUMED', 'CUTTING_OUTPUT',
     'TRANSFER', 'ADJUSTMENT', 'RESERVE', 'RELEASE', 'COUNT', 'SALE', 'RETURN', 'LOAD',
     'UNLOAD', 'BREAKAGE', 'LOSS', 'DISPATCH', 'ARRIVAL'));

-- ---------------------------------------------------------------------
-- Transfers between sites (IMP 11, TRF-007, TCN-008, PF-04)
-- ---------------------------------------------------------------------
CREATE TABLE site_transfers (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version            BIGINT        NOT NULL DEFAULT 0,
    number             VARCHAR(30)   NOT NULL,
    from_site_id       UUID          NOT NULL REFERENCES locations(id),
    to_site_id         UUID          NOT NULL REFERENCES locations(id),
    reason             VARCHAR(255)  NOT NULL,
    expected_dispatch  DATE          NOT NULL,
    status             VARCHAR(20)   NOT NULL,
    requested_by       VARCHAR(50)   NOT NULL,
    requested_by_id    BIGINT,
    requested_at       TIMESTAMP     NOT NULL,
    decided_by         VARCHAR(50),
    decided_by_id      BIGINT,
    decided_at         TIMESTAMP,
    decision_note      VARCHAR(255),
    vehicle_plate      VARCHAR(20),
    driver_name        VARCHAR(100),
    expected_arrival   TIMESTAMP,
    dispatched_by      VARCHAR(50),
    dispatched_at      TIMESTAMP,
    received_by        VARCHAR(50),
    received_at        TIMESTAMP,
    difference_note    VARCHAR(500),
    created_at         TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by         VARCHAR(50),
    updated_at         TIMESTAMP,
    updated_by         VARCHAR(50),
    CONSTRAINT uk_site_transfers_number UNIQUE (number),
    CONSTRAINT chk_site_transfers_sites CHECK (from_site_id <> to_site_id),
    CONSTRAINT chk_site_transfers_status CHECK (status IN ('REQUESTED', 'APPROVED', 'REJECTED', 'CANCELLED', 'IN_TRANSIT',
        'RECEIVED', 'RECEIVED_SHORT')),
    CONSTRAINT chk_site_transfers_dispatched CHECK (status IN ('REQUESTED', 'APPROVED', 'REJECTED', 'CANCELLED')
        OR (dispatched_at IS NOT NULL AND vehicle_plate IS NOT NULL)),
    CONSTRAINT chk_site_transfers_received CHECK (status NOT IN ('RECEIVED', 'RECEIVED_SHORT') OR received_at IS NOT NULL)
);
CREATE INDEX idx_site_transfers_status ON site_transfers (status);
CREATE INDEX idx_site_transfers_requested ON site_transfers (requested_at DESC);

-- What is asked: so many sheets of a glass (of a size, if it matters)
CREATE TABLE site_transfer_lines (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transfer_id  UUID          NOT NULL REFERENCES site_transfers(id),
    line_no      INTEGER       NOT NULL,
    product_id   UUID          NOT NULL REFERENCES products(id),
    width_mm     INTEGER,
    height_mm    INTEGER,
    quantity     INTEGER       NOT NULL,
    CONSTRAINT uk_site_transfer_lines_no UNIQUE (transfer_id, line_no) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT chk_site_transfer_lines_qty CHECK (quantity > 0),
    CONSTRAINT chk_site_transfer_lines_size CHECK ((width_mm IS NULL) = (height_mm IS NULL)
        AND (width_mm IS NULL OR (width_mm BETWEEN 1 AND 10000 AND height_mm BETWEEN 1 AND 10000)))
);
CREATE INDEX idx_site_transfer_lines_transfer ON site_transfer_lines (transfer_id);

-- What left and what arrived, label by label
CREATE TABLE site_transfer_units (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version              BIGINT        NOT NULL DEFAULT 0,
    transfer_id          UUID          NOT NULL REFERENCES site_transfers(id),
    stock_unit_id        UUID          NOT NULL REFERENCES stock_units(id),
    unit_code            VARCHAR(30)   NOT NULL,
    from_location_id     UUID          NOT NULL REFERENCES locations(id),
    dispatched_at        TIMESTAMP     NOT NULL,
    received_at          TIMESTAMP,
    received_by          VARCHAR(50),
    to_location_id       UUID          REFERENCES locations(id),
    created_at           TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by           VARCHAR(50),
    updated_at           TIMESTAMP,
    updated_by           VARCHAR(50),
    CONSTRAINT uk_site_transfer_units UNIQUE (transfer_id, stock_unit_id),
    CONSTRAINT chk_site_transfer_units_received CHECK ((received_at IS NULL) = (to_location_id IS NULL))
);
CREATE INDEX idx_site_transfer_units_transfer ON site_transfer_units (transfer_id);
CREATE INDEX idx_site_transfer_units_unit ON site_transfer_units (stock_unit_id);

-- ---------------------------------------------------------------------
-- Transaction Tickets (BP 7.4, F-05, Annex I; decision D6)
-- ---------------------------------------------------------------------
CREATE TABLE transaction_tickets (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version            BIGINT        NOT NULL DEFAULT 0,
    number             VARCHAR(30)   NOT NULL,
    kind               VARCHAR(20)   NOT NULL,
    source_type        VARCHAR(30)   NOT NULL,
    source_id          UUID          NOT NULL,
    source_number      VARCHAR(40)   NOT NULL,
    summary            VARCHAR(255)  NOT NULL,
    pieces             INTEGER       NOT NULL,
    area_m2            NUMERIC(12,4) NOT NULL,
    value              NUMERIC(18,2),
    movement_posted    BOOLEAN       NOT NULL,           -- the movement already happened (raised after it) or waits for the ticket
    status             VARCHAR(15)   NOT NULL,
    raised_by          VARCHAR(50)   NOT NULL,
    raised_by_id       BIGINT,
    raised_at          TIMESTAMP     NOT NULL,
    verified_by        VARCHAR(50),
    verified_by_id     BIGINT,
    verified_at        TIMESTAMP,
    verify_note        VARCHAR(255),
    countersigned_by   VARCHAR(50),
    countersigned_by_id BIGINT,
    countersigned_at   TIMESTAMP,
    countersign_note   VARCHAR(255),
    posted_by          VARCHAR(50),
    posted_by_id       BIGINT,
    posted_at          TIMESTAMP,
    rejected_by        VARCHAR(50),
    rejected_at        TIMESTAMP,
    reject_reason      VARCHAR(255),
    created_at         TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by         VARCHAR(50),
    updated_at         TIMESTAMP,
    updated_by         VARCHAR(50),
    CONSTRAINT uk_transaction_tickets_number UNIQUE (number),
    CONSTRAINT uk_transaction_tickets_source UNIQUE (source_type, source_id, kind),
    CONSTRAINT chk_transaction_tickets_kind CHECK (kind IN ('RECEIPT', 'TRANSFER', 'SITE_DISPATCH', 'SITE_ARRIVAL', 'LOAD',
        'RETURN', 'ADJUSTMENT', 'CUSTOMER_RETURN')),
    CONSTRAINT chk_transaction_tickets_status CHECK (status IN ('RAISED', 'VERIFIED', 'COUNTERSIGNED', 'POSTED', 'REJECTED')),
    CONSTRAINT chk_transaction_tickets_steps CHECK ((status = 'RAISED' OR status = 'REJECTED' OR verified_at IS NOT NULL)
        AND (status NOT IN ('COUNTERSIGNED', 'POSTED') OR countersigned_at IS NOT NULL)
        AND (status <> 'POSTED' OR posted_at IS NOT NULL)
        AND (status <> 'REJECTED' OR (rejected_at IS NOT NULL AND reject_reason IS NOT NULL)))
);
CREATE INDEX idx_transaction_tickets_status ON transaction_tickets (status);
CREATE INDEX idx_transaction_tickets_raised ON transaction_tickets (raised_at DESC);
CREATE INDEX idx_transaction_tickets_source ON transaction_tickets (source_id);

-- ---------------------------------------------------------------------
-- The daily review of stock movements (IMP 12) and the monthly reconciliation (BP 7.4, Annex I step 5)
-- ---------------------------------------------------------------------
CREATE TABLE stock_day_reviews (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version          BIGINT        NOT NULL DEFAULT 0,
    day              DATE          NOT NULL,
    movements        INTEGER       NOT NULL,
    note             VARCHAR(500),
    reviewed_by      VARCHAR(50)   NOT NULL,
    reviewed_by_id   BIGINT,
    reviewed_at      TIMESTAMP     NOT NULL,
    created_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by       VARCHAR(50),
    updated_at       TIMESTAMP,
    updated_by       VARCHAR(50),
    CONSTRAINT uk_stock_day_reviews_day UNIQUE (day),
    CONSTRAINT chk_stock_day_reviews_movements CHECK (movements >= 0)
);

CREATE TABLE stock_reconciliations (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version              BIGINT        NOT NULL DEFAULT 0,
    period_start         DATE          NOT NULL,
    loaded_units         INTEGER       NOT NULL,           -- units loaded on vehicles in the month
    accounted_units      INTEGER       NOT NULL,           -- of them: sold from the vehicle, back, broken, lost or still on board
    dispatched_units     INTEGER       NOT NULL,           -- units sent to another site in the month
    arrived_units        INTEGER       NOT NULL,           -- of them: arrived, or still in transit
    tickets_open         INTEGER       NOT NULL,           -- tickets of the month not posted
    variance_value       NUMERIC(18,2) NOT NULL,           -- write-offs, losses and count differences at cost
    stock_value          NUMERIC(18,2) NOT NULL,
    variance_percent     NUMERIC(8,4)  NOT NULL,
    above_tolerance      BOOLEAN       NOT NULL,
    note                 VARCHAR(500),
    reviewed_by          VARCHAR(50)   NOT NULL,
    reviewed_by_id       BIGINT,
    reviewed_at          TIMESTAMP     NOT NULL,
    created_at           TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by           VARCHAR(50),
    updated_at           TIMESTAMP,
    updated_by           VARCHAR(50),
    CONSTRAINT uk_stock_reconciliations_period UNIQUE (period_start),
    CONSTRAINT chk_stock_reconciliations_period CHECK (EXTRACT(DAY FROM period_start) = 1)
);

-- ---------------------------------------------------------------------
-- Documents on a transfer (the delivery note signed at both ends), notifications
-- ---------------------------------------------------------------------
ALTER TABLE attachments DROP CONSTRAINT chk_attachments_owner;
ALTER TABLE attachments ADD CONSTRAINT chk_attachments_owner CHECK (owner_type IN
    ('GOODS_RECEIPT', 'SHIPMENT', 'SUPPLIER_INVOICE', 'STOCK_ADJUSTMENT', 'VEHICLE', 'DRIVER', 'TRIP', 'VEHICLE_COST',
     'CREDIT_NOTE_REQUEST', 'RECEIVABLE_WRITE_OFF', 'CUSTOMER', 'SUPPLIER', 'CREDIT_APPLICATION', 'SITE_TRANSFER'));

ALTER TABLE notifications DROP CONSTRAINT chk_notifications_kind;
ALTER TABLE notifications ADD CONSTRAINT chk_notifications_kind
    CHECK (kind IN ('LOW_STOCK', 'APPROVAL', 'DECISION', 'SYSTEM', 'EBM', 'FLEET', 'SYNC', 'EOD', 'CREDIT', 'TRANSFER'));

-- ---------------------------------------------------------------------
-- Numbers and settings
-- ---------------------------------------------------------------------
INSERT INTO number_sequences (doc_type, branch_code, prefix, reset_policy, padding, created_by) VALUES
('SITE_TRANSFER',       'WH', 'STR', 'YEARLY', 6, 'system'),
('TRANSACTION_TICKET',  'WH', 'TT',  'YEARLY', 6, 'system');

INSERT INTO settings (setting_key, setting_value, created_by) VALUES
('ticket.enabled',                  'true',  'system'),   -- movements but sales get a Transaction Ticket (D6)
('ticket.gate-receipts',            'true',  'system'),   -- a goods receipt posts only from its countersigned ticket
('transfer.approval',               'true',  'system'),   -- a transfer between sites is approved before it leaves
('transfer.difference-hours',       '24',    'system'),   -- labels not received this long after dispatch are a difference
('reconciliation.tolerance-percent', '0.5',  'system'),   -- stock lost or found above this % of the stock value is flagged
('ticket.since',                    TO_CHAR(CURRENT_DATE, 'YYYY-MM-DD'), 'system');   -- tickets are raised for movements from this day

-- ---------------------------------------------------------------------
-- Security
-- ---------------------------------------------------------------------
INSERT INTO pages (code, name, module, path, icon, sort_order) VALUES
('SITE_TRANSFERS',        'Site Transfers',        'Inventory', '/site-transfers',              'truck',      12),
('TRANSACTION_TICKETS',   'Transaction Tickets',   'Inventory', '/tickets',                     'clipboard',  13),
('STOCK_RECONCILIATION',  'Stock Reconciliation',  'Reports',   '/reports/stock-reconciliation', 'list',      42);

INSERT INTO permissions (code, name, module, action, description) VALUES
('VIEW_SITE_TRANSFER',        'View Site Transfers',               'Inventory', 'VIEW',     'See transfers between sites and what is in transit'),
('REQUEST_SITE_TRANSFER',     'Request Site Transfers',            'Inventory', 'CREATE',   'Ask for glass to be sent from another site'),
('APPROVE_SITE_TRANSFER',     'Approve Site Transfers',            'Inventory', 'APPROVE',  'Approve or reject a transfer between sites asked by someone else'),
('DISPATCH_SITE_TRANSFER',    'Dispatch Site Transfers',           'Inventory', 'DISPATCH', 'Load an approved transfer: scan the labels, give the vehicle and driver'),
('RECEIVE_SITE_TRANSFER',     'Receive Site Transfers',            'Inventory', 'RECEIVE',  'Scan each label of a transfer on arrival and put it on a rack'),
('VIEW_TICKET',               'View Transaction Tickets',          'Inventory', 'VIEW',     'See the Transaction Tickets of stock movements'),
('VERIFY_TICKET',             'Verify Transaction Tickets',        'Inventory', 'VERIFY',   'Verify a ticket against the glass that moved (Supply Chain)'),
('COUNTERSIGN_TICKET',        'Countersign Transaction Tickets',   'Inventory', 'APPROVE',  'Countersign a ticket: the movement answers to an order, a request or a return (Commercial)'),
('POST_TICKET',               'Post Transaction Tickets',          'Inventory', 'POST',     'Post a countersigned ticket (Finance): a goods receipt waiting for it posts'),
('REVIEW_DAILY_STOCK',        'Review the Day''s Stock Movements', 'Inventory', 'REVIEW',   'Record the daily review of stock movements'),
('VIEW_STOCK_RECONCILIATION', 'View the Stock Reconciliation',     'Reports',   'VIEW',     'See the monthly reconciliation of stock movements, sales and counts'),
('REVIEW_STOCK_RECONCILIATION', 'Review the Stock Reconciliation', 'Reports',   'REVIEW',   'Sign the monthly stock reconciliation off'),
('ALERT_SITE_TRANSFER',       'Site Transfer Alerts',              'Inventory', 'ALERT',    'Be told of transfers waiting and of glass that did not arrive');

INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE (p.code IN ('SITE_TRANSFERS', 'TRANSACTION_TICKETS') AND r.code IN ('ADMIN', 'OWNER', 'WAREHOUSE_SUPERVISOR', 'ACCOUNTANT', 'AUDITOR',
        'COO', 'DIRECTOR_FINANCE', 'DIRECTOR_COMMERCIAL', 'DIRECTOR_SUPPLY_CHAIN', 'WAREHOUSE_MANAGER', 'INVENTORY_TRANSACTIONS',
        'DISPATCH_OFFICER', 'INTERNAL_CONTROLLER', 'BOOKKEEPING_CASHIER'))
   OR (p.code = 'STOCK_RECONCILIATION' AND r.code IN ('ADMIN', 'OWNER', 'ACCOUNTANT', 'AUDITOR', 'COO', 'DIRECTOR_FINANCE',
        'DIRECTOR_SUPPLY_CHAIN', 'REPORTING_TAX_BUDGET', 'INTERNAL_CONTROLLER'))
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE (r.code = 'ADMIN' AND p.code IN ('VIEW_SITE_TRANSFER', 'REQUEST_SITE_TRANSFER', 'APPROVE_SITE_TRANSFER', 'DISPATCH_SITE_TRANSFER',
        'RECEIVE_SITE_TRANSFER', 'VIEW_TICKET', 'VERIFY_TICKET', 'COUNTERSIGN_TICKET', 'POST_TICKET', 'REVIEW_DAILY_STOCK',
        'VIEW_STOCK_RECONCILIATION', 'REVIEW_STOCK_RECONCILIATION', 'ALERT_SITE_TRANSFER'))
   OR (r.code = 'OWNER' AND p.code IN ('VIEW_SITE_TRANSFER', 'APPROVE_SITE_TRANSFER', 'VIEW_TICKET', 'VIEW_STOCK_RECONCILIATION',
        'REVIEW_STOCK_RECONCILIATION'))
   OR (r.code = 'WAREHOUSE_SUPERVISOR' AND p.code IN ('VIEW_SITE_TRANSFER', 'REQUEST_SITE_TRANSFER', 'DISPATCH_SITE_TRANSFER',
        'RECEIVE_SITE_TRANSFER', 'VIEW_TICKET', 'REVIEW_DAILY_STOCK'))
   OR (r.code = 'ACCOUNTANT' AND p.code IN ('VIEW_SITE_TRANSFER', 'VIEW_TICKET', 'POST_TICKET', 'VIEW_STOCK_RECONCILIATION'))
   OR (r.code IN ('AUDITOR', 'INTERNAL_CONTROLLER') AND p.code IN ('VIEW_SITE_TRANSFER', 'VIEW_TICKET', 'VIEW_STOCK_RECONCILIATION',
        'ALERT_SITE_TRANSFER'))
   OR (r.code = 'COO' AND p.code IN ('VIEW_SITE_TRANSFER', 'APPROVE_SITE_TRANSFER', 'VIEW_TICKET', 'VIEW_STOCK_RECONCILIATION'))
   OR (r.code = 'DIRECTOR_FINANCE' AND p.code IN ('VIEW_SITE_TRANSFER', 'VIEW_TICKET', 'POST_TICKET', 'VIEW_STOCK_RECONCILIATION',
        'REVIEW_STOCK_RECONCILIATION'))
   OR (r.code = 'BOOKKEEPING_CASHIER' AND p.code IN ('VIEW_TICKET', 'POST_TICKET'))
   OR (r.code = 'DIRECTOR_COMMERCIAL' AND p.code IN ('VIEW_SITE_TRANSFER', 'VIEW_TICKET', 'COUNTERSIGN_TICKET'))
   OR (r.code = 'DIRECTOR_SUPPLY_CHAIN' AND p.code IN ('VIEW_SITE_TRANSFER', 'APPROVE_SITE_TRANSFER', 'VIEW_TICKET', 'VERIFY_TICKET',
        'REVIEW_DAILY_STOCK', 'VIEW_STOCK_RECONCILIATION', 'ALERT_SITE_TRANSFER'))
   OR (r.code = 'WAREHOUSE_MANAGER' AND p.code IN ('VIEW_SITE_TRANSFER', 'REQUEST_SITE_TRANSFER', 'DISPATCH_SITE_TRANSFER',
        'RECEIVE_SITE_TRANSFER', 'VIEW_TICKET', 'VERIFY_TICKET', 'REVIEW_DAILY_STOCK', 'ALERT_SITE_TRANSFER'))
   OR (r.code = 'INVENTORY_TRANSACTIONS' AND p.code IN ('VIEW_SITE_TRANSFER', 'REQUEST_SITE_TRANSFER', 'DISPATCH_SITE_TRANSFER',
        'RECEIVE_SITE_TRANSFER', 'VIEW_TICKET'))
   OR (r.code = 'DISPATCH_OFFICER' AND p.code IN ('VIEW_SITE_TRANSFER', 'DISPATCH_SITE_TRANSFER', 'VIEW_TICKET'))
ON CONFLICT DO NOTHING;
