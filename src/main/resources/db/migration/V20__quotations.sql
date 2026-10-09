-- =====================================================================
-- V20: quotations (POS-03). A priced offer for a customer with a validity
-- date: whole sheets from stock and sizes to cut with their processing,
-- priced from the customer's list (a discount per line within the author's
-- limit). Draft, then sent (fixed, printed); rung up at a till it becomes
-- the sale, and the invoice when paid; cancelled with a reason.
-- =====================================================================

CREATE TABLE quotations (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version         BIGINT         NOT NULL DEFAULT 0,
    number          VARCHAR(30)    NOT NULL,          -- QUO-WH-2026-000001
    status          VARCHAR(12)    NOT NULL,
    customer_id     UUID           NOT NULL REFERENCES customers(id),
    buyer_name      VARCHAR(100),
    buyer_tin       VARCHAR(9),
    quote_date      DATE           NOT NULL,
    valid_until     DATE           NOT NULL,
    notes           VARCHAR(500),
    net_amount      NUMERIC(18,2)  NOT NULL DEFAULT 0,
    vat_amount      NUMERIC(18,2)  NOT NULL DEFAULT 0,
    total_amount    NUMERIC(18,2)  NOT NULL DEFAULT 0,
    sent_at         TIMESTAMP,
    sent_by         VARCHAR(50),
    invoice_id      UUID           REFERENCES sales_invoices(id),
    converted_at    TIMESTAMP,
    cancel_reason   VARCHAR(255),
    cancelled_at    TIMESTAMP,
    cancelled_by    VARCHAR(50),
    created_at      TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by      VARCHAR(50),
    updated_at      TIMESTAMP,
    updated_by      VARCHAR(50),
    CONSTRAINT uk_quotations_number UNIQUE (number),
    CONSTRAINT chk_quotations_status CHECK (status IN ('DRAFT', 'SENT', 'CONVERTED', 'CANCELLED')),
    CONSTRAINT chk_quotations_dates CHECK (valid_until >= quote_date),
    CONSTRAINT chk_quotations_totals CHECK (total_amount >= 0 AND total_amount = net_amount + vat_amount),
    CONSTRAINT chk_quotations_tin CHECK (buyer_tin IS NULL OR buyer_tin ~ '^[0-9]{9}$'),
    CONSTRAINT chk_quotations_sent CHECK (status NOT IN ('SENT', 'CONVERTED') OR (sent_at IS NOT NULL AND sent_by IS NOT NULL)),
    CONSTRAINT chk_quotations_converted CHECK (status <> 'CONVERTED' OR (invoice_id IS NOT NULL AND converted_at IS NOT NULL)),
    CONSTRAINT chk_quotations_cancelled CHECK (status <> 'CANCELLED'
        OR (cancel_reason IS NOT NULL AND cancelled_at IS NOT NULL AND cancelled_by IS NOT NULL))
);
CREATE INDEX idx_quotations_customer ON quotations (customer_id);
CREATE INDEX idx_quotations_status ON quotations (status);

-- A line: whole sheets (SHEET), a size to cut (CUSTOM_PIECE) or processing on a size (SERVICE).
-- The list price and the discount give the price; the amount is whole RWF, VAT included.
CREATE TABLE quotation_lines (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version            BIGINT         NOT NULL DEFAULT 0,
    quotation_id       UUID           NOT NULL REFERENCES quotations(id),
    line_no            INTEGER        NOT NULL,
    kind               VARCHAR(12)    NOT NULL,
    parent_line_id     UUID           REFERENCES quotation_lines(id) DEFERRABLE INITIALLY DEFERRED,  -- a size and its processing go together
    product_id         UUID           NOT NULL REFERENCES products(id),
    service_id         UUID           REFERENCES processing_services(id),
    width_mm           INTEGER        NOT NULL,
    height_mm          INTEGER        NOT NULL,
    quantity           INTEGER        NOT NULL,
    holes              INTEGER,
    processing         VARCHAR(200),
    mark               VARCHAR(60),
    chargeable_area_m2 NUMERIC(10,4),                 -- glass: per piece
    service_quantity   NUMERIC(10,4),                 -- processing: m², metres, pieces or holes
    list_price         NUMERIC(18,2)  NOT NULL,       -- per m² (glass) or per unit (processing)
    discount_percent   NUMERIC(5,2)   NOT NULL DEFAULT 0,
    price              NUMERIC(18,2)  NOT NULL,
    price_list_id      UUID           NOT NULL REFERENCES price_lists(id),
    prices_include_vat BOOLEAN        NOT NULL,
    tax_code           VARCHAR(1)     NOT NULL,
    vat_rate           NUMERIC(5,2)   NOT NULL,
    amount             NUMERIC(18,2)  NOT NULL,
    created_at         TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by         VARCHAR(50),
    updated_at         TIMESTAMP,
    updated_by         VARCHAR(50),
    CONSTRAINT uk_quotation_lines_no UNIQUE (quotation_id, line_no) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT chk_quotation_lines_kind CHECK (kind IN ('SHEET', 'CUSTOM_PIECE', 'SERVICE')),
    CONSTRAINT chk_quotation_lines_size CHECK (width_mm > 0 AND height_mm > 0 AND quantity > 0),
    CONSTRAINT chk_quotation_lines_service CHECK ((kind = 'SERVICE') = (service_id IS NOT NULL AND parent_line_id IS NOT NULL)),
    CONSTRAINT chk_quotation_lines_priced CHECK (kind = 'SERVICE' OR chargeable_area_m2 > 0),
    CONSTRAINT chk_quotation_lines_amount CHECK (list_price >= 0 AND price >= 0 AND amount >= 0 AND vat_rate >= 0
        AND discount_percent >= 0 AND discount_percent <= 100),
    CONSTRAINT chk_quotation_lines_holes CHECK (holes IS NULL OR holes > 0)
);
CREATE INDEX idx_quotation_lines_quotation ON quotation_lines (quotation_id);

-- The sale a quotation was rung up into (it becomes CONVERTED when that sale is paid)
ALTER TABLE sales_invoices ADD COLUMN quotation_id UUID REFERENCES quotations(id);
CREATE INDEX idx_sales_invoices_quotation ON sales_invoices (quotation_id) WHERE quotation_id IS NOT NULL;

-- How long a quotation's prices hold by default (POS-03)
INSERT INTO settings (setting_key, setting_value, created_by) VALUES
('sales.quotation-validity-days', '14', 'system');

-- ---------------------------------------------------------------------
-- Pages, permissions, grants (SRS 2.2: the cashier quotes at the counter;
-- the owner too; the accountant and auditor read quotations)
-- ---------------------------------------------------------------------
INSERT INTO pages (code, name, module, path, icon, sort_order) VALUES
('QUOTATIONS', 'Quotations', 'Sales & POS', '/quotations', 'file-text', 6);

INSERT INTO permissions (code, name, module, action, description) VALUES
('VIEW_QUOTATION',   'View Quotations',   'Sales', 'VIEW',   'View quotations and print them'),
('MANAGE_QUOTATION', 'Manage Quotations', 'Sales', 'MANAGE', 'Create, edit, send and cancel quotations');

INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE p.code = 'QUOTATIONS' AND r.code IN ('ADMIN', 'CASHIER', 'OWNER', 'ACCOUNTANT', 'AUDITOR')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE (p.code = 'VIEW_QUOTATION' AND r.code IN ('ADMIN', 'CASHIER', 'OWNER', 'ACCOUNTANT', 'AUDITOR'))
   OR (p.code = 'MANAGE_QUOTATION' AND r.code IN ('ADMIN', 'CASHIER', 'OWNER'))
ON CONFLICT DO NOTHING;
