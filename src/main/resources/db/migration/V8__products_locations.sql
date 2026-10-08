-- =====================================================================
-- V8: Master data part 1 - glass products and stock locations
-- MD-01: a product is a glass type + optional colour/finish + thickness,
--        sold and stocked by the m².
-- MD-02: locations form a tree Site > Zone > Rack > Slot, plus one virtual
--        location per vehicle (created by the Fleet module, FLT-01).
-- MD-03: racks carry a weight limit, a piece limit and the sheet
--        orientation they take.
-- =====================================================================

CREATE TABLE products (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version           BIGINT        NOT NULL DEFAULT 0,
    code              VARCHAR(20)   NOT NULL,
    glass_type        VARCHAR(20)   NOT NULL,
    variant           VARCHAR(30),                  -- colour or finish: Bronze, Grey, Low-iron
    thickness_mm      NUMERIC(5,2)  NOT NULL,       -- laminated glass is sold as 6.38 mm
    tax_category_id   UUID          NOT NULL REFERENCES tax_categories(id),
    reorder_level_m2  NUMERIC(10,4),                -- INV-10 alert threshold; empty = no alert
    notes             VARCHAR(255),
    enabled           BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by        VARCHAR(50),
    updated_at        TIMESTAMP,
    updated_by        VARCHAR(50),
    CONSTRAINT uk_products_code UNIQUE (code),
    CONSTRAINT chk_products_code CHECK (code ~ '^[A-Z0-9][A-Z0-9.-]{1,19}$'),
    CONSTRAINT chk_products_glass_type CHECK (glass_type IN
        ('CLEAR', 'TINTED', 'REFLECTIVE', 'FROSTED', 'TEMPERED', 'LAMINATED', 'MIRROR')),
    CONSTRAINT chk_products_thickness CHECK (thickness_mm > 0 AND thickness_mm <= 50),
    CONSTRAINT chk_products_reorder CHECK (reorder_level_m2 IS NULL OR reorder_level_m2 >= 0)
);

-- One product per type, colour/finish and thickness (colour compared without case).
CREATE UNIQUE INDEX uk_products_identity ON products (glass_type, LOWER(COALESCE(variant, '')), thickness_mm);
CREATE INDEX idx_products_tax_category ON products (tax_category_id);

CREATE TABLE locations (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version        BIGINT       NOT NULL DEFAULT 0,
    code           VARCHAR(30)  NOT NULL,           -- printed on rack labels, scanned in stock counts
    name           VARCHAR(60),
    location_type  VARCHAR(10)  NOT NULL,
    parent_id      UUID         REFERENCES locations(id),
    offcut         BOOLEAN      NOT NULL DEFAULT FALSE, -- rack reserved for off-cuts (PRD-04)
    max_weight_kg  INTEGER,                         -- MD-03, racks only; empty = no limit
    max_pieces     INTEGER,
    orientation    VARCHAR(10),
    enabled        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by     VARCHAR(50),
    updated_at     TIMESTAMP,
    updated_by     VARCHAR(50),
    CONSTRAINT uk_locations_code UNIQUE (code),
    CONSTRAINT chk_locations_code CHECK (code ~ '^[A-Z0-9][A-Z0-9-]{0,29}$'),
    CONSTRAINT chk_locations_type CHECK (location_type IN ('SITE', 'ZONE', 'RACK', 'SLOT', 'VEHICLE')),
    -- Sites and vehicles are roots; zones, racks and slots sit under a parent (its type is checked in LocationService).
    CONSTRAINT chk_locations_parent CHECK ((location_type IN ('SITE', 'VEHICLE')) = (parent_id IS NULL)),
    CONSTRAINT chk_locations_rack_only CHECK (location_type = 'RACK'
        OR (NOT offcut AND max_weight_kg IS NULL AND max_pieces IS NULL AND orientation IS NULL)),
    CONSTRAINT chk_locations_rack_orientation CHECK (location_type <> 'RACK' OR orientation IS NOT NULL),
    CONSTRAINT chk_locations_orientation CHECK (orientation IS NULL OR orientation IN ('VERTICAL', 'HORIZONTAL', 'BOTH')),
    CONSTRAINT chk_locations_limits CHECK ((max_weight_kg IS NULL OR max_weight_kg > 0)
        AND (max_pieces IS NULL OR max_pieces > 0))
);

CREATE INDEX idx_locations_parent ON locations (parent_id);

-- ---------------------------------------------------------------------
-- Starting products: a common range to confirm with the business
-- (TODO.md). All use the default VAT category.
-- ---------------------------------------------------------------------
INSERT INTO products (code, glass_type, variant, thickness_mm, tax_category_id, created_by)
SELECT p.code, p.glass_type, p.variant, p.thickness_mm, t.id, 'system'
FROM (VALUES
    ('CLR-3',        'CLEAR',      NULL,     3.00),
    ('CLR-4',        'CLEAR',      NULL,     4.00),
    ('CLR-5',        'CLEAR',      NULL,     5.00),
    ('CLR-6',        'CLEAR',      NULL,     6.00),
    ('CLR-8',        'CLEAR',      NULL,     8.00),
    ('CLR-10',       'CLEAR',      NULL,    10.00),
    ('CLR-12',       'CLEAR',      NULL,    12.00),
    ('TNT-BRONZE-5', 'TINTED',     'Bronze', 5.00),
    ('TNT-BRONZE-6', 'TINTED',     'Bronze', 6.00),
    ('TNT-GREY-5',   'TINTED',     'Grey',   5.00),
    ('TNT-GREY-6',   'TINTED',     'Grey',   6.00),
    ('RFL-BLUE-6',   'REFLECTIVE', 'Blue',   6.00),
    ('FRS-4',        'FROSTED',    NULL,     4.00),
    ('FRS-5',        'FROSTED',    NULL,     5.00),
    ('MIR-4',        'MIRROR',     NULL,     4.00),
    ('MIR-5',        'MIRROR',     NULL,     5.00),
    ('LAM-6.38',     'LAMINATED',  NULL,     6.38),
    ('LAM-8.38',     'LAMINATED',  NULL,     8.38)
) AS p(code, glass_type, variant, thickness_mm)
CROSS JOIN tax_categories t
WHERE t.is_default;

-- ---------------------------------------------------------------------
-- Starting locations: one site with a sample zone, to rename or extend.
-- Rack limits are examples to adjust to the real racks.
-- ---------------------------------------------------------------------
INSERT INTO locations (code, name, location_type, created_by) VALUES
('WH', 'Main warehouse', 'SITE', 'system');

INSERT INTO locations (code, name, location_type, parent_id, created_by)
SELECT 'WH-A', 'Zone A', 'ZONE', id, 'system' FROM locations WHERE code = 'WH';

INSERT INTO locations (code, name, location_type, parent_id, offcut, max_weight_kg, max_pieces, orientation, created_by)
SELECT r.code, r.name, 'RACK', z.id, r.offcut, r.max_weight_kg, r.max_pieces, r.orientation, 'system'
FROM (VALUES
    ('WH-A-R01', 'Rack 1',       FALSE, 3000, 30,  'VERTICAL'),
    ('WH-A-R02', 'Rack 2',       FALSE, 3000, 30,  'VERTICAL'),
    ('WH-A-OC',  'Off-cut rack', TRUE,  1000, 100, 'BOTH')
) AS r(code, name, offcut, max_weight_kg, max_pieces, orientation)
CROSS JOIN locations z
WHERE z.code = 'WH-A';

-- ---------------------------------------------------------------------
-- Security: pages, permissions and grants (SRS 2.2)
-- ---------------------------------------------------------------------
INSERT INTO pages (code, name, module, path, icon, sort_order) VALUES
('PRODUCTS',  'Glass Products', 'Inventory', '/products',  'layers', 2),
('LOCATIONS', 'Locations',      'Inventory', '/locations', 'pin',    2);

INSERT INTO permissions (code, name, module, action, description) VALUES
('VIEW_PRODUCT',    'View Glass Products',   'Products',  'VIEW',   'View glass products'),
('MANAGE_PRODUCT',  'Manage Glass Products', 'Products',  'MANAGE', 'Add, edit, activate and deactivate glass products'),
('VIEW_LOCATION',   'View Locations',        'Locations', 'VIEW',   'View sites, zones, racks and slots'),
('MANAGE_LOCATION', 'Manage Locations',      'Locations', 'MANAGE', 'Add, edit, activate and deactivate locations and rack limits');

-- ADMIN: everything (master data is the administrator's job, SRS 2.2).
INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE r.code = 'ADMIN' AND p.code IN ('PRODUCTS', 'LOCATIONS')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code = 'ADMIN' AND p.code IN ('VIEW_PRODUCT', 'MANAGE_PRODUCT', 'VIEW_LOCATION', 'MANAGE_LOCATION')
ON CONFLICT DO NOTHING;

-- Products: read by the roles that buy, store, cut, sell or account for glass.
INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE p.code = 'PRODUCTS'
  AND r.code IN ('OWNER', 'PROCUREMENT', 'WAREHOUSE_SUPERVISOR', 'CUTTING_OPERATOR', 'CASHIER', 'ACCOUNTANT', 'AUDITOR')
ON CONFLICT DO NOTHING;

-- Locations: read by the roles that put glass on racks or take it off;
-- the warehouse supervisor runs the racks and keeps them up to date.
INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE p.code = 'LOCATIONS'
  AND r.code IN ('OWNER', 'PROCUREMENT', 'WAREHOUSE_SUPERVISOR', 'CUTTING_OPERATOR', 'AUDITOR')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE (p.code = 'VIEW_PRODUCT'
       AND r.code IN ('OWNER', 'PROCUREMENT', 'WAREHOUSE_SUPERVISOR', 'CUTTING_OPERATOR', 'CASHIER', 'ACCOUNTANT', 'AUDITOR'))
   OR (p.code = 'VIEW_LOCATION'
       AND r.code IN ('OWNER', 'PROCUREMENT', 'WAREHOUSE_SUPERVISOR', 'CUTTING_OPERATOR', 'AUDITOR'))
   OR (p.code = 'MANAGE_LOCATION' AND r.code = 'WAREHOUSE_SUPERVISOR')
ON CONFLICT DO NOTHING;
