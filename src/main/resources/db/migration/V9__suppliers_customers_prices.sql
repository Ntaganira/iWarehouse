-- =====================================================================
-- V9: Master data part 2 - suppliers, customers and price lists
-- MD-05: suppliers with country, currency and default incoterm.
-- MD-04: customers (walk-in, account, contractor) with TIN, credit limit,
--        payment terms and price list.
-- MD-06: price lists per m² by product, processing surcharges (edging,
--        drilling, tempering...) and a minimum chargeable area per piece.
-- Supplier and customer codes come from DocumentNumberService (MD-07).
-- =====================================================================

CREATE TABLE suppliers (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version             BIGINT        NOT NULL DEFAULT 0,
    code                VARCHAR(30)   NOT NULL,          -- SUP-WH-0001
    name                VARCHAR(120)  NOT NULL,
    country_code        VARCHAR(2)    NOT NULL,          -- ISO 3166-1 alpha-2
    currency_code       VARCHAR(3)    NOT NULL REFERENCES currencies(code), -- invoicing currency (PRC-01)
    incoterm            VARCHAR(3),                      -- Incoterms 2020; empty for local suppliers
    tin                 VARCHAR(30),
    payment_terms_days  INTEGER       NOT NULL DEFAULT 0,
    contact_name        VARCHAR(100),
    phone               VARCHAR(30),
    email               VARCHAR(120),
    address             VARCHAR(255),
    notes               VARCHAR(255),
    enabled             BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          VARCHAR(50),
    updated_at          TIMESTAMP,
    updated_by          VARCHAR(50),
    CONSTRAINT uk_suppliers_code UNIQUE (code),
    CONSTRAINT chk_suppliers_country CHECK (country_code ~ '^[A-Z]{2}$'),
    CONSTRAINT chk_suppliers_incoterm CHECK (incoterm IS NULL OR incoterm IN
        ('EXW', 'FCA', 'FAS', 'FOB', 'CFR', 'CIF', 'CPT', 'CIP', 'DAP', 'DPU', 'DDP')),
    CONSTRAINT chk_suppliers_terms CHECK (payment_terms_days BETWEEN 0 AND 365)
);

CREATE UNIQUE INDEX uk_suppliers_name ON suppliers (LOWER(name));
CREATE INDEX idx_suppliers_currency ON suppliers (currency_code);

CREATE TABLE price_lists (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version             BIGINT        NOT NULL DEFAULT 0,
    code                VARCHAR(20)   NOT NULL,
    name                VARCHAR(60)   NOT NULL,
    prices_include_vat  BOOLEAN       NOT NULL DEFAULT TRUE,
    min_chargeable_m2   NUMERIC(10,4),                   -- empty = Settings (pricing.min-chargeable-area)
    is_default          BOOLEAN       NOT NULL DEFAULT FALSE,
    notes               VARCHAR(255),
    enabled             BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          VARCHAR(50),
    updated_at          TIMESTAMP,
    updated_by          VARCHAR(50),
    CONSTRAINT uk_price_lists_code UNIQUE (code),
    CONSTRAINT chk_price_lists_code CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{1,19}$'),
    CONSTRAINT chk_price_lists_min_area CHECK (min_chargeable_m2 IS NULL OR min_chargeable_m2 > 0),
    CONSTRAINT chk_price_lists_default_enabled CHECK (enabled OR NOT is_default)
);

-- Exactly one default list: customers without a list, and products a customer's list does not price.
CREATE UNIQUE INDEX uk_price_lists_default ON price_lists (is_default) WHERE is_default;

-- Price per m² of a product on a list. Rows are kept when a price is cleared
-- (price NULL = not priced), so the price history of the list stays complete.
CREATE TABLE price_list_items (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version        BIGINT         NOT NULL DEFAULT 0,
    price_list_id  UUID           NOT NULL REFERENCES price_lists(id),
    product_id     UUID           NOT NULL REFERENCES products(id),
    price_per_m2   NUMERIC(18,2),
    created_at     TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by     VARCHAR(50),
    updated_at     TIMESTAMP,
    updated_by     VARCHAR(50),
    CONSTRAINT uk_price_list_items UNIQUE (price_list_id, product_id),
    CONSTRAINT chk_price_list_items_price CHECK (price_per_m2 IS NULL OR price_per_m2 > 0)
);

CREATE INDEX idx_price_list_items_product ON price_list_items (product_id);

-- Processing charged on top of the glass (POS-02).
CREATE TABLE processing_services (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version      BIGINT       NOT NULL DEFAULT 0,
    code         VARCHAR(20)  NOT NULL,
    name         VARCHAR(60)  NOT NULL,
    charge_unit  VARCHAR(10)  NOT NULL,                  -- what one unit of the price covers
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by   VARCHAR(50),
    updated_at   TIMESTAMP,
    updated_by   VARCHAR(50),
    CONSTRAINT uk_processing_services_code UNIQUE (code),
    CONSTRAINT chk_processing_services_code CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{1,19}$'),
    CONSTRAINT chk_processing_services_unit CHECK (charge_unit IN ('M2', 'METRE', 'PIECE', 'HOLE'))
);

CREATE TABLE price_list_services (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version        BIGINT         NOT NULL DEFAULT 0,
    price_list_id  UUID           NOT NULL REFERENCES price_lists(id),
    service_id     UUID           NOT NULL REFERENCES processing_services(id),
    price          NUMERIC(18,2),                        -- per charge unit; NULL = not priced
    created_at     TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by     VARCHAR(50),
    updated_at     TIMESTAMP,
    updated_by     VARCHAR(50),
    CONSTRAINT uk_price_list_services UNIQUE (price_list_id, service_id),
    CONSTRAINT chk_price_list_services_price CHECK (price IS NULL OR price > 0)
);

CREATE TABLE customers (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version             BIGINT        NOT NULL DEFAULT 0,
    code                VARCHAR(30)   NOT NULL,          -- CUS-WH-00001
    name                VARCHAR(120)  NOT NULL,
    customer_type       VARCHAR(12)   NOT NULL,
    tin                 VARCHAR(9),                      -- RRA TIN, printed on invoices (TAX-04)
    phone               VARCHAR(30),
    email               VARCHAR(120),
    contact_name        VARCHAR(100),
    address             VARCHAR(255),
    credit_limit        NUMERIC(18,2) NOT NULL DEFAULT 0, -- RWF; 0 = cash only (POS-05)
    payment_terms_days  INTEGER       NOT NULL DEFAULT 0,
    price_list_id       UUID          REFERENCES price_lists(id), -- empty = the default list
    is_default          BOOLEAN       NOT NULL DEFAULT FALSE,     -- anonymous counter sales
    notes               VARCHAR(255),
    enabled             BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          VARCHAR(50),
    updated_at          TIMESTAMP,
    updated_by          VARCHAR(50),
    CONSTRAINT uk_customers_code UNIQUE (code),
    CONSTRAINT chk_customers_type CHECK (customer_type IN ('WALK_IN', 'ACCOUNT', 'CONTRACTOR')),
    CONSTRAINT chk_customers_tin CHECK (tin IS NULL OR tin ~ '^[0-9]{9}$'),
    CONSTRAINT chk_customers_credit CHECK (credit_limit >= 0),
    CONSTRAINT chk_customers_terms CHECK (payment_terms_days BETWEEN 0 AND 365),
    -- Walk-in customers pay at the counter: no credit.
    CONSTRAINT chk_customers_walk_in_cash CHECK (customer_type <> 'WALK_IN' OR (credit_limit = 0 AND payment_terms_days = 0)),
    CONSTRAINT chk_customers_default CHECK (NOT is_default OR (customer_type = 'WALK_IN' AND enabled))
);

CREATE UNIQUE INDEX uk_customers_default ON customers (is_default) WHERE is_default;
-- One customer record per taxpayer, so a credit limit cannot be doubled with a second account.
CREATE UNIQUE INDEX uk_customers_tin ON customers (tin) WHERE tin IS NOT NULL;
CREATE INDEX idx_customers_name ON customers (LOWER(name));
CREATE INDEX idx_customers_price_list ON customers (price_list_id);

-- ---------------------------------------------------------------------
-- Starting data: the default retail list (prices to be entered), the
-- usual processing services, and the anonymous walk-in customer.
-- ---------------------------------------------------------------------
INSERT INTO price_lists (code, name, prices_include_vat, is_default, notes, created_by) VALUES
('RETAIL', 'Retail (counter)', TRUE, TRUE, 'Default prices for counter and moving-shop sales', 'system');

INSERT INTO processing_services (code, name, charge_unit, created_by) VALUES
('EDGING',    'Edging',    'METRE', 'system'),
('POLISHING', 'Polishing', 'METRE', 'system'),
('BEVELLING', 'Bevelling', 'METRE', 'system'),
('DRILLING',  'Drilling',  'HOLE',  'system'),
('TEMPERING', 'Tempering', 'M2',    'system');

INSERT INTO customers (code, name, customer_type, is_default, notes, created_by) VALUES
('WALK-IN', 'Walk-in customer', 'WALK_IN', TRUE, 'Anonymous counter sales, paid on the spot', 'system');

-- Codes for new customers and suppliers (MD-07): never reset.
INSERT INTO number_sequences (doc_type, branch_code, prefix, reset_policy, padding, created_by) VALUES
('CUSTOMER', 'WH', 'CUS', 'NEVER', 5, 'system'),
('SUPPLIER', 'WH', 'SUP', 'NEVER', 4, 'system');

-- ---------------------------------------------------------------------
-- Security: pages, permissions and grants (SRS 2.2). The CUSTOMERS page
-- exists since V1.
-- ---------------------------------------------------------------------
INSERT INTO pages (code, name, module, path, icon, sort_order) VALUES
('SUPPLIERS',   'Suppliers',   'Procurement', '/suppliers',   'factory', 4),
('PRICE_LISTS', 'Price Lists', 'Sales & POS', '/price-lists', 'tag',     8);

INSERT INTO permissions (code, name, module, action, description) VALUES
('VIEW_SUPPLIER',         'View Suppliers',           'Suppliers',   'VIEW',   'View suppliers'),
('MANAGE_SUPPLIER',       'Manage Suppliers',         'Suppliers',   'MANAGE', 'Add, edit, activate and deactivate suppliers'),
('VIEW_CUSTOMER',         'View Customers',           'Customers',   'VIEW',   'View customers'),
('MANAGE_CUSTOMER',       'Manage Customers',         'Customers',   'MANAGE', 'Add, edit, activate and deactivate customers'),
('MANAGE_CUSTOMER_TERMS', 'Set Customer Credit Terms', 'Customers',  'MANAGE', 'Set credit limit, payment terms and price list of a customer'),
('VIEW_PRICE_LIST',       'View Price Lists',         'Price Lists', 'VIEW',   'View price lists and processing prices'),
('MANAGE_PRICE_LIST',     'Manage Price Lists',       'Price Lists', 'MANAGE', 'Set prices, add price lists and processing services');

-- ADMIN: everything.
INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE r.code = 'ADMIN' AND p.code IN ('SUPPLIERS', 'PRICE_LISTS')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code = 'ADMIN' AND p.module IN ('Suppliers', 'Customers', 'Price Lists')
ON CONFLICT DO NOTHING;

-- Pages: procurement keeps suppliers; the counter and the accountant keep
-- customers; the owner sets prices; owner and auditor read everything.
INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE (p.code = 'SUPPLIERS'   AND r.code IN ('PROCUREMENT', 'ACCOUNTANT', 'OWNER', 'AUDITOR'))
   OR (p.code = 'CUSTOMERS'   AND r.code IN ('CASHIER', 'ACCOUNTANT', 'OWNER', 'AUDITOR'))
   OR (p.code = 'PRICE_LISTS' AND r.code IN ('OWNER', 'CASHIER', 'ACCOUNTANT', 'AUDITOR'))
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE (p.code = 'VIEW_SUPPLIER'         AND r.code IN ('PROCUREMENT', 'ACCOUNTANT', 'OWNER', 'AUDITOR'))
   OR (p.code = 'MANAGE_SUPPLIER'       AND r.code = 'PROCUREMENT')
   OR (p.code = 'VIEW_CUSTOMER'         AND r.code IN ('CASHIER', 'ACCOUNTANT', 'OWNER', 'AUDITOR'))
   OR (p.code = 'MANAGE_CUSTOMER'       AND r.code IN ('CASHIER', 'ACCOUNTANT'))
   OR (p.code = 'MANAGE_CUSTOMER_TERMS' AND r.code = 'ACCOUNTANT')
   OR (p.code = 'VIEW_PRICE_LIST'       AND r.code IN ('OWNER', 'CASHIER', 'ACCOUNTANT', 'AUDITOR'))
   OR (p.code = 'MANAGE_PRICE_LIST'     AND r.code = 'OWNER')
ON CONFLICT DO NOTHING;
