-- =====================================================================
-- V60: Operations controls (TASK.md)
--   - WhatsApp confirmation numbers on users and customers, and the
--     gateway settings that send the confirmations.
--   - Credit guardrails: open invoice debt and a hard collection
--     interval beside the existing overdue grace.
--   - Product aliases, so the counter and mobile search find a product
--     by the names the trade uses.
--   - A monthly distance target on a vehicle.
-- =====================================================================

-- ---------------------------------------------------------------------
-- WhatsApp confirmation numbers (international +250 format)
-- ---------------------------------------------------------------------
ALTER TABLE users ADD COLUMN whatsapp_number VARCHAR(20);
ALTER TABLE customers ADD COLUMN whatsapp_number VARCHAR(20);
ALTER TABLE users ADD CONSTRAINT chk_users_whatsapp
    CHECK (whatsapp_number IS NULL OR whatsapp_number ~ '^\+250[0-9]{9}$');
ALTER TABLE customers ADD CONSTRAINT chk_customers_whatsapp
    CHECK (whatsapp_number IS NULL OR whatsapp_number ~ '^\+250[0-9]{9}$');

-- ---------------------------------------------------------------------
-- Product aliases: another name or code the product is known by
-- ---------------------------------------------------------------------
CREATE TABLE product_aliases (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version          BIGINT       NOT NULL DEFAULT 0,
    product_id       UUID         NOT NULL REFERENCES products (id),
    alias            VARCHAR(100) NOT NULL,
    alias_normalized VARCHAR(100) NOT NULL,
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by       VARCHAR(50),
    updated_at       TIMESTAMP,
    updated_by       VARCHAR(50),
    CONSTRAINT uk_product_aliases_name UNIQUE (product_id, alias_normalized)
);
CREATE INDEX idx_product_aliases_normalized ON product_aliases (alias_normalized);

-- ---------------------------------------------------------------------
-- Vehicles: the monthly distance target (0/empty = no target)
-- ---------------------------------------------------------------------
ALTER TABLE vehicles ADD COLUMN monthly_distance_target_km INTEGER;
ALTER TABLE vehicles ADD CONSTRAINT chk_vehicles_monthly_target
    CHECK (monthly_distance_target_km IS NULL OR monthly_distance_target_km >= 0);

-- ---------------------------------------------------------------------
-- Settings
-- ---------------------------------------------------------------------
INSERT INTO settings (setting_key, setting_value, created_by) VALUES
('credit.block-open-debt',        'true', 'system'),  -- refuse new credit while the customer has any open invoice
('credit.collection-interval-days', '0',  'system'),  -- hard interval; 0 = use the overdue grace days
('whatsapp.enabled',              'false', 'system'),
('whatsapp.gateway-url',          NULL,    'system'),
('whatsapp.token',                NULL,    'system'),
('whatsapp.sender',               NULL,    'system');
