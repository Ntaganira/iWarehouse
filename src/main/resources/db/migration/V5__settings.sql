-- =====================================================================
-- V5: Settings (ADM-03), tax categories (TAX-01) and document numbering (MD-07)
-- All three are audited business data (SRS 4.13: "... users, roles,
-- permissions and settings"), so they use UUID ids and @Version.
-- The SETTINGS page and VIEW_SETTINGS / EDIT_SETTINGS come from V1.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Settings: one row per key. Keys, types and limits are defined in code
-- (enums/SettingKey); a new setting = a new enum constant + a row here.
-- ---------------------------------------------------------------------
CREATE TABLE settings (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version       BIGINT       NOT NULL DEFAULT 0,
    setting_key   VARCHAR(100) NOT NULL,
    setting_value VARCHAR(500),
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by    VARCHAR(50),
    updated_at    TIMESTAMP,
    updated_by    VARCHAR(50),
    CONSTRAINT uk_settings_key UNIQUE (setting_key)
);

INSERT INTO settings (setting_key, setting_value, created_by) VALUES
('company.name',                   'iWarehouse',     'system'),
('company.tin',                    NULL,             'system'),
('company.address',                'Kigali, Rwanda', 'system'),
('company.phone',                  NULL,             'system'),
('company.email',                  NULL,             'system'),
('company.branch-code',            'WH',             'system'),  -- default branch for numbering (MD-07)
('production.offcut.min-area',     '0.25',           'system'),  -- m2 (PRD-04)
('production.offcut.min-side',     '300',            'system'),  -- mm (PRD-04)
('production.glass-density',       '2.5',            'system'),  -- kg per m2 per mm of thickness
('pricing.min-chargeable-area',    '0.25',           'system'),  -- m2, default for new price lists (MD-06)
('approval.adjustment-limit',      '0',              'system'),  -- RWF; adjustments above it need approval (INV-07)
('approval.discount-limit-percent','0',              'system');  -- %; discounts above it need approval (POS-06)

-- ---------------------------------------------------------------------
-- Tax categories (TAX-01): standard 18%, zero-rated, exempt. ebm_code is
-- the RRA EBM/VSDC tax type sent with each invoice line (TAX-02).
-- Confirm the codes against the VSDC specification before go-live.
-- ---------------------------------------------------------------------
CREATE TABLE tax_categories (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version     BIGINT        NOT NULL DEFAULT 0,
    code        VARCHAR(20)   NOT NULL,
    name        VARCHAR(100)  NOT NULL,
    rate        NUMERIC(5, 2) NOT NULL,
    ebm_code    VARCHAR(1)    NOT NULL,
    description VARCHAR(255),
    is_default  BOOLEAN       NOT NULL DEFAULT FALSE,
    enabled     BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by  VARCHAR(50),
    updated_at  TIMESTAMP,
    updated_by  VARCHAR(50),
    CONSTRAINT uk_tax_categories_code UNIQUE (code),
    CONSTRAINT chk_tax_categories_code CHECK (code ~ '^[A-Z][A-Z0-9_]{1,19}$'),
    CONSTRAINT chk_tax_categories_rate CHECK (rate >= 0 AND rate <= 100),
    CONSTRAINT chk_tax_categories_ebm_code CHECK (ebm_code ~ '^[A-Z]$'),
    CONSTRAINT chk_tax_categories_default_enabled CHECK (enabled OR NOT is_default)
);

-- At most one default category (used for new products).
CREATE UNIQUE INDEX uk_tax_categories_default ON tax_categories (is_default) WHERE is_default;

INSERT INTO tax_categories (code, name, rate, ebm_code, description, is_default, created_by) VALUES
('STANDARD', 'Standard rate',  18.00, 'B', 'Taxable supplies at the standard VAT rate', TRUE,  'system'),
('ZERO',     'Zero-rated',      0.00, 'C', 'Zero-rated supplies, e.g. exports',         FALSE, 'system'),
('EXEMPT',   'Exempt',          0.00, 'A', 'Supplies exempt from VAT',                  FALSE, 'system');

-- ---------------------------------------------------------------------
-- Document numbering (MD-07): one sequence per document type and branch.
-- Number = PREFIX-BRANCH[-PERIOD]-SEQUENCE, e.g. INV-WH-2026-000123.
-- next_value and period_key are the counter; services issue numbers
-- under a row lock, inside the transaction that saves the document.
-- ---------------------------------------------------------------------
CREATE TABLE number_sequences (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version      BIGINT      NOT NULL DEFAULT 0,
    doc_type     VARCHAR(30) NOT NULL,
    branch_code  VARCHAR(10) NOT NULL,
    prefix       VARCHAR(10) NOT NULL,
    reset_policy VARCHAR(10) NOT NULL DEFAULT 'YEARLY',
    padding      INTEGER     NOT NULL DEFAULT 6,
    next_value   BIGINT      NOT NULL DEFAULT 1,
    period_key   VARCHAR(7)  NOT NULL DEFAULT '',   -- '2026', '2026-10' or '' (never reset)
    created_at   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by   VARCHAR(50),
    updated_at   TIMESTAMP,
    updated_by   VARCHAR(50),
    CONSTRAINT uk_number_sequences_type_branch UNIQUE (doc_type, branch_code),
    CONSTRAINT uk_number_sequences_prefix_branch UNIQUE (prefix, branch_code),
    CONSTRAINT chk_number_sequences_reset CHECK (reset_policy IN ('NEVER', 'YEARLY', 'MONTHLY')),
    CONSTRAINT chk_number_sequences_padding CHECK (padding BETWEEN 3 AND 10),
    CONSTRAINT chk_number_sequences_next CHECK (next_value >= 1),
    CONSTRAINT chk_number_sequences_prefix CHECK (prefix ~ '^[A-Z][A-Z0-9]{0,9}$'),
    CONSTRAINT chk_number_sequences_branch CHECK (branch_code ~ '^[A-Z0-9]{1,10}$')
);

INSERT INTO number_sequences (doc_type, branch_code, prefix, created_by) VALUES
('INVOICE',        'WH', 'INV', 'system'),
('QUOTATION',      'WH', 'QUO', 'system'),
('SALES_ORDER',    'WH', 'SO',  'system'),
('CREDIT_NOTE',    'WH', 'CN',  'system'),
('RECEIPT',        'WH', 'RCT', 'system'),
('PURCHASE_ORDER', 'WH', 'PO',  'system'),
('GOODS_RECEIPT',  'WH', 'GRN', 'system'),
('SHIPMENT',       'WH', 'SHP', 'system'),
('TRANSFER',       'WH', 'TRF', 'system'),
('ADJUSTMENT',     'WH', 'ADJ', 'system'),
('STOCK_COUNT',    'WH', 'CNT', 'system'),
('CUTTING_JOB',    'WH', 'CUT', 'system'),
('TRIP',           'WH', 'TRP', 'system'),
('JOURNAL',        'WH', 'JV',  'system');

-- ---------------------------------------------------------------------
-- Grants: OWNER and AUDITOR read settings (SRS 2.2 "read all").
-- ADMIN already holds the SETTINGS page and both permissions (V1).
-- ---------------------------------------------------------------------
INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE r.code IN ('OWNER', 'AUDITOR') AND p.code = 'SETTINGS'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code IN ('OWNER', 'AUDITOR') AND p.code = 'VIEW_SETTINGS'
ON CONFLICT DO NOTHING;
