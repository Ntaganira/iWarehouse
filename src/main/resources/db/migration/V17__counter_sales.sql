-- =====================================================================
-- V17: Counter sales, part 1 (SRS 4.6, 4.10: POS-01, POS-04, POS-10,
-- TAX-01, TAX-04; AT-08)
--
-- A cashier works in a till session: opened with a float, closed with
-- the cash counted; the difference is recorded with a reason. The sale
-- being rung up is a draft invoice of the session: its units are held
-- until it is paid or cancelled. Paying issues the invoice (INV number),
-- the units leave stock as SOLD and one journal posts the payments, the
-- sales revenue and VAT output, and the cost of the glass at MAC.
-- Amounts are RWF: line amounts in whole francs including VAT; VAT is
-- worked out per tax letter on the invoice totals.
-- =====================================================================

ALTER TABLE stock_movements DROP CONSTRAINT chk_stock_movements_type;
ALTER TABLE stock_movements ADD CONSTRAINT chk_stock_movements_type CHECK (movement_type IN
    ('RECEIPT', 'CUTTING_START', 'CUTTING_RELEASE', 'CUTTING_CONSUMED', 'CUTTING_OUTPUT',
     'TRANSFER', 'ADJUSTMENT', 'RESERVE', 'RELEASE', 'COUNT', 'SALE'));

CREATE TABLE till_sessions (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version           BIGINT        NOT NULL DEFAULT 0,
    number            VARCHAR(30)   NOT NULL,           -- TILL-WH-2026-000001
    cashier_id        BIGINT        NOT NULL REFERENCES users(id),
    cashier_username  VARCHAR(50)   NOT NULL,
    status            VARCHAR(10)   NOT NULL,
    opened_at         TIMESTAMP     NOT NULL,
    opening_float     NUMERIC(18,2) NOT NULL,
    closed_at         TIMESTAMP,
    cash_sales        NUMERIC(18,2),                    -- cash kept from sales (tendered less change)
    expected_cash     NUMERIC(18,2),                    -- float + cash sales
    counted_cash      NUMERIC(18,2),
    difference        NUMERIC(18,2),                    -- counted less expected: negative = short
    close_note        VARCHAR(255),
    created_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by        VARCHAR(50),
    updated_at        TIMESTAMP,
    updated_by        VARCHAR(50),
    CONSTRAINT uk_till_sessions_number UNIQUE (number),
    CONSTRAINT chk_till_sessions_status CHECK (status IN ('OPEN', 'CLOSED')),
    CONSTRAINT chk_till_sessions_float CHECK (opening_float >= 0),
    CONSTRAINT chk_till_sessions_closed CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL AND cash_sales IS NOT NULL
        AND expected_cash IS NOT NULL AND counted_cash IS NOT NULL AND difference IS NOT NULL)),
    CONSTRAINT chk_till_sessions_counted CHECK (counted_cash IS NULL OR counted_cash >= 0)
);
-- One open till per cashier
CREATE UNIQUE INDEX uk_till_sessions_open_cashier ON till_sessions (cashier_id) WHERE status = 'OPEN';
CREATE INDEX idx_till_sessions_opened ON till_sessions (opened_at);

CREATE TABLE sales_invoices (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version           BIGINT        NOT NULL DEFAULT 0,
    number            VARCHAR(30),                      -- INV-WH-2026-000001, given when paid
    status            VARCHAR(10)   NOT NULL,
    till_session_id   UUID          NOT NULL REFERENCES till_sessions(id),
    customer_id       UUID          NOT NULL REFERENCES customers(id),
    buyer_name        VARCHAR(100),                     -- as printed; a walk-in may give a name
    buyer_tin         VARCHAR(9),                       -- TAX-04
    invoice_date      DATE,
    net_amount        NUMERIC(18,2),
    vat_amount        NUMERIC(18,2),
    total_amount      NUMERIC(18,2),
    cash_tendered     NUMERIC(18,2),
    change_given      NUMERIC(18,2),
    posted_at         TIMESTAMP,
    posted_by         VARCHAR(50),
    created_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by        VARCHAR(50),
    updated_at        TIMESTAMP,
    updated_by        VARCHAR(50),
    CONSTRAINT uk_sales_invoices_number UNIQUE (number),
    CONSTRAINT chk_sales_invoices_status CHECK (status IN ('DRAFT', 'POSTED', 'CANCELLED')),
    CONSTRAINT chk_sales_invoices_posted CHECK ((status = 'POSTED') = (number IS NOT NULL AND invoice_date IS NOT NULL
        AND posted_at IS NOT NULL AND net_amount IS NOT NULL AND vat_amount IS NOT NULL AND total_amount IS NOT NULL)),
    CONSTRAINT chk_sales_invoices_totals CHECK (total_amount IS NULL OR (total_amount >= 0 AND total_amount = net_amount + vat_amount)),
    CONSTRAINT chk_sales_invoices_tin CHECK (buyer_tin IS NULL OR buyer_tin ~ '^[0-9]{9}$')
);
CREATE INDEX idx_sales_invoices_session ON sales_invoices (till_session_id);
CREATE INDEX idx_sales_invoices_customer ON sales_invoices (customer_id);
-- One sale being rung up per till
CREATE UNIQUE INDEX uk_sales_invoices_draft ON sales_invoices (till_session_id) WHERE status = 'DRAFT';

CREATE TABLE sales_invoice_lines (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version             BIGINT        NOT NULL DEFAULT 0,
    invoice_id          UUID          NOT NULL REFERENCES sales_invoices(id),
    line_no             INTEGER       NOT NULL,
    kind                VARCHAR(12)   NOT NULL,
    stock_unit_id       UUID          REFERENCES stock_units(id),
    unit_code           VARCHAR(30),
    product_id          UUID          NOT NULL REFERENCES products(id),
    width_mm            INTEGER       NOT NULL,
    height_mm           INTEGER       NOT NULL,
    quantity            INTEGER       NOT NULL DEFAULT 1,
    chargeable_area_m2  NUMERIC(10,4) NOT NULL,          -- per piece, at least the list's minimum (MD-06)
    price_per_m2        NUMERIC(18,2) NOT NULL,
    price_list_id       UUID          NOT NULL REFERENCES price_lists(id),
    prices_include_vat  BOOLEAN       NOT NULL,
    tax_code            VARCHAR(1)    NOT NULL,          -- EBM letter: A exempt, B standard, C zero-rated
    vat_rate            NUMERIC(5,2)  NOT NULL,
    amount              NUMERIC(18,2) NOT NULL,          -- whole RWF, VAT included
    created_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          VARCHAR(50),
    updated_at          TIMESTAMP,
    updated_by          VARCHAR(50),
    CONSTRAINT uk_sales_invoice_lines_no UNIQUE (invoice_id, line_no) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT chk_sales_invoice_lines_kind CHECK (kind IN ('STOCK_UNIT')),
    CONSTRAINT chk_sales_invoice_lines_unit CHECK (kind <> 'STOCK_UNIT' OR (stock_unit_id IS NOT NULL AND unit_code IS NOT NULL AND quantity = 1)),
    CONSTRAINT chk_sales_invoice_lines_size CHECK (width_mm > 0 AND height_mm > 0 AND quantity > 0 AND chargeable_area_m2 > 0),
    CONSTRAINT chk_sales_invoice_lines_amount CHECK (price_per_m2 >= 0 AND amount >= 0 AND vat_rate >= 0)
);
CREATE INDEX idx_sales_invoice_lines_invoice ON sales_invoice_lines (invoice_id);
CREATE INDEX idx_sales_invoice_lines_unit ON sales_invoice_lines (stock_unit_id);

-- The lines of an issued invoice never change: a correction is a credit note (POS-09)
CREATE OR REPLACE FUNCTION forbid_posted_invoice_line_change() RETURNS TRIGGER AS $$
BEGIN
    IF (SELECT status FROM sales_invoices WHERE id = OLD.invoice_id) = 'POSTED' THEN
        RAISE EXCEPTION 'Invoice lines are fixed once the invoice is issued: correct it with a credit note';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_sales_invoice_lines_posted BEFORE UPDATE OR DELETE ON sales_invoice_lines
    FOR EACH ROW EXECUTE FUNCTION forbid_posted_invoice_line_change();

CREATE TABLE sales_payments (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    invoice_id    UUID          NOT NULL REFERENCES sales_invoices(id),
    line_no       INTEGER       NOT NULL,
    method        VARCHAR(15)   NOT NULL,
    amount        NUMERIC(18,2) NOT NULL,
    reference     VARCHAR(60),                          -- mobile money, card or transfer reference
    created_at    TIMESTAMP     NOT NULL,
    username      VARCHAR(50)   NOT NULL,
    CONSTRAINT uk_sales_payments_no UNIQUE (invoice_id, line_no),
    CONSTRAINT chk_sales_payments_method CHECK (method IN ('CASH', 'MOBILE_MONEY', 'CARD', 'BANK_TRANSFER', 'CREDIT')),
    CONSTRAINT chk_sales_payments_amount CHECK (amount > 0),
    CONSTRAINT chk_sales_payments_reference CHECK (method IN ('CASH', 'CREDIT') OR reference IS NOT NULL)
);
CREATE INDEX idx_sales_payments_invoice ON sales_payments (invoice_id);
CREATE TRIGGER trg_sales_payments_append_only BEFORE UPDATE OR DELETE ON sales_payments
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_modification();

-- Receivable lines name their customer (the credit a customer uses, POS-05)
ALTER TABLE journal_lines ADD COLUMN customer_id UUID REFERENCES customers(id);
CREATE INDEX idx_journal_lines_customer ON journal_lines (customer_id) WHERE customer_id IS NOT NULL;

ALTER TABLE journal_entries DROP CONSTRAINT chk_journal_entries_source;
ALTER TABLE journal_entries ADD CONSTRAINT chk_journal_entries_source CHECK (source_type IN ('GOODS_RECEIPT', 'SHIPMENT',
    'CLAIM_OPENED', 'CLAIM_SETTLED', 'CLAIM_REJECTED', 'CUTTING_JOB', 'ADJUSTMENT', 'OPENING_STOCK',
    'SALES_INVOICE', 'TILL_OPENED', 'TILL_CLOSED'));

INSERT INTO number_sequences (doc_type, branch_code, prefix, created_by) VALUES
('TILL_SESSION', 'WH', 'TILL', 'system');

-- ---------------------------------------------------------------------
-- Pages, permissions, grants (SRS 2.2: the cashier sells at the counter;
-- the accountant, owner and auditor read invoices and till sessions)
-- ---------------------------------------------------------------------
INSERT INTO pages (code, name, module, path, icon, sort_order) VALUES
('TILL_SESSIONS', 'Till Sessions', 'Sales & POS', '/till-sessions', 'clock', 7);

INSERT INTO permissions (code, name, module, action, description) VALUES
('SELL',              'Sell at the Counter', 'Sales', 'SELL', 'Open and close a till, ring up and take payment for sales'),
('VIEW_INVOICE',      'View Invoices',       'Sales', 'VIEW', 'View sales invoices and print their receipts'),
('VIEW_TILL_SESSION', 'View Till Sessions',  'Sales', 'VIEW', 'View till sessions, their cash and differences');

INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE r.code = 'ADMIN' AND p.code = 'TILL_SESSIONS'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code = 'ADMIN' AND p.code IN ('SELL', 'VIEW_INVOICE', 'VIEW_TILL_SESSION')
ON CONFLICT DO NOTHING;

INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE (p.code = 'POS' AND r.code = 'CASHIER')
   OR (p.code IN ('INVOICES', 'TILL_SESSIONS') AND r.code IN ('CASHIER', 'ACCOUNTANT', 'OWNER', 'AUDITOR'))
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE (p.code = 'SELL' AND r.code = 'CASHIER')
   OR (p.code IN ('VIEW_INVOICE', 'VIEW_TILL_SESSION') AND r.code IN ('CASHIER', 'ACCOUNTANT', 'OWNER', 'AUDITOR'))
ON CONFLICT DO NOTHING;
