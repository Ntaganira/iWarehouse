-- =====================================================================
-- V48: the field cycle (M15 of the plan to cover the client's restructuring documents; not in SRS v2.1)
--   - the load request on the trip: route, towns, days out, settlement date, the load's value, approved by the Director
--     of Commercial before the vehicle leaves (BP 7.3 rule 1, F-09, Annex H step 1)
--   - no new load while the driver's or the vehicle's last trip is not cleared, unless the COO waives it with the reason
--     (BP 7.3 rule 9, F-09 C, F-11, SDP 10)
--   - collections banked or sent by MoMo during the trip, from the phone or the office (BP 7.3 rule 4, F-10 C, SDP 10)
--   - the van's stock counted every day by the driver and unannounced by a supervisor (SDP 6, BP 7.3 rule 10, F-08)
--   - the clearance certificate: cleared by the Director of Commercial, countersigned by the Director of Finance (F-11)
--   - field credit to approved customers within their limit (decision D8); costs from the float until a date (decision D9)
-- =====================================================================

-- ---------------------------------------------------------------------
-- The load request, the previous trip, the clearance (N.01, N.04, N.15)
-- ---------------------------------------------------------------------
ALTER TABLE trips ADD COLUMN route VARCHAR(200);
ALTER TABLE trips ADD COLUMN towns VARCHAR(300);
ALTER TABLE trips ADD COLUMN days_out INTEGER;
ALTER TABLE trips ADD COLUMN settle_by DATE;
ALTER TABLE trips ADD COLUMN load_value NUMERIC(18,2);
ALTER TABLE trips ADD COLUMN load_approved_by VARCHAR(50);
ALTER TABLE trips ADD COLUMN load_approved_by_id BIGINT;
ALTER TABLE trips ADD COLUMN load_approved_at TIMESTAMP;
ALTER TABLE trips ADD COLUMN previous_override_reason VARCHAR(255);
ALTER TABLE trips ADD COLUMN previous_override_by VARCHAR(50);
ALTER TABLE trips ADD COLUMN previous_override_at TIMESTAMP;
ALTER TABLE trips ADD COLUMN cleared_by VARCHAR(50);
ALTER TABLE trips ADD COLUMN cleared_by_id BIGINT;
ALTER TABLE trips ADD COLUMN cleared_at TIMESTAMP;
ALTER TABLE trips ADD CONSTRAINT chk_trips_days_out CHECK (days_out IS NULL OR days_out BETWEEN 1 AND 60);
ALTER TABLE trips ADD CONSTRAINT chk_trips_load_approved CHECK (load_approved_at IS NULL OR load_approved_by IS NOT NULL);
ALTER TABLE trips ADD CONSTRAINT chk_trips_previous_override CHECK (previous_override_at IS NULL
    OR (previous_override_by IS NOT NULL AND previous_override_reason IS NOT NULL));
ALTER TABLE trips ADD CONSTRAINT chk_trips_cleared CHECK (cleared_at IS NULL OR (cleared_by IS NOT NULL AND status IN ('RETURNED', 'CLOSED')));

-- ---------------------------------------------------------------------
-- Collections banked during the trip (N.07)
-- ---------------------------------------------------------------------
CREATE TABLE trip_deposits (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    number         VARCHAR(30)   NOT NULL,               -- TDP-WH-2026-000001
    client_id      UUID,                                 -- the phone's id for it: sent twice, taken once
    trip_id        UUID          NOT NULL REFERENCES trips(id),
    driver_id      UUID          NOT NULL REFERENCES drivers(id),
    method         VARCHAR(15)   NOT NULL,
    amount         NUMERIC(18,2) NOT NULL,
    reference      VARCHAR(60)   NOT NULL,
    deposited_on   DATE          NOT NULL,
    recorded_via   VARCHAR(10)   NOT NULL,
    created_at     TIMESTAMP     NOT NULL,
    user_id        BIGINT,
    username       VARCHAR(50)   NOT NULL,
    CONSTRAINT uk_trip_deposits_number UNIQUE (number),
    CONSTRAINT uk_trip_deposits_client UNIQUE (client_id),
    CONSTRAINT chk_trip_deposits_method CHECK (method IN ('BANK_TRANSFER', 'MOBILE_MONEY')),
    CONSTRAINT chk_trip_deposits_amount CHECK (amount > 0),
    CONSTRAINT chk_trip_deposits_via CHECK (recorded_via IN ('PHONE', 'OFFICE'))
);
CREATE INDEX idx_trip_deposits_trip ON trip_deposits (trip_id);
CREATE TRIGGER trg_trip_deposits_append_only BEFORE UPDATE OR DELETE ON trip_deposits
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_modification();

-- ---------------------------------------------------------------------
-- The van's stock counted on the road (N.12, L.02)
-- ---------------------------------------------------------------------
CREATE TABLE trip_counts (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trip_id      UUID          NOT NULL REFERENCES trips(id),
    client_id    UUID,
    kind         VARCHAR(10)   NOT NULL,
    counted_at   TIMESTAMP     NOT NULL,
    expected     INTEGER       NOT NULL,
    found        INTEGER       NOT NULL,
    missing      INTEGER       NOT NULL,
    extra        INTEGER       NOT NULL,
    note         VARCHAR(255),
    user_id      BIGINT,
    username     VARCHAR(50)   NOT NULL,
    CONSTRAINT uk_trip_counts_client UNIQUE (client_id),
    CONSTRAINT chk_trip_counts_kind CHECK (kind IN ('DAILY', 'SPOT'))
);
CREATE INDEX idx_trip_counts_trip ON trip_counts (trip_id, counted_at);
CREATE TRIGGER trg_trip_counts_append_only BEFORE UPDATE OR DELETE ON trip_counts
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_modification();

CREATE TABLE trip_count_lines (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    count_id       UUID          NOT NULL REFERENCES trip_counts(id),
    stock_unit_id  UUID          REFERENCES stock_units(id),
    unit_code      VARCHAR(30)   NOT NULL,
    outcome        VARCHAR(10)   NOT NULL,
    CONSTRAINT chk_trip_count_lines_outcome CHECK (outcome IN ('FOUND', 'MISSING', 'EXTRA'))
);
CREATE INDEX idx_trip_count_lines_count ON trip_count_lines (count_id);
CREATE TRIGGER trg_trip_count_lines_append_only BEFORE UPDATE OR DELETE ON trip_count_lines
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_modification();

-- ---------------------------------------------------------------------
-- Journals, sync conflicts, numbers, settings
-- ---------------------------------------------------------------------
ALTER TABLE journal_entries DROP CONSTRAINT chk_journal_entries_source;
ALTER TABLE journal_entries ADD CONSTRAINT chk_journal_entries_source CHECK (source_type IN
    ('GOODS_RECEIPT', 'SHIPMENT', 'CLAIM_OPENED', 'CLAIM_SETTLED', 'CLAIM_REJECTED', 'CUTTING_JOB', 'ADJUSTMENT',
     'OPENING_STOCK', 'SALES_INVOICE', 'TILL_OPENED', 'TILL_CLOSED', 'SALES_DELIVERY', 'SALES_BALANCE', 'CREDIT_NOTE',
     'CUSTOMER_PAYMENT', 'SUPPLIER_INVOICE', 'SUPPLIER_PAYMENT', 'FX_REVALUATION', 'MANUAL_JOURNAL', 'MOBILE_SALE',
     'TRIP_RETURN', 'AUDIT_CASE', 'FLOAT_CLEARANCE', 'PAYROLL', 'PAYROLL_PAYMENT', 'VEHICLE_COST', 'RECEIVABLE_WRITE_OFF',
     'CASH_DEPOSIT', 'TRIP_DEPOSIT'));

ALTER TABLE mobile_sync_conflicts DROP CONSTRAINT chk_mobile_sync_conflicts_reason;
ALTER TABLE mobile_sync_conflicts ADD CONSTRAINT chk_mobile_sync_conflicts_reason CHECK (reason IN
    ('NO_TRIP', 'NOT_YOUR_TRIP', 'UNIT_NOT_ON_VEHICLE', 'UNIT_REPORTED_BROKEN', 'NUMBER_NOT_ISSUED', 'NUMBER_USED', 'NO_PRICE',
     'PRICE_BELOW_LIMIT', 'TOTAL_MISMATCH', 'PAYMENT_MISMATCH', 'INVALID', 'BELOW_COST', 'CREDIT_REFUSED'));

INSERT INTO number_sequences (doc_type, branch_code, prefix, reset_policy, padding, created_by) VALUES
('TRIP_DEPOSIT', 'WH', 'TDP', 'YEARLY', 6, 'system');

INSERT INTO settings (setting_key, setting_value, created_by) VALUES
('fleet.load-approval',              'true',       'system'),  -- the load request is approved before the vehicle leaves (F-09)
('fleet.previous-cleared',           'true',       'system'),  -- no new load while the last trip is not cleared (BP 7.3 rule 9)
('fleet.clearance-countersign',      'true',       'system'),  -- the clearance is countersigned by the Director of Finance (F-11)
('fleet.costs-from-float-until',     '2026-12-31', 'system'),  -- vehicle costs paid from a float up to this day (decision D9)
('fleet.shortage-tolerance-percent', '0.5',        'system'),  -- van stock shortages kept within this % of the value loaded
('mobile.field-credit',              'true',       'system');  -- the phone sells on credit to approved customers within their limit (D8)

-- ---------------------------------------------------------------------
-- Security
-- ---------------------------------------------------------------------
INSERT INTO permissions (code, name, module, action, description) VALUES
('APPROVE_LOAD_REQUEST',       'Approve Load Requests',        'Fleet', 'APPROVE', 'Approve a trip''s load request (route, towns, days, value, settlement date) before it leaves'),
('WAIVE_PREVIOUS_TRIP',        'Waive the Previous Trip',      'Fleet', 'OVERRIDE', 'Let a vehicle leave while its driver''s or its own last trip is not cleared, with the reason (COO)'),
('RECORD_TRIP_DEPOSIT',        'Record Trip Deposits',         'Fleet', 'CREATE',  'Record collections a driver banked or sent by mobile money during a trip'),
('COUNT_VAN',                  'Count Van Stock',              'Fleet', 'COUNT',   'Count the glass on a vehicle on the road, unannounced'),
('CLEAR_TRIP',                 'Clear Trips',                  'Fleet', 'APPROVE', 'Clear a returned trip: its stock, cash and float settled (Director of Commercial)'),
('COUNTERSIGN_TRIP_CLEARANCE', 'Countersign Trip Clearances',  'Fleet', 'APPROVE', 'Countersign a trip''s clearance, which closes it (Director of Finance)');

INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE p.code = 'FLEET' AND r.code IN ('DIRECTOR_COMMERCIAL', 'DIRECTOR_FINANCE', 'COO', 'INTERNAL_CONTROLLER', 'SECURITY_RISK', 'TREASURY')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE (r.code = 'ADMIN' AND p.code IN ('APPROVE_LOAD_REQUEST', 'WAIVE_PREVIOUS_TRIP', 'RECORD_TRIP_DEPOSIT', 'COUNT_VAN', 'CLEAR_TRIP',
        'COUNTERSIGN_TRIP_CLEARANCE'))
   OR (r.code = 'DIRECTOR_COMMERCIAL' AND p.code IN ('APPROVE_LOAD_REQUEST', 'CLEAR_TRIP', 'VIEW_TRIP'))
   OR (r.code = 'DIRECTOR_FINANCE' AND p.code IN ('COUNTERSIGN_TRIP_CLEARANCE', 'RECORD_TRIP_DEPOSIT', 'VIEW_TRIP'))
   OR (r.code = 'COO' AND p.code IN ('WAIVE_PREVIOUS_TRIP', 'APPROVE_LOAD_REQUEST', 'VIEW_TRIP'))
   OR (r.code = 'OWNER' AND p.code IN ('WAIVE_PREVIOUS_TRIP', 'APPROVE_LOAD_REQUEST', 'CLEAR_TRIP', 'COUNTERSIGN_TRIP_CLEARANCE'))
   OR (r.code IN ('ACCOUNTANT', 'TREASURY') AND p.code IN ('RECORD_TRIP_DEPOSIT', 'VIEW_TRIP'))
   OR (r.code IN ('INTERNAL_CONTROLLER', 'SECURITY_RISK', 'WAREHOUSE_SUPERVISOR', 'FLEET_MANAGER') AND p.code IN ('COUNT_VAN', 'VIEW_TRIP'))
ON CONFLICT DO NOTHING;
