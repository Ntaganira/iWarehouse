-- =====================================================================
-- V7: Currencies and exchange rates (ACC-02)
-- RWF is the base currency: ledgers are always in RWF, foreign documents
-- keep their own amount, currency and rate (CLAUDE.md). A rate is the
-- number of RWF for 1 unit of the currency, by date and source.
-- =====================================================================

CREATE TABLE currencies (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version     BIGINT       NOT NULL DEFAULT 0,
    code        VARCHAR(3)   NOT NULL,          -- ISO 4217
    name        VARCHAR(60)  NOT NULL,
    symbol      VARCHAR(8)   NOT NULL,
    decimals    INTEGER      NOT NULL DEFAULT 2, -- minor units used when rounding amounts
    is_base     BOOLEAN      NOT NULL DEFAULT FALSE,
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by  VARCHAR(50),
    updated_at  TIMESTAMP,
    updated_by  VARCHAR(50),
    CONSTRAINT uk_currencies_code UNIQUE (code),
    CONSTRAINT chk_currencies_code CHECK (code ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_currencies_decimals CHECK (decimals BETWEEN 0 AND 4),
    CONSTRAINT chk_currencies_base_enabled CHECK (enabled OR NOT is_base)
);

-- Exactly one base currency.
CREATE UNIQUE INDEX uk_currencies_base ON currencies (is_base) WHERE is_base;

INSERT INTO currencies (code, name, symbol, decimals, is_base, enabled, created_by) VALUES
('RWF', 'Rwandan franc',      'FRw', 0, TRUE,  TRUE,  'system'),
('USD', 'US dollar',          '$',   2, FALSE, TRUE,  'system'),
('EUR', 'Euro',               '€',   2, FALSE, TRUE,  'system'),
('CNY', 'Chinese yuan',       '¥',   2, FALSE, TRUE,  'system'),
('AED', 'UAE dirham',         'AED', 2, FALSE, FALSE, 'system'),
('GBP', 'Pound sterling',     '£',   2, FALSE, FALSE, 'system'),
('KES', 'Kenyan shilling',    'KSh', 2, FALSE, FALSE, 'system'),
('UGX', 'Ugandan shilling',   'USh', 0, FALSE, FALSE, 'system'),
('TZS', 'Tanzanian shilling', 'TSh', 2, FALSE, FALSE, 'system'),
('INR', 'Indian rupee',       '₹',   2, FALSE, FALSE, 'system');

-- ---------------------------------------------------------------------
-- Exchange rates: RWF for 1 unit of the currency, per date and source.
-- BNR = National Bank of Rwanda reference rate; CUSTOMS = RRA rate used
-- to value imports for duty; BANK = rate of an actual bank deal.
-- Rates are corrected (with a reason), never deleted.
-- ---------------------------------------------------------------------
CREATE TABLE exchange_rates (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version       BIGINT         NOT NULL DEFAULT 0,
    currency_code VARCHAR(3)     NOT NULL REFERENCES currencies (code),
    rate_date     DATE           NOT NULL,
    source        VARCHAR(10)    NOT NULL,
    rate          NUMERIC(18, 6) NOT NULL,
    note          VARCHAR(255),
    created_at    TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by    VARCHAR(50),
    updated_at    TIMESTAMP,
    updated_by    VARCHAR(50),
    CONSTRAINT uk_exchange_rates_currency_date_source UNIQUE (currency_code, rate_date, source),
    CONSTRAINT chk_exchange_rates_source CHECK (source IN ('BNR', 'CUSTOMS', 'BANK', 'MANUAL')),
    CONSTRAINT chk_exchange_rates_rate CHECK (rate > 0)
);

CREATE INDEX idx_exchange_rates_lookup ON exchange_rates (currency_code, source, rate_date DESC);
CREATE INDEX idx_exchange_rates_date ON exchange_rates (rate_date DESC);

-- ---------------------------------------------------------------------
-- Settings (ADM-03): which source documents use by default, and how old
-- the latest rate may be before documents refuse it.
-- ---------------------------------------------------------------------
INSERT INTO settings (setting_key, setting_value, created_by) VALUES
('currency.default-rate-source', 'BNR', 'system'),
('currency.max-rate-age-days',   '7',   'system');

-- ---------------------------------------------------------------------
-- Security: page, permissions and grants (SRS 2.2)
-- ---------------------------------------------------------------------
INSERT INTO pages (code, name, module, path, icon, sort_order) VALUES
('CURRENCIES', 'Currencies & Rates', 'Accounting', '/currencies', 'currency', 9);

INSERT INTO permissions (code, name, module, action, description) VALUES
('VIEW_CURRENCY',        'View Currencies and Rates', 'Currencies', 'VIEW',   'View currencies and exchange rates'),
('MANAGE_CURRENCY',      'Manage Currencies',         'Currencies', 'MANAGE', 'Add, edit, activate and deactivate currencies'),
('MANAGE_EXCHANGE_RATE', 'Manage Exchange Rates',     'Currencies', 'MANAGE', 'Record, import and correct exchange rates');

-- ADMIN: everything.
INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE r.code = 'ADMIN' AND p.code = 'CURRENCIES'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code = 'ADMIN' AND p.code IN ('VIEW_CURRENCY', 'MANAGE_CURRENCY', 'MANAGE_EXCHANGE_RATE')
ON CONFLICT DO NOTHING;

-- ACCOUNTANT keeps currencies and rates; PROCUREMENT records rates for imports
-- (customs and bank rates); OWNER and AUDITOR read.
INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE r.code IN ('ACCOUNTANT', 'PROCUREMENT', 'OWNER', 'AUDITOR') AND p.code = 'CURRENCIES'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE (r.code IN ('ACCOUNTANT', 'PROCUREMENT', 'OWNER', 'AUDITOR') AND p.code = 'VIEW_CURRENCY')
   OR (r.code = 'ACCOUNTANT' AND p.code IN ('MANAGE_CURRENCY', 'MANAGE_EXCHANGE_RATE'))
   OR (r.code = 'PROCUREMENT' AND p.code = 'MANAGE_EXCHANGE_RATE')
ON CONFLICT DO NOTHING;
