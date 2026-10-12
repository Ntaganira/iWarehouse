-- =====================================================================
-- V41: vehicle costs (owner, 2026-10-12; not in SRS v2.1). What running
-- each vehicle costs: fuel, services and oil changes, repairs, spare
-- parts (bought when needed, never stocked), tyres, washing, parking and
-- tolls. Each cost posts its journal: paid from the vault, the bank or
-- mobile money, owed to the garage (its supplier account), or paid by the
-- driver from a trip's cash (their float). Above a Settings limit another
-- person approves it first. A posted cost is reversed, never edited.
-- Service plans per vehicle (every so many km or months) raise reminders
-- before they fall due. A cost replaces the trip's fuel entries, which
-- were never posted: trip_fuel is no longer used and keeps its history.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Service plans: an oil change every 5,000 km or 3 months, and so on
-- ---------------------------------------------------------------------
CREATE TABLE vehicle_service_plans (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version         BIGINT        NOT NULL DEFAULT 0,
    vehicle_id      UUID          NOT NULL REFERENCES vehicles(id),
    name            VARCHAR(60)   NOT NULL,                 -- Oil change, Full service, Tyres
    every_km        INTEGER,                                -- due this many km after it was last done
    every_months    INTEGER,                                -- or this many months, whichever comes first
    last_done_on    DATE          NOT NULL,                 -- moved by a posted cost that does it
    last_done_km    INTEGER,
    enabled         BOOLEAN       NOT NULL DEFAULT TRUE,
    note            VARCHAR(255),
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by      VARCHAR(50),
    updated_at      TIMESTAMP,
    updated_by      VARCHAR(50),
    CONSTRAINT chk_vehicle_service_plans_every CHECK (every_km IS NOT NULL OR every_months IS NOT NULL),
    CONSTRAINT chk_vehicle_service_plans_km CHECK (every_km IS NULL OR every_km BETWEEN 100 AND 200000),
    CONSTRAINT chk_vehicle_service_plans_months CHECK (every_months IS NULL OR every_months BETWEEN 1 AND 60),
    CONSTRAINT chk_vehicle_service_plans_done_km CHECK (last_done_km IS NULL OR last_done_km >= 0)
);
CREATE UNIQUE INDEX uk_vehicle_service_plans_name ON vehicle_service_plans (vehicle_id, lower(name));

-- ---------------------------------------------------------------------
-- Vehicle costs: VC-WH-2026-000001
-- ---------------------------------------------------------------------
CREATE TABLE vehicle_costs (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version               BIGINT         NOT NULL DEFAULT 0,
    number                VARCHAR(30)    NOT NULL,
    vehicle_id            UUID           NOT NULL REFERENCES vehicles(id),
    trip_id               UUID           REFERENCES trips(id),        -- the trip it was for (needed when the driver paid)
    cost_date             DATE           NOT NULL,
    category              VARCHAR(10)    NOT NULL,                    -- FUEL, SERVICE, REPAIR, PARTS, TYRES, WASHING, PARKING, OTHER
    description           VARCHAR(255)   NOT NULL,
    odometer_km           INTEGER,
    litres                NUMERIC(8,2),                               -- fuel only
    amount                NUMERIC(18,2)  NOT NULL,                    -- RWF, VAT included
    vat_amount            NUMERIC(18,2)  NOT NULL DEFAULT 0,          -- on a VAT-registered garage's EBM receipt: claimed back
    payment               VARCHAR(15)    NOT NULL,                    -- CASH_VAULT, BANK, MOBILE_MONEY, CREDIT, DRIVER_FLOAT
    payment_ref           VARCHAR(60),
    supplier_id           UUID           REFERENCES suppliers(id),    -- the garage or station; owed to it on CREDIT
    supplier_ref          VARCHAR(60),                                -- its invoice or receipt number
    driver_id             UUID           REFERENCES drivers(id),      -- whose float paid it (DRIVER_FLOAT)
    service_plan_id       UUID           REFERENCES vehicle_service_plans(id),
    status                VARCHAR(16)    NOT NULL,                    -- PENDING_APPROVAL, POSTED, REJECTED, CANCELLED, REVERSED
    requested_by_id       BIGINT,
    requested_by          VARCHAR(50)    NOT NULL,
    requested_at          TIMESTAMP      NOT NULL,
    decided_by            VARCHAR(50),
    decided_at            TIMESTAMP,
    decision_note         VARCHAR(255),
    journal_id            UUID           REFERENCES journal_entries(id),
    posted_at             TIMESTAMP,
    reversal_journal_id   UUID           REFERENCES journal_entries(id),
    reversal_date         DATE,
    reversal_reason       VARCHAR(255),
    reversed_by           VARCHAR(50),
    reversed_at           TIMESTAMP,
    created_at            TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by            VARCHAR(50),
    updated_at            TIMESTAMP,
    updated_by            VARCHAR(50),
    CONSTRAINT uk_vehicle_costs_number UNIQUE (number),
    CONSTRAINT chk_vehicle_costs_category CHECK (category IN ('FUEL', 'SERVICE', 'REPAIR', 'PARTS', 'TYRES', 'WASHING', 'PARKING', 'OTHER')),
    CONSTRAINT chk_vehicle_costs_payment CHECK (payment IN ('CASH_VAULT', 'BANK', 'MOBILE_MONEY', 'CREDIT', 'DRIVER_FLOAT')),
    CONSTRAINT chk_vehicle_costs_status CHECK (status IN ('PENDING_APPROVAL', 'POSTED', 'REJECTED', 'CANCELLED', 'REVERSED')),
    CONSTRAINT chk_vehicle_costs_amounts CHECK (amount > 0 AND vat_amount >= 0 AND vat_amount < amount),
    CONSTRAINT chk_vehicle_costs_litres CHECK (litres IS NULL OR (category = 'FUEL' AND litres > 0)),
    CONSTRAINT chk_vehicle_costs_odometer CHECK (odometer_km IS NULL OR odometer_km >= 0),
    CONSTRAINT chk_vehicle_costs_credit CHECK (payment <> 'CREDIT' OR supplier_id IS NOT NULL),
    CONSTRAINT chk_vehicle_costs_float CHECK ((payment = 'DRIVER_FLOAT') = (driver_id IS NOT NULL) AND (payment <> 'DRIVER_FLOAT' OR trip_id IS NOT NULL)),
    CONSTRAINT chk_vehicle_costs_posted CHECK ((status IN ('POSTED', 'REVERSED')) = (journal_id IS NOT NULL AND posted_at IS NOT NULL)),
    CONSTRAINT chk_vehicle_costs_decided CHECK (status NOT IN ('REJECTED', 'CANCELLED') OR (decided_by IS NOT NULL AND decision_note IS NOT NULL)),
    CONSTRAINT chk_vehicle_costs_reversed CHECK ((status = 'REVERSED') = (reversal_journal_id IS NOT NULL AND reversal_date IS NOT NULL
        AND reversal_reason IS NOT NULL AND reversed_by IS NOT NULL))
);
CREATE INDEX idx_vehicle_costs_vehicle ON vehicle_costs (vehicle_id, cost_date);
CREATE INDEX idx_vehicle_costs_trip ON vehicle_costs (trip_id);
CREATE INDEX idx_vehicle_costs_status ON vehicle_costs (status, cost_date);
CREATE INDEX idx_vehicle_costs_supplier ON vehicle_costs (supplier_id);

INSERT INTO number_sequences (doc_type, branch_code, prefix, reset_policy, padding, created_by) VALUES
('VEHICLE_COST', 'WH', 'VC', 'YEARLY', 6, 'system');

-- The receipt of a cost: a photo or a PDF
ALTER TABLE attachments DROP CONSTRAINT chk_attachments_owner;
ALTER TABLE attachments ADD CONSTRAINT chk_attachments_owner CHECK (owner_type IN
    ('GOODS_RECEIPT', 'SHIPMENT', 'SUPPLIER_INVOICE', 'STOCK_ADJUSTMENT', 'VEHICLE', 'DRIVER', 'TRIP', 'VEHICLE_COST'));

-- ---------------------------------------------------------------------
-- The ledger: what the vehicles cost
-- ---------------------------------------------------------------------
INSERT INTO accounts (code, name, account_type, system_key, description, created_by) VALUES
('6110', 'Vehicle Fuel',                    'EXPENSE', 'VEHICLE_FUEL',        'Fuel for the delivery vehicles', 'system'),
('6120', 'Vehicle Repairs and Maintenance', 'EXPENSE', 'VEHICLE_MAINTENANCE', 'Services and oil changes, repairs, spare parts and tyres', 'system'),
('6130', 'Other Vehicle Costs',             'EXPENSE', 'VEHICLE_OTHER',       'Washing, parking, tolls and other running costs', 'system');

ALTER TABLE journal_entries DROP CONSTRAINT chk_journal_entries_source;
ALTER TABLE journal_entries ADD CONSTRAINT chk_journal_entries_source CHECK (source_type IN
    ('GOODS_RECEIPT', 'SHIPMENT', 'CLAIM_OPENED', 'CLAIM_SETTLED', 'CLAIM_REJECTED', 'CUTTING_JOB', 'ADJUSTMENT',
     'OPENING_STOCK', 'SALES_INVOICE', 'TILL_OPENED', 'TILL_CLOSED', 'SALES_DELIVERY', 'SALES_BALANCE', 'CREDIT_NOTE',
     'CUSTOMER_PAYMENT', 'SUPPLIER_INVOICE', 'SUPPLIER_PAYMENT', 'FX_REVALUATION', 'MANUAL_JOURNAL', 'MOBILE_SALE',
     'TRIP_RETURN', 'AUDIT_CASE', 'FLOAT_CLEARANCE', 'PAYROLL', 'PAYROLL_PAYMENT', 'VEHICLE_COST'));

-- ---------------------------------------------------------------------
-- Settings: the approval limit, and how many km before a service is due
-- the fleet manager hears about it (the days come from fleet.expiry-alert-days)
-- ---------------------------------------------------------------------
INSERT INTO settings (setting_key, setting_value, created_by) VALUES
('approval.vehicle-cost-limit', '100000', 'system'),
('fleet.service-alert-km', '500', 'system');

-- ---------------------------------------------------------------------
-- Security: the fleet manager and the accountant record costs, the owner
-- approves, the accountant reverses, the auditor reads
-- ---------------------------------------------------------------------
INSERT INTO pages (code, name, module, path, icon, sort_order) VALUES
('VEHICLE_COSTS', 'Vehicle Costs', 'Fleet', '/vehicle-costs', 'tool', 8);

INSERT INTO permissions (code, name, module, action, description) VALUES
('VIEW_VEHICLE_COST',     'View Vehicle Costs',      'Fleet', 'VIEW',    'See what the vehicles cost, their mileage and service plans'),
('RECORD_VEHICLE_COST',   'Record Vehicle Costs',    'Fleet', 'CREATE',  'Record fuel, services, repairs, parts and other costs of a vehicle'),
('APPROVE_VEHICLE_COST',  'Approve Vehicle Costs',   'Fleet', 'APPROVE', 'Approve or reject a vehicle cost above the approval limit, recorded by someone else'),
('REVERSE_VEHICLE_COST',  'Reverse Vehicle Costs',   'Fleet', 'REVERSE', 'Reverse a posted vehicle cost, with a reason'),
('MANAGE_SERVICE_PLANS',  'Manage Service Plans',    'Fleet', 'MANAGE',  'Add and change the service plans of the vehicles');

INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE p.code = 'VEHICLE_COSTS' AND r.code IN ('ADMIN', 'FLEET_MANAGER', 'ACCOUNTANT', 'OWNER', 'AUDITOR')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE (r.code = 'ADMIN' AND p.code IN ('VIEW_VEHICLE_COST', 'RECORD_VEHICLE_COST', 'APPROVE_VEHICLE_COST', 'REVERSE_VEHICLE_COST', 'MANAGE_SERVICE_PLANS'))
   OR (r.code = 'FLEET_MANAGER' AND p.code IN ('VIEW_VEHICLE_COST', 'RECORD_VEHICLE_COST', 'MANAGE_SERVICE_PLANS'))
   OR (r.code = 'ACCOUNTANT' AND p.code IN ('VIEW_VEHICLE_COST', 'RECORD_VEHICLE_COST', 'REVERSE_VEHICLE_COST'))
   OR (r.code = 'OWNER' AND p.code IN ('VIEW_VEHICLE_COST', 'APPROVE_VEHICLE_COST'))
   OR (r.code = 'AUDITOR' AND p.code = 'VIEW_VEHICLE_COST')
ON CONFLICT DO NOTHING;
