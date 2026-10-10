-- =====================================================================
-- V35: Fleet: vehicles, drivers, trips and loading (SRS 4.5: FLT-01..FLT-07, FLT-12; AT-03)
--
-- A vehicle is a moving shop: its own VEHICLE location (no parent) is
-- made with it and follows it (code VEH-<plate>), never edited on the
-- Locations screen (FLT-02). Its limits (kg, pieces) are checked when a
-- trip departs (FLT-06). A driver is a user with a licence (FLT-03); their
-- national ID and licence number are personal data (NFR-12, Law
-- 058/2021): shown in full only with VIEW_DRIVER_DATA, masked in the
-- change log. A trip (TRP number) plans a vehicle, a driver, a date and an
-- area with a loading manifest of units, held while it is planned; each
-- unit is confirmed by scanning it; departure needs every planned unit
-- loaded, the papers valid and the load within the vehicle's limits, then
-- moves the units to the vehicle, ON_VEHICLE (LOAD movements), in the
-- driver's charge (FLT-07). Expired papers refuse a trip; they are told
-- 30 days before (FLT-04, setting fleet.expiry-alert-days). Odometer and
-- fuel are kept per trip (FLT-12). The return scan, end of day and the
-- driver's float come with M9.
-- =====================================================================

CREATE TABLE vehicles (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version            BIGINT       NOT NULL DEFAULT 0,
    plate              VARCHAR(15)  NOT NULL,              -- RAC123A: upper case, no spaces
    model              VARCHAR(60)  NOT NULL,
    rack_configuration VARCHAR(255),                       -- A-frames, side racks...
    max_load_kg        INTEGER      NOT NULL,
    max_pieces         INTEGER      NOT NULL,
    insurance_expiry   DATE         NOT NULL,
    inspection_expiry  DATE         NOT NULL,
    odometer_km        INTEGER,                            -- last reading, from its trips
    location_id        UUID         NOT NULL REFERENCES locations(id),
    enabled            BOOLEAN      NOT NULL DEFAULT TRUE,
    notes              VARCHAR(255),
    created_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by         VARCHAR(50),
    updated_at         TIMESTAMP,
    updated_by         VARCHAR(50),
    CONSTRAINT uk_vehicles_plate UNIQUE (plate),
    CONSTRAINT uk_vehicles_location UNIQUE (location_id),
    CONSTRAINT chk_vehicles_plate CHECK (plate ~ '^[A-Z0-9]{2,15}$'),
    CONSTRAINT chk_vehicles_limits CHECK (max_load_kg > 0 AND max_pieces > 0),
    CONSTRAINT chk_vehicles_odometer CHECK (odometer_km IS NULL OR odometer_km >= 0)
);

CREATE TABLE drivers (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version            BIGINT       NOT NULL DEFAULT 0,
    user_id            BIGINT       NOT NULL REFERENCES users(id),
    username           VARCHAR(50)  NOT NULL,              -- the user's, never changes: the change log's reference
    national_id        VARCHAR(16)  NOT NULL,
    licence_number     VARCHAR(30)  NOT NULL,
    licence_category   VARCHAR(20)  NOT NULL,              -- as printed: B, C, CE...
    licence_expiry     DATE         NOT NULL,
    default_vehicle_id UUID         REFERENCES vehicles(id),
    enabled            BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by         VARCHAR(50),
    updated_at         TIMESTAMP,
    updated_by         VARCHAR(50),
    CONSTRAINT uk_drivers_user UNIQUE (user_id),
    CONSTRAINT uk_drivers_national_id UNIQUE (national_id),
    CONSTRAINT uk_drivers_licence UNIQUE (licence_number),
    CONSTRAINT chk_drivers_national_id CHECK (national_id ~ '^[0-9]{16}$'),
    CONSTRAINT chk_drivers_licence CHECK (licence_number ~ '^[A-Z0-9/-]{3,30}$')
);

CREATE TABLE trips (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version        BIGINT        NOT NULL DEFAULT 0,
    number         VARCHAR(30)   NOT NULL,                 -- TRP-WH-2026-000001
    vehicle_id     UUID          NOT NULL REFERENCES vehicles(id),
    driver_id      UUID          NOT NULL REFERENCES drivers(id),
    trip_date      DATE          NOT NULL,
    area           VARCHAR(120)  NOT NULL,                 -- route or area
    note           VARCHAR(255),
    status         VARCHAR(20)   NOT NULL DEFAULT 'PLANNED',
    departed_at    TIMESTAMP,
    departed_by    VARCHAR(50),
    loaded_pieces  INTEGER,                                -- what left, when it departed
    loaded_kg      NUMERIC(12,2),
    odometer_start INTEGER,
    odometer_end   INTEGER,
    cancelled_at   TIMESTAMP,
    cancelled_by   VARCHAR(50),
    cancel_reason  VARCHAR(255),
    created_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by     VARCHAR(50),
    updated_at     TIMESTAMP,
    updated_by     VARCHAR(50),
    CONSTRAINT uk_trips_number UNIQUE (number),
    CONSTRAINT chk_trips_status CHECK (status IN ('PLANNED', 'DEPARTED', 'CANCELLED')),
    CONSTRAINT chk_trips_planned CHECK (status <> 'PLANNED' OR (departed_at IS NULL AND cancelled_at IS NULL)),
    -- every state after planning but cancelled has left with its load
    CONSTRAINT chk_trips_departed CHECK (status IN ('PLANNED', 'CANCELLED')
        OR (departed_at IS NOT NULL AND loaded_pieces IS NOT NULL AND loaded_kg IS NOT NULL)),
    CONSTRAINT chk_trips_cancelled CHECK (status <> 'CANCELLED' OR (cancelled_at IS NOT NULL AND cancel_reason IS NOT NULL)),
    CONSTRAINT chk_trips_odometer CHECK ((odometer_start IS NULL OR odometer_start >= 0)
        AND (odometer_end IS NULL OR (odometer_start IS NOT NULL AND odometer_end >= odometer_start)))
);

CREATE INDEX idx_trips_status ON trips (status);
CREATE INDEX idx_trips_date ON trips (trip_date);
CREATE INDEX idx_trips_vehicle ON trips (vehicle_id);
CREATE INDEX idx_trips_driver ON trips (driver_id);
-- A vehicle, and a driver, is on one trip at a time
CREATE UNIQUE INDEX uk_trips_vehicle_on_road ON trips (vehicle_id) WHERE status = 'DEPARTED';
CREATE UNIQUE INDEX uk_trips_driver_on_road ON trips (driver_id) WHERE status = 'DEPARTED';

-- The loading manifest: the units planned for a trip, each confirmed by a scan. Lines change while the trip is planned.
CREATE TABLE trip_lines (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version          BIGINT       NOT NULL DEFAULT 0,
    trip_id          UUID         NOT NULL REFERENCES trips(id),
    stock_unit_id    UUID         NOT NULL REFERENCES stock_units(id),
    unit_code        VARCHAR(30)  NOT NULL,
    planned_at       TIMESTAMP    NOT NULL,
    planned_by       VARCHAR(50),
    loaded_at        TIMESTAMP,                            -- scanned onto the vehicle
    loaded_by        VARCHAR(50),
    from_location_id UUID         REFERENCES locations(id), -- the rack it left from, at departure
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by       VARCHAR(50),
    updated_at       TIMESTAMP,
    updated_by       VARCHAR(50),
    CONSTRAINT uk_trip_lines_unit UNIQUE (trip_id, stock_unit_id),
    CONSTRAINT chk_trip_lines_loaded CHECK (loaded_by IS NULL OR loaded_at IS NOT NULL)
);

CREATE INDEX idx_trip_lines_trip ON trip_lines (trip_id);
CREATE INDEX idx_trip_lines_unit ON trip_lines (stock_unit_id);

-- Fuel bought for a trip (FLT-12), kept for the fleet reports; paying for it is booked by the accountant.
CREATE TABLE trip_fuel (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version    BIGINT        NOT NULL DEFAULT 0,
    trip_id    UUID          NOT NULL REFERENCES trips(id),
    filled_on  DATE          NOT NULL,
    litres     NUMERIC(8,2)  NOT NULL,
    amount     NUMERIC(18,2) NOT NULL,                     -- RWF
    station    VARCHAR(80),
    note       VARCHAR(255),                               -- receipt number...
    created_at TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(50),
    updated_at TIMESTAMP,
    updated_by VARCHAR(50),
    CONSTRAINT chk_trip_fuel_litres CHECK (litres > 0),
    CONSTRAINT chk_trip_fuel_amount CHECK (amount >= 0)
);

CREATE INDEX idx_trip_fuel_trip ON trip_fuel (trip_id);

-- Units go on a vehicle when its trip departs (FLT-07)
ALTER TABLE stock_movements DROP CONSTRAINT chk_stock_movements_type;
ALTER TABLE stock_movements ADD CONSTRAINT chk_stock_movements_type CHECK (movement_type IN
    ('RECEIPT', 'CUTTING_START', 'CUTTING_RELEASE', 'CUTTING_CONSUMED', 'CUTTING_OUTPUT',
     'TRANSFER', 'ADJUSTMENT', 'RESERVE', 'RELEASE', 'COUNT', 'SALE', 'RETURN', 'LOAD'));

-- Licence, insurance and inspection about to expire (FLT-04)
ALTER TABLE notifications DROP CONSTRAINT chk_notifications_kind;
ALTER TABLE notifications ADD CONSTRAINT chk_notifications_kind
    CHECK (kind IN ('LOW_STOCK', 'APPROVAL', 'DECISION', 'SYSTEM', 'EBM', 'FLEET'));

-- Insurance and inspection certificates on the vehicle, a copy of the licence on the driver
ALTER TABLE attachments DROP CONSTRAINT chk_attachments_owner;
ALTER TABLE attachments ADD CONSTRAINT chk_attachments_owner
    CHECK (owner_type IN ('GOODS_RECEIPT', 'SHIPMENT', 'SUPPLIER_INVOICE', 'STOCK_ADJUSTMENT', 'VEHICLE', 'DRIVER'));

INSERT INTO settings (setting_key, setting_value, created_by) VALUES
('fleet.expiry-alert-days', '30', 'system');

-- ---------------------------------------------------------------------
-- Security (SRS 2.2). The FLEET page (/trips) exists since V1; the TRIP
-- number sequence (TRP) since V5.
-- ---------------------------------------------------------------------
UPDATE pages SET name = 'Trips' WHERE code = 'FLEET';

INSERT INTO pages (code, name, module, path, icon, sort_order) VALUES
('VEHICLES', 'Vehicles', 'Fleet', '/vehicles', 'truck', 5),
('DRIVERS',  'Drivers',  'Fleet', '/drivers',  'id-card', 5);

INSERT INTO permissions (code, name, module, action, description) VALUES
('VIEW_VEHICLE',         'View Vehicles',             'Fleet', 'VIEW',    'View vehicles, their papers, trips and the stock on board'),
('MANAGE_VEHICLE',       'Manage Vehicles',           'Fleet', 'MANAGE',  'Add, edit, deactivate vehicles (each is a stock location)'),
('VIEW_DRIVER',          'View Drivers',              'Fleet', 'VIEW',    'View drivers, their licence expiry and trips'),
('MANAGE_DRIVER',        'Manage Drivers',            'Fleet', 'MANAGE',  'Register drivers, edit their licence, deactivate them'),
('VIEW_DRIVER_DATA',     'View Driver Personal Data', 'Fleet', 'VIEW',    'See national ID, licence number and licence copies (Law 058/2021)'),
('VIEW_TRIP',            'View Trips',                'Fleet', 'VIEW',    'View every trip; without it a driver sees their own'),
('PLAN_TRIP',            'Plan Trips',                'Fleet', 'PLAN',    'Plan a trip and its loading manifest, cancel a planned trip'),
('LOAD_TRIP',            'Scan-load Trips',           'Fleet', 'LOAD',    'Confirm the loading by scanning each unit'),
('DEPART_TRIP',          'Confirm Departure',         'Fleet', 'APPROVE', 'Approve the loading: the units go on the vehicle, in the driver''s charge'),
('RECORD_TRIP_READINGS', 'Record Trip Readings',      'Fleet', 'RECORD',  'Record the odometer and fuel of a trip'),
('ALERT_FLEET',          'Fleet Alerts',              'Fleet', 'NOTIFY',  'Told when a licence, insurance or inspection is about to expire');

-- ADMIN: everything.
INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE r.code = 'ADMIN' AND p.code IN ('VEHICLES', 'DRIVERS')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code = 'ADMIN' AND p.code IN ('VIEW_VEHICLE', 'MANAGE_VEHICLE', 'VIEW_DRIVER', 'MANAGE_DRIVER', 'VIEW_DRIVER_DATA',
    'VIEW_TRIP', 'PLAN_TRIP', 'LOAD_TRIP', 'DEPART_TRIP', 'RECORD_TRIP_READINGS', 'ALERT_FLEET')
ON CONFLICT DO NOTHING;

-- The fleet manager keeps vehicles and drivers and schedules trips; the
-- supervisor plans the manifests and approves the loading; the driver
-- scan-loads their own trip; the owner, accountant and auditor read.
INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE (p.code IN ('FLEET', 'VEHICLES', 'DRIVERS') AND r.code IN ('FLEET_MANAGER', 'WAREHOUSE_SUPERVISOR', 'OWNER', 'AUDITOR'))
   OR (p.code IN ('FLEET', 'VEHICLES') AND r.code = 'ACCOUNTANT')
   OR (p.code = 'FLEET' AND r.code = 'DRIVER')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE (r.code = 'FLEET_MANAGER' AND p.code IN ('VIEW_VEHICLE', 'MANAGE_VEHICLE', 'VIEW_DRIVER', 'MANAGE_DRIVER', 'VIEW_DRIVER_DATA',
           'VIEW_TRIP', 'PLAN_TRIP', 'RECORD_TRIP_READINGS', 'ALERT_FLEET', 'ATTACH_DOCUMENT'))
   OR (r.code = 'WAREHOUSE_SUPERVISOR' AND p.code IN ('VIEW_VEHICLE', 'VIEW_DRIVER', 'VIEW_TRIP', 'PLAN_TRIP', 'LOAD_TRIP',
           'DEPART_TRIP', 'RECORD_TRIP_READINGS'))
   OR (r.code = 'DRIVER' AND p.code IN ('LOAD_TRIP', 'RECORD_TRIP_READINGS'))
   OR (r.code = 'OWNER' AND p.code IN ('VIEW_VEHICLE', 'VIEW_DRIVER', 'VIEW_DRIVER_DATA', 'VIEW_TRIP', 'ALERT_FLEET'))
   OR (r.code = 'AUDITOR' AND p.code IN ('VIEW_VEHICLE', 'VIEW_DRIVER', 'VIEW_TRIP'))
   OR (r.code = 'ACCOUNTANT' AND p.code IN ('VIEW_VEHICLE', 'VIEW_TRIP'))
ON CONFLICT DO NOTHING;
