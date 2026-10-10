-- =====================================================================
-- V36: Mobile POS with offline sync (SRS 4.7, 4.8: MPOS-01..05,
-- SYNC-01..08, ACC-06, AUD-07, NFR-10; AT-04, AT-05)
--
-- Drivers sell from their vehicle on a phone (the PWA at /m/), with or
-- without network. The PWA signs in once per device and gets a token
-- (api_devices: per device, revocable, kept as a hash). At trip start it
-- downloads the trip: the units on board, the customers, the prices
-- frozen for the trip (trip_price_lists, trip_prices: the price list as
-- it stood at the first download, MPOS-04) and a block of invoice numbers
-- of its own (trip_invoice_numbers: MINV, one per unit still on board).
-- Each sale made offline carries a client UUID; syncing is idempotent on
-- it (uk_sales_invoices_client, AT-05). A sale the server cannot accept
-- as it was made (a unit sold or moved, a number not the device's, a
-- price beyond the driver's limit...) is kept for the supervisor
-- (mobile_sync_conflicts), never dropped (SYNC-05). An accepted sale is
-- an invoice of channel MOBILE on its trip, its payments named by the
-- trip instead of a till; cash goes to the driver's float (journal lines
-- now name the driver, ACC-06) and the receipt is queued for EBM
-- (SYNC-06).
-- =====================================================================

-- ---------------------------------------------------------------------
-- Devices and their tokens (NFR-10)
-- ---------------------------------------------------------------------
CREATE TABLE api_devices (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version       BIGINT       NOT NULL DEFAULT 0,
    user_id       BIGINT       NOT NULL REFERENCES users(id),
    username      VARCHAR(50)  NOT NULL,
    device_key    VARCHAR(64)  NOT NULL,                  -- the phone's own id (X-Device-Id)
    name          VARCHAR(60)  NOT NULL,
    token_hash    CHAR(64)     NOT NULL,                  -- SHA-256 of the token, never the token
    user_agent    VARCHAR(255),
    last_seen_at  TIMESTAMP,
    revoked_at    TIMESTAMP,
    revoked_by    VARCHAR(50),
    revoke_reason VARCHAR(255),
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by    VARCHAR(50),
    updated_at    TIMESTAMP,
    updated_by    VARCHAR(50),
    CONSTRAINT uk_api_devices_token UNIQUE (token_hash),
    CONSTRAINT chk_api_devices_key CHECK (device_key ~ '^[A-Za-z0-9-]{8,64}$'),
    CONSTRAINT chk_api_devices_revoked CHECK ((revoked_at IS NULL) = (revoke_reason IS NULL))
);

-- One live token per user and phone
CREATE UNIQUE INDEX uk_api_devices_live ON api_devices (user_id, device_key) WHERE revoked_at IS NULL;
CREATE INDEX idx_api_devices_user ON api_devices (user_id);

-- ---------------------------------------------------------------------
-- Sales from a vehicle: invoices and payments of a trip instead of a till
-- ---------------------------------------------------------------------
ALTER TABLE sales_invoices ALTER COLUMN till_session_id DROP NOT NULL;
ALTER TABLE sales_invoices
    ADD COLUMN channel           VARCHAR(10) NOT NULL DEFAULT 'COUNTER',
    ADD COLUMN trip_id           UUID REFERENCES trips(id),
    ADD COLUMN client_id         UUID,                   -- the sale's UUID made on the phone (SYNC-03)
    ADD COLUMN device_id         UUID REFERENCES api_devices(id),
    ADD COLUMN client_created_at TIMESTAMP,              -- when the phone made it
    ADD COLUMN synced_at         TIMESTAMP;              -- when the server took it
ALTER TABLE sales_invoices ADD CONSTRAINT uk_sales_invoices_client UNIQUE (client_id);
ALTER TABLE sales_invoices ADD CONSTRAINT chk_sales_invoices_channel CHECK (
    (channel = 'COUNTER' AND till_session_id IS NOT NULL AND trip_id IS NULL AND client_id IS NULL)
    OR (channel = 'MOBILE' AND till_session_id IS NULL AND trip_id IS NOT NULL AND client_id IS NOT NULL
        AND device_id IS NOT NULL AND synced_at IS NOT NULL AND status = 'POSTED'));
CREATE INDEX idx_sales_invoices_trip ON sales_invoices (trip_id);

ALTER TABLE sales_payments ALTER COLUMN till_session_id DROP NOT NULL;
ALTER TABLE sales_payments ADD COLUMN trip_id UUID REFERENCES trips(id);
ALTER TABLE sales_payments ADD CONSTRAINT chk_sales_payments_place CHECK ((till_session_id IS NULL) <> (trip_id IS NULL));
CREATE INDEX idx_sales_payments_trip ON sales_payments (trip_id);

-- Driver Float lines name their driver (ACC-06: a float per driver)
ALTER TABLE journal_lines ADD COLUMN driver_id UUID REFERENCES drivers(id);
CREATE INDEX idx_journal_lines_driver ON journal_lines (driver_id);

ALTER TABLE journal_entries DROP CONSTRAINT chk_journal_entries_source;
ALTER TABLE journal_entries ADD CONSTRAINT chk_journal_entries_source CHECK (source_type IN
    ('GOODS_RECEIPT', 'SHIPMENT', 'CLAIM_OPENED', 'CLAIM_SETTLED', 'CLAIM_REJECTED', 'CUTTING_JOB', 'ADJUSTMENT',
     'OPENING_STOCK', 'SALES_INVOICE', 'TILL_OPENED', 'TILL_CLOSED', 'SALES_DELIVERY', 'SALES_BALANCE', 'CREDIT_NOTE',
     'CUSTOMER_PAYMENT', 'SUPPLIER_INVOICE', 'SUPPLIER_PAYMENT', 'FX_REVALUATION', 'MANUAL_JOURNAL', 'MOBILE_SALE'));

-- ---------------------------------------------------------------------
-- What a trip's phones download (SYNC-01)
-- ---------------------------------------------------------------------
-- The price lists as they stood at the trip's first download: the trip sells at these prices all day (MPOS-04)
CREATE TABLE trip_price_lists (
    trip_id            UUID          NOT NULL REFERENCES trips(id),
    price_list_id      UUID          NOT NULL REFERENCES price_lists(id),
    code               VARCHAR(20)   NOT NULL,
    name               VARCHAR(100)  NOT NULL,
    default_list       BOOLEAN       NOT NULL,
    prices_include_vat BOOLEAN       NOT NULL,
    min_chargeable_m2  NUMERIC(10,4) NOT NULL,
    taken_at           TIMESTAMP     NOT NULL,
    CONSTRAINT pk_trip_price_lists PRIMARY KEY (trip_id, price_list_id)
);

CREATE TABLE trip_prices (
    trip_id       UUID          NOT NULL REFERENCES trips(id),
    price_list_id UUID          NOT NULL REFERENCES price_lists(id),
    product_id    UUID          NOT NULL REFERENCES products(id),
    price_per_m2  NUMERIC(18,2) NOT NULL,
    CONSTRAINT pk_trip_prices PRIMARY KEY (trip_id, price_list_id, product_id),
    CONSTRAINT chk_trip_prices_price CHECK (price_per_m2 >= 0)
);

CREATE TRIGGER trg_trip_price_lists_append_only
    BEFORE UPDATE OR DELETE ON trip_price_lists
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_modification();
CREATE TRIGGER trg_trip_prices_append_only
    BEFORE UPDATE OR DELETE ON trip_prices
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_modification();

-- Invoice numbers given to a phone for its offline sales (MINV), each used once
CREATE TABLE trip_invoice_numbers (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version    BIGINT      NOT NULL DEFAULT 0,
    trip_id    UUID        NOT NULL REFERENCES trips(id),
    device_id  UUID        NOT NULL REFERENCES api_devices(id),
    number     VARCHAR(30) NOT NULL,
    issued_at  TIMESTAMP   NOT NULL,
    invoice_id UUID        REFERENCES sales_invoices(id),
    used_at    TIMESTAMP,
    CONSTRAINT uk_trip_invoice_numbers_number UNIQUE (number),
    CONSTRAINT uk_trip_invoice_numbers_invoice UNIQUE (invoice_id),
    CONSTRAINT chk_trip_invoice_numbers_used CHECK ((invoice_id IS NULL) = (used_at IS NULL))
);

CREATE INDEX idx_trip_invoice_numbers_trip ON trip_invoice_numbers (trip_id, device_id);

INSERT INTO number_sequences (doc_type, branch_code, prefix, reset_policy, padding, created_by) VALUES
('MOBILE_INVOICE', 'WH', 'MINV', 'YEARLY', 6, 'system');

-- ---------------------------------------------------------------------
-- Sales the server could not take as they were made (SYNC-05)
-- ---------------------------------------------------------------------
CREATE TABLE mobile_sync_conflicts (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version           BIGINT       NOT NULL DEFAULT 0,
    client_id         UUID         NOT NULL,
    trip_id           UUID         REFERENCES trips(id),
    device_id         UUID         NOT NULL REFERENCES api_devices(id),
    username          VARCHAR(50)  NOT NULL,
    number            VARCHAR(30),                       -- the invoice number the phone printed
    reason            VARCHAR(30)  NOT NULL,
    detail            VARCHAR(500),
    total_amount      NUMERIC(18,2),                     -- what the phone charged
    payload           TEXT         NOT NULL,             -- the sale as the phone sent it
    client_created_at TIMESTAMP,
    received_at       TIMESTAMP    NOT NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'OPEN',
    reviewed_at       TIMESTAMP,
    reviewed_by       VARCHAR(50),
    review_note       VARCHAR(255),
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by        VARCHAR(50),
    updated_at        TIMESTAMP,
    updated_by        VARCHAR(50),
    CONSTRAINT uk_mobile_sync_conflicts_client UNIQUE (client_id),
    CONSTRAINT chk_mobile_sync_conflicts_status CHECK (status IN ('OPEN', 'REVIEWED')),
    CONSTRAINT chk_mobile_sync_conflicts_reviewed CHECK ((status = 'REVIEWED') = (reviewed_at IS NOT NULL AND review_note IS NOT NULL)),
    CONSTRAINT chk_mobile_sync_conflicts_reason CHECK (reason IN
        ('NO_TRIP', 'NOT_YOUR_TRIP', 'UNIT_NOT_ON_VEHICLE', 'NUMBER_NOT_ISSUED', 'NUMBER_USED', 'NO_PRICE', 'PRICE_BELOW_LIMIT',
         'TOTAL_MISMATCH', 'PAYMENT_MISMATCH', 'INVALID'))
);

CREATE INDEX idx_mobile_sync_conflicts_status ON mobile_sync_conflicts (status);
CREATE INDEX idx_mobile_sync_conflicts_trip ON mobile_sync_conflicts (trip_id);

ALTER TABLE notifications DROP CONSTRAINT chk_notifications_kind;
ALTER TABLE notifications ADD CONSTRAINT chk_notifications_kind
    CHECK (kind IN ('LOW_STOCK', 'APPROVAL', 'DECISION', 'SYSTEM', 'EBM', 'FLEET', 'SYNC'));

-- ---------------------------------------------------------------------
-- Security (SRS 2.2)
-- ---------------------------------------------------------------------
INSERT INTO pages (code, name, module, path, icon, sort_order) VALUES
('MOBILE_POS',     'Mobile POS',     'Sales & POS',    '/m/',             'smartphone', 6),
('DEVICES',        'Mobile Devices', 'Administration', '/devices',        'smartphone', 22),
('SYNC_CONFLICTS', 'Sync Conflicts', 'Fleet',          '/sync-conflicts', 'alert',      5);

INSERT INTO permissions (code, name, module, action, description) VALUES
('USE_MOBILE_POS',       'Use the Mobile POS',    'Mobile POS', 'SELL',   'Sign in on a phone and sell from the vehicle of their own trip'),
('VIEW_DEVICES',         'View Mobile Devices',   'Mobile POS', 'VIEW',   'See the phones signed in to the mobile POS'),
('REVOKE_DEVICE',        'Revoke Mobile Devices', 'Mobile POS', 'REVOKE', 'Sign a phone out of the mobile POS for good'),
('VIEW_SYNC_CONFLICTS',  'View Sync Conflicts',   'Mobile POS', 'VIEW',   'See the mobile sales the server could not take as they were made'),
('REVIEW_SYNC_CONFLICT', 'Review Sync Conflicts', 'Mobile POS', 'REVIEW', 'Record what was done about a mobile sale in conflict');

-- ADMIN: everything.
INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE r.code = 'ADMIN' AND p.code IN ('MOBILE_POS', 'DEVICES', 'SYNC_CONFLICTS')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code = 'ADMIN' AND p.code IN ('USE_MOBILE_POS', 'VIEW_DEVICES', 'REVOKE_DEVICE', 'VIEW_SYNC_CONFLICTS', 'REVIEW_SYNC_CONFLICT')
ON CONFLICT DO NOTHING;

-- Drivers sell on their phone; the fleet manager keeps the phones; the supervisor
-- reviews conflicts; the owner and auditor read.
INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE (p.code = 'MOBILE_POS' AND r.code = 'DRIVER')
   OR (p.code = 'DEVICES' AND r.code IN ('FLEET_MANAGER', 'OWNER', 'AUDITOR'))
   OR (p.code = 'SYNC_CONFLICTS' AND r.code IN ('WAREHOUSE_SUPERVISOR', 'FLEET_MANAGER', 'OWNER', 'ACCOUNTANT', 'AUDITOR'))
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE (r.code = 'DRIVER' AND p.code = 'USE_MOBILE_POS')
   OR (r.code = 'FLEET_MANAGER' AND p.code IN ('VIEW_DEVICES', 'REVOKE_DEVICE', 'VIEW_SYNC_CONFLICTS'))
   OR (r.code IN ('OWNER', 'AUDITOR') AND p.code IN ('VIEW_DEVICES', 'VIEW_SYNC_CONFLICTS'))
   OR (r.code = 'WAREHOUSE_SUPERVISOR' AND p.code IN ('VIEW_SYNC_CONFLICTS', 'REVIEW_SYNC_CONFLICT'))
   OR (r.code = 'ACCOUNTANT' AND p.code = 'VIEW_SYNC_CONFLICTS')
ON CONFLICT DO NOTHING;
