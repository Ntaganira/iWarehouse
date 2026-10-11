-- =====================================================================
-- V37: End of day for the moving shops (M9: FLT-08..FLT-11, MPOS-06,
-- MPOS-07, ACC-06, ACC-07, RPT-04, AT-06, AT-07).
-- A trip on the road comes back: the driver reports breakage on the way
-- (photo, reason, place) and declares their cash; the warehouse scans the
-- units back and reconciles: expected on board = loaded - sold - breakage
-- accepted by the supervisor. Any unit missing or unexpected opens an audit
-- case (the owner is told); the accountant clears the driver's float
-- (shortage to Driver Shortage Receivable, overage to Cash Over/Short).
-- The trip closes once stock and cash are both settled (SRS 5.1).
-- =====================================================================

-- ---------------------------------------------------------------------
-- Trips come back (RETURNED) and close (CLOSED)
-- ---------------------------------------------------------------------
ALTER TABLE trips
    ADD COLUMN returned_at        TIMESTAMP,
    ADD COLUMN returned_by        VARCHAR(50),
    ADD COLUMN returned_pieces    INTEGER,                   -- scanned back onto a rack
    ADD COLUMN sold_pieces        INTEGER,
    ADD COLUMN broken_pieces      INTEGER,
    ADD COLUMN missing_pieces     INTEGER,
    ADD COLUMN cash_declared      NUMERIC(18,2),             -- what the driver says they hold (MPOS-07)
    ADD COLUMN cash_declared_at   TIMESTAMP,
    ADD COLUMN cash_declared_by   VARCHAR(50),
    ADD COLUMN cash_declared_via  VARCHAR(10),               -- PHONE or OFFICE
    ADD COLUMN closed_at          TIMESTAMP,
    ADD COLUMN closed_by          VARCHAR(50);

ALTER TABLE trips DROP CONSTRAINT chk_trips_status;
ALTER TABLE trips ADD CONSTRAINT chk_trips_status CHECK (status IN ('PLANNED', 'DEPARTED', 'RETURNED', 'CLOSED', 'CANCELLED'));
ALTER TABLE trips ADD CONSTRAINT chk_trips_returned CHECK ((status IN ('RETURNED', 'CLOSED')) = (returned_at IS NOT NULL
    AND returned_pieces IS NOT NULL AND sold_pieces IS NOT NULL AND broken_pieces IS NOT NULL AND missing_pieces IS NOT NULL));
ALTER TABLE trips ADD CONSTRAINT chk_trips_closed CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL));
ALTER TABLE trips ADD CONSTRAINT chk_trips_declared CHECK ((cash_declared IS NULL) = (cash_declared_at IS NULL)
    AND (cash_declared IS NULL OR (cash_declared >= 0 AND cash_declared_via IN ('PHONE', 'OFFICE'))));

-- Each manifest line: scanned back, then what became of the unit at the reconciliation (FLT-09)
ALTER TABLE trip_lines
    ADD COLUMN returned_at             TIMESTAMP,            -- scanned back off the vehicle
    ADD COLUMN returned_by             VARCHAR(50),
    ADD COLUMN outcome                 VARCHAR(12),          -- SOLD, RETURNED, BROKEN, MISSING
    ADD COLUMN returned_to_location_id UUID REFERENCES locations(id);

ALTER TABLE trip_lines ADD CONSTRAINT chk_trip_lines_returned CHECK (returned_by IS NULL OR returned_at IS NOT NULL);
ALTER TABLE trip_lines ADD CONSTRAINT chk_trip_lines_outcome CHECK (outcome IS NULL OR outcome IN ('SOLD', 'RETURNED', 'BROKEN', 'MISSING'));
ALTER TABLE trip_lines ADD CONSTRAINT chk_trip_lines_put_back CHECK ((outcome = 'RETURNED') = (returned_to_location_id IS NOT NULL));

-- Labels scanned at the return that are not a unit expected back (FLT-10: an unexpected unit)
CREATE TABLE trip_return_extras (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version       BIGINT       NOT NULL DEFAULT 0,
    trip_id       UUID         NOT NULL REFERENCES trips(id),
    code          VARCHAR(30)  NOT NULL,
    stock_unit_id UUID         REFERENCES stock_units(id),   -- when the label is a unit's
    scanned_at    TIMESTAMP    NOT NULL,
    scanned_by    VARCHAR(50),
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by    VARCHAR(50),
    updated_at    TIMESTAMP,
    updated_by    VARCHAR(50),
    CONSTRAINT uk_trip_return_extras UNIQUE (trip_id, code)
);

CREATE INDEX idx_trip_return_extras_trip ON trip_return_extras (trip_id);

-- ---------------------------------------------------------------------
-- Breakage on the road (FLT-08, MPOS-06): reported with a photo, reason and
-- place, accepted or rejected by the supervisor at the end of the day
-- ---------------------------------------------------------------------
CREATE TABLE trip_breakages (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version        BIGINT       NOT NULL DEFAULT 0,
    trip_id        UUID         NOT NULL REFERENCES trips(id),
    stock_unit_id  UUID         NOT NULL REFERENCES stock_units(id),
    unit_code      VARCHAR(30)  NOT NULL,
    client_id      UUID,                                     -- the phone's own id for the report (idempotent sync)
    device_id      UUID         REFERENCES api_devices(id),
    reason         VARCHAR(255) NOT NULL,
    place          VARCHAR(255) NOT NULL,
    happened_at    TIMESTAMP    NOT NULL,                    -- when it broke, as the phone or the office said
    reported_by    VARCHAR(50)  NOT NULL,
    photo_id       UUID         REFERENCES attachments(id),
    status         VARCHAR(10)  NOT NULL DEFAULT 'REPORTED',
    decided_at     TIMESTAMP,
    decided_by     VARCHAR(50),
    decision_note  VARCHAR(255),
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by     VARCHAR(50),
    updated_at     TIMESTAMP,
    updated_by     VARCHAR(50),
    CONSTRAINT uk_trip_breakages_client UNIQUE (client_id),
    CONSTRAINT chk_trip_breakages_status CHECK (status IN ('REPORTED', 'ACCEPTED', 'REJECTED')),
    CONSTRAINT chk_trip_breakages_decided CHECK ((status = 'REPORTED') = (decided_at IS NULL)),
    CONSTRAINT chk_trip_breakages_rejected CHECK (status <> 'REJECTED' OR decision_note IS NOT NULL)
);

-- A unit is reported broken once per trip (a rejected report can be made again)
CREATE UNIQUE INDEX uk_trip_breakages_unit ON trip_breakages (trip_id, stock_unit_id) WHERE status <> 'REJECTED';
CREATE INDEX idx_trip_breakages_trip ON trip_breakages (trip_id);

-- ---------------------------------------------------------------------
-- Audit cases (FLT-10, FLT-11): units missing or unexpected at the return
-- ---------------------------------------------------------------------
CREATE TABLE audit_cases (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version          BIGINT        NOT NULL DEFAULT 0,
    number           VARCHAR(30)   NOT NULL,
    trip_id          UUID          NOT NULL REFERENCES trips(id),
    status           VARCHAR(10)   NOT NULL DEFAULT 'OPEN',
    opened_at        TIMESTAMP     NOT NULL,
    missing_count    INTEGER       NOT NULL DEFAULT 0,
    unexpected_count INTEGER       NOT NULL DEFAULT 0,
    missing_value    NUMERIC(18,2) NOT NULL DEFAULT 0,       -- the missing units' stock value when the case opened
    escalated_at     TIMESTAMP,
    escalated_by     VARCHAR(50),
    escalation_note  VARCHAR(255),
    resolved_at      TIMESTAMP,
    created_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by       VARCHAR(50),
    updated_at       TIMESTAMP,
    updated_by       VARCHAR(50),
    CONSTRAINT uk_audit_cases_number UNIQUE (number),
    CONSTRAINT uk_audit_cases_trip UNIQUE (trip_id),
    CONSTRAINT chk_audit_cases_status CHECK (status IN ('OPEN', 'ESCALATED', 'RESOLVED')),
    CONSTRAINT chk_audit_cases_escalated CHECK ((escalated_at IS NULL) = (escalation_note IS NULL)
        AND (status <> 'ESCALATED' OR escalated_at IS NOT NULL)),
    CONSTRAINT chk_audit_cases_resolved CHECK ((status = 'RESOLVED') = (resolved_at IS NOT NULL))
);

CREATE INDEX idx_audit_cases_status ON audit_cases (status);

CREATE TABLE audit_case_lines (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version       BIGINT        NOT NULL DEFAULT 0,
    case_id       UUID          NOT NULL REFERENCES audit_cases(id),
    kind          VARCHAR(10)   NOT NULL,                    -- MISSING or UNEXPECTED
    stock_unit_id UUID          REFERENCES stock_units(id),  -- none for a label that is no unit's
    unit_code     VARCHAR(30)   NOT NULL,
    unit_value    NUMERIC(18,2) NOT NULL DEFAULT 0,          -- m² x MAC when the case opened (missing units)
    outcome       VARCHAR(12),
    outcome_at    TIMESTAMP,
    outcome_by    VARCHAR(50),
    note          VARCHAR(255),
    created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by    VARCHAR(50),
    updated_at    TIMESTAMP,
    updated_by    VARCHAR(50),
    CONSTRAINT uk_audit_case_lines UNIQUE (case_id, unit_code),
    CONSTRAINT chk_audit_case_lines_kind CHECK (kind IN ('MISSING', 'UNEXPECTED')),
    CONSTRAINT chk_audit_case_lines_outcome CHECK (outcome IS NULL
        OR (kind = 'MISSING' AND outcome IN ('FOUND', 'SOLD', 'WRITTEN_OFF', 'CHARGED'))
        OR (kind = 'UNEXPECTED' AND outcome = 'EXPLAINED')),
    CONSTRAINT chk_audit_case_lines_decided CHECK ((outcome IS NULL) = (outcome_at IS NULL))
);

CREATE INDEX idx_audit_case_lines_case ON audit_case_lines (case_id);
CREATE INDEX idx_audit_case_lines_unit ON audit_case_lines (stock_unit_id);

-- ---------------------------------------------------------------------
-- Float clearance (ACC-06, ACC-07): the accountant counts the trip's cash
-- ---------------------------------------------------------------------
CREATE TABLE float_clearances (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version         BIGINT        NOT NULL DEFAULT 0,
    number          VARCHAR(30)   NOT NULL,
    trip_id         UUID          NOT NULL REFERENCES trips(id),
    driver_id       UUID          NOT NULL REFERENCES drivers(id),
    expected        NUMERIC(18,2) NOT NULL,                  -- the trip's cash sales
    declared        NUMERIC(18,2) NOT NULL,                  -- what the driver declared
    counted         NUMERIC(18,2) NOT NULL,
    shortage        NUMERIC(18,2) NOT NULL,
    overage         NUMERIC(18,2) NOT NULL,
    deposit_account VARCHAR(12)   NOT NULL,                  -- CASH_VAULT or BANK
    reference       VARCHAR(60),
    note            VARCHAR(255),
    cleared_at      TIMESTAMP     NOT NULL,
    cleared_by      VARCHAR(50)   NOT NULL,
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by      VARCHAR(50),
    updated_at      TIMESTAMP,
    updated_by      VARCHAR(50),
    CONSTRAINT uk_float_clearances_number UNIQUE (number),
    CONSTRAINT uk_float_clearances_trip UNIQUE (trip_id),
    CONSTRAINT chk_float_clearances_amounts CHECK (expected >= 0 AND declared >= 0 AND counted >= 0
        AND shortage = GREATEST(expected - counted, 0) AND overage = GREATEST(counted - expected, 0)),
    CONSTRAINT chk_float_clearances_account CHECK (deposit_account IN ('CASH_VAULT', 'BANK'))
);

CREATE INDEX idx_float_clearances_driver ON float_clearances (driver_id);

-- A clearance is posted when it is saved: never changed
CREATE TRIGGER trg_float_clearances_append_only
    BEFORE UPDATE OR DELETE ON float_clearances
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_modification();

INSERT INTO number_sequences (doc_type, branch_code, prefix, reset_policy, padding, created_by) VALUES
('AUDIT_CASE',      'WH', 'AC', 'YEARLY', 6, 'system'),
('FLOAT_CLEARANCE', 'WH', 'FC', 'YEARLY', 6, 'system');

-- ---------------------------------------------------------------------
-- Stock, journals, notifications, documents, conflicts
-- ---------------------------------------------------------------------
-- Units come off a vehicle (UNLOAD), break on the road (BREAKAGE), or are lost from it (LOSS)
ALTER TABLE stock_movements DROP CONSTRAINT chk_stock_movements_type;
ALTER TABLE stock_movements ADD CONSTRAINT chk_stock_movements_type CHECK (movement_type IN
    ('RECEIPT', 'CUTTING_START', 'CUTTING_RELEASE', 'CUTTING_CONSUMED', 'CUTTING_OUTPUT',
     'TRANSFER', 'ADJUSTMENT', 'RESERVE', 'RELEASE', 'COUNT', 'SALE', 'RETURN', 'LOAD',
     'UNLOAD', 'BREAKAGE', 'LOSS'));

ALTER TABLE journal_entries DROP CONSTRAINT chk_journal_entries_source;
ALTER TABLE journal_entries ADD CONSTRAINT chk_journal_entries_source CHECK (source_type IN
    ('GOODS_RECEIPT', 'SHIPMENT', 'CLAIM_OPENED', 'CLAIM_SETTLED', 'CLAIM_REJECTED', 'CUTTING_JOB', 'ADJUSTMENT',
     'OPENING_STOCK', 'SALES_INVOICE', 'TILL_OPENED', 'TILL_CLOSED', 'SALES_DELIVERY', 'SALES_BALANCE', 'CREDIT_NOTE',
     'CUSTOMER_PAYMENT', 'SUPPLIER_INVOICE', 'SUPPLIER_PAYMENT', 'FX_REVALUATION', 'MANUAL_JOURNAL', 'MOBILE_SALE',
     'TRIP_RETURN', 'AUDIT_CASE', 'FLOAT_CLEARANCE'));

ALTER TABLE notifications DROP CONSTRAINT chk_notifications_kind;
ALTER TABLE notifications ADD CONSTRAINT chk_notifications_kind
    CHECK (kind IN ('LOW_STOCK', 'APPROVAL', 'DECISION', 'SYSTEM', 'EBM', 'FLEET', 'SYNC', 'EOD'));

-- Breakage photos (and any paper of the trip) are the trip's documents
ALTER TABLE attachments DROP CONSTRAINT chk_attachments_owner;
ALTER TABLE attachments ADD CONSTRAINT chk_attachments_owner
    CHECK (owner_type IN ('GOODS_RECEIPT', 'SHIPMENT', 'SUPPLIER_INVOICE', 'STOCK_ADJUSTMENT', 'VEHICLE', 'DRIVER', 'TRIP'));

-- A phone selling a unit it reported broken
ALTER TABLE mobile_sync_conflicts DROP CONSTRAINT chk_mobile_sync_conflicts_reason;
ALTER TABLE mobile_sync_conflicts ADD CONSTRAINT chk_mobile_sync_conflicts_reason CHECK (reason IN
    ('NO_TRIP', 'NOT_YOUR_TRIP', 'UNIT_NOT_ON_VEHICLE', 'UNIT_REPORTED_BROKEN', 'NUMBER_NOT_ISSUED', 'NUMBER_USED', 'NO_PRICE',
     'PRICE_BELOW_LIMIT', 'TOTAL_MISMATCH', 'PAYMENT_MISMATCH', 'INVALID'));

-- A float not cleared this many hours after the trip came back is reported (RPT-06)
INSERT INTO settings (setting_key, setting_value, created_by) VALUES
('fleet.float-alert-hours', '24', 'system');

-- ---------------------------------------------------------------------
-- Security (SRS 2.2)
-- ---------------------------------------------------------------------
INSERT INTO pages (code, name, module, path, icon, sort_order) VALUES
('AUDIT_CASES',   'Audit Cases',   'Fleet',      '/audit-cases',   'alert',     6),
('FLOATS',        'Driver Floats', 'Accounting', '/floats',        'cash',      10),
('FLEET_REPORTS', 'Fleet Reports', 'Reports',    '/reports/fleet', 'bar-chart', 11);

INSERT INTO permissions (code, name, module, action, description) VALUES
('RETURN_TRIP',        'Record Trip Returns',    'Fleet',      'RETURN',   'Scan units back off a vehicle and reconcile the trip at the end of the day'),
('VALIDATE_BREAKAGE',  'Validate Breakage',      'Fleet',      'APPROVE',  'Accept or reject the breakage a driver reported on the road'),
('CLOSE_TRIP',         'Close Trips',            'Fleet',      'CLOSE',    'Close a trip once its stock and cash are settled'),
('VIEW_AUDIT_CASES',   'View Audit Cases',       'Fleet',      'VIEW',     'See the units missing or unexpected at the end of the day'),
('MANAGE_AUDIT_CASE',  'Resolve Audit Cases',    'Fleet',      'RESOLVE',  'Record a missing unit found or written off, explain an unexpected one, escalate a case'),
('CHARGE_DRIVER',      'Charge Drivers',         'Fleet',      'APPROVE',  'Charge a missing unit to the driver''s shortage account (the owner''s approval, FLT-11)'),
('ALERT_EOD',          'End-of-Day Alerts',      'Fleet',      'NOTIFY',   'Told when a trip comes back with units missing or unexpected'),
('VIEW_FLOATS',        'View Driver Floats',     'Accounting', 'VIEW',     'See what each driver holds from their sales and the clearances'),
('CLEAR_FLOAT',        'Clear Driver Floats',    'Accounting', 'CLEAR',    'Count a trip''s cash and clear the driver''s float'),
('ALERT_FLOAT',        'Float Alerts',           'Accounting', 'NOTIFY',   'Told when a driver''s float is not cleared in time'),
('VIEW_FLEET_REPORTS', 'View Fleet Reports',     'Reports',    'VIEW',     'Sales per trip and driver, end-of-day discrepancies, breakage rates, floats outstanding');

-- ADMIN: everything.
INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE r.code = 'ADMIN' AND p.code IN ('AUDIT_CASES', 'FLOATS', 'FLEET_REPORTS')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code = 'ADMIN' AND p.code IN ('RETURN_TRIP', 'VALIDATE_BREAKAGE', 'CLOSE_TRIP', 'VIEW_AUDIT_CASES', 'MANAGE_AUDIT_CASE',
    'CHARGE_DRIVER', 'ALERT_EOD', 'VIEW_FLOATS', 'CLEAR_FLOAT', 'ALERT_FLOAT', 'VIEW_FLEET_REPORTS')
ON CONFLICT DO NOTHING;

-- The supervisor runs the return, validates breakage and resolves the cases; the fleet manager closes trips too;
-- the owner is told, may charge a driver and reads everything; the accountant clears floats; the auditor reads.
INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE (p.code = 'AUDIT_CASES' AND r.code IN ('WAREHOUSE_SUPERVISOR', 'FLEET_MANAGER', 'OWNER', 'ACCOUNTANT', 'AUDITOR'))
   OR (p.code = 'FLOATS' AND r.code IN ('ACCOUNTANT', 'OWNER', 'FLEET_MANAGER', 'AUDITOR'))
   OR (p.code = 'FLEET_REPORTS' AND r.code IN ('OWNER', 'FLEET_MANAGER', 'WAREHOUSE_SUPERVISOR', 'ACCOUNTANT', 'AUDITOR'))
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE (r.code = 'WAREHOUSE_SUPERVISOR' AND p.code IN ('RETURN_TRIP', 'VALIDATE_BREAKAGE', 'CLOSE_TRIP', 'VIEW_AUDIT_CASES',
        'MANAGE_AUDIT_CASE', 'ALERT_EOD', 'VIEW_FLEET_REPORTS'))
   OR (r.code = 'FLEET_MANAGER' AND p.code IN ('RETURN_TRIP', 'CLOSE_TRIP', 'VIEW_AUDIT_CASES', 'VIEW_FLOATS', 'VIEW_FLEET_REPORTS'))
   OR (r.code = 'OWNER' AND p.code IN ('VIEW_AUDIT_CASES', 'MANAGE_AUDIT_CASE', 'CHARGE_DRIVER', 'ALERT_EOD', 'VIEW_FLOATS',
        'ALERT_FLOAT', 'VIEW_FLEET_REPORTS'))
   OR (r.code = 'ACCOUNTANT' AND p.code IN ('VIEW_AUDIT_CASES', 'VIEW_FLOATS', 'CLEAR_FLOAT', 'ALERT_FLOAT', 'VIEW_FLEET_REPORTS'))
   OR (r.code = 'AUDITOR' AND p.code IN ('VIEW_AUDIT_CASES', 'VIEW_FLOATS', 'VIEW_FLEET_REPORTS'))
ON CONFLICT DO NOTHING;
