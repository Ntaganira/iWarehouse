-- =====================================================================
-- V22: returns and credit notes (POS-09). A credit note is issued against
-- an issued invoice for the glass the customer brings back: sheets sold
-- from stock and pieces handed over, each unit once. Each unit goes back
-- to stock (available on a rack, at its own cost) or to cullet. Each
-- invoice line is credited for its share of what came back, VAT per tax
-- letter. The credit first reduces the invoice's balance due; the rest is
-- refunded in cash from the till, by mobile money, card or transfer, or to
-- the customer's account. Posted when saved, never changed: its lines and
-- units are append-only.
-- =====================================================================

ALTER TABLE stock_movements DROP CONSTRAINT chk_stock_movements_type;
ALTER TABLE stock_movements ADD CONSTRAINT chk_stock_movements_type CHECK (movement_type IN
    ('RECEIPT', 'CUTTING_START', 'CUTTING_RELEASE', 'CUTTING_CONSUMED', 'CUTTING_OUTPUT',
     'TRANSFER', 'ADJUSTMENT', 'RESERVE', 'RELEASE', 'COUNT', 'SALE', 'RETURN'));

CREATE TABLE credit_notes (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version           BIGINT         NOT NULL DEFAULT 0,
    number            VARCHAR(30)    NOT NULL,          -- CN-WH-2026-000001
    invoice_id        UUID           NOT NULL REFERENCES sales_invoices(id),
    customer_id       UUID           NOT NULL REFERENCES customers(id),
    credit_date       DATE           NOT NULL,
    reason            VARCHAR(255)   NOT NULL,
    net_amount        NUMERIC(18,2)  NOT NULL,
    vat_amount        NUMERIC(18,2)  NOT NULL,
    total_amount      NUMERIC(18,2)  NOT NULL,
    balance_reduced   NUMERIC(18,2)  NOT NULL DEFAULT 0, -- the part that reduced the invoice's balance due
    refund_method     VARCHAR(15),                      -- how the rest went back; CREDIT = to the customer's account
    refund_amount     NUMERIC(18,2)  NOT NULL,
    refund_reference  VARCHAR(60),
    till_session_id   UUID           REFERENCES till_sessions(id), -- a cash refund: the till it left
    posted_at         TIMESTAMP      NOT NULL,
    posted_by         VARCHAR(50)    NOT NULL,
    created_at        TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by        VARCHAR(50),
    updated_at        TIMESTAMP,
    updated_by        VARCHAR(50),
    CONSTRAINT uk_credit_notes_number UNIQUE (number),
    CONSTRAINT chk_credit_notes_totals CHECK (total_amount > 0 AND total_amount = net_amount + vat_amount),
    CONSTRAINT chk_credit_notes_refund CHECK (balance_reduced >= 0 AND refund_amount >= 0
        AND refund_amount + balance_reduced = total_amount),
    CONSTRAINT chk_credit_notes_method CHECK ((refund_amount > 0) = (refund_method IS NOT NULL)
        AND (refund_method IS NULL OR refund_method IN ('CASH', 'MOBILE_MONEY', 'CARD', 'BANK_TRANSFER', 'CREDIT'))),
    CONSTRAINT chk_credit_notes_reference CHECK (refund_method IS NULL OR refund_method IN ('CASH', 'CREDIT')
        OR refund_reference IS NOT NULL),
    CONSTRAINT chk_credit_notes_till CHECK ((refund_method = 'CASH') = (till_session_id IS NOT NULL))
);
CREATE INDEX idx_credit_notes_invoice ON credit_notes (invoice_id);
CREATE INDEX idx_credit_notes_till ON credit_notes (till_session_id) WHERE till_session_id IS NOT NULL;
CREATE INDEX idx_credit_notes_posted ON credit_notes (posted_at);

-- What each invoice line is credited: its share of the pieces back, VAT included (whole RWF)
CREATE TABLE credit_note_lines (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    credit_note_id    UUID           NOT NULL REFERENCES credit_notes(id),
    line_no           INTEGER        NOT NULL,
    invoice_line_id   UUID           NOT NULL REFERENCES sales_invoice_lines(id),
    quantity          INTEGER        NOT NULL,          -- units (pieces) back
    tax_code          VARCHAR(1)     NOT NULL,
    vat_rate          NUMERIC(5,2)   NOT NULL,
    amount            NUMERIC(18,2)  NOT NULL,
    CONSTRAINT uk_credit_note_lines_no UNIQUE (credit_note_id, line_no),
    CONSTRAINT chk_credit_note_lines_values CHECK (quantity > 0 AND amount >= 0 AND vat_rate >= 0)
);
CREATE INDEX idx_credit_note_lines_note ON credit_note_lines (credit_note_id);
CREATE INDEX idx_credit_note_lines_invoice_line ON credit_note_lines (invoice_line_id);

-- Each unit brought back and where it went; a unit comes back once per invoice
CREATE TABLE credit_note_units (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    credit_note_id    UUID           NOT NULL REFERENCES credit_notes(id),
    invoice_id        UUID           NOT NULL REFERENCES sales_invoices(id),
    invoice_line_id   UUID           NOT NULL REFERENCES sales_invoice_lines(id),
    stock_unit_id     UUID           NOT NULL REFERENCES stock_units(id),
    unit_code         VARCHAR(30)    NOT NULL,
    outcome           VARCHAR(10)    NOT NULL,          -- RESTOCK (back on a rack) or CULLET
    location_id       UUID           REFERENCES locations(id),
    unit_cost         NUMERIC(18,2)  NOT NULL,          -- the cost that came back from cost of goods sold
    CONSTRAINT uk_credit_note_units_unit UNIQUE (invoice_id, stock_unit_id),
    CONSTRAINT chk_credit_note_units_outcome CHECK (outcome IN ('RESTOCK', 'CULLET')),
    CONSTRAINT chk_credit_note_units_location CHECK ((outcome = 'RESTOCK') = (location_id IS NOT NULL))
);
CREATE INDEX idx_credit_note_units_note ON credit_note_units (credit_note_id);

CREATE TRIGGER trg_credit_note_lines_append_only BEFORE UPDATE OR DELETE ON credit_note_lines
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_modification();
CREATE TRIGGER trg_credit_note_units_append_only BEFORE UPDATE OR DELETE ON credit_note_units
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_modification();

-- Cash refunded from a till leaves its drawer: kept on the till when it closes
ALTER TABLE till_sessions ADD COLUMN cash_refunds NUMERIC(18,2);
UPDATE till_sessions SET cash_refunds = 0 WHERE status = 'CLOSED';
ALTER TABLE till_sessions ADD CONSTRAINT chk_till_sessions_refunds CHECK ((status = 'CLOSED') = (cash_refunds IS NOT NULL)
    AND (cash_refunds IS NULL OR cash_refunds >= 0));

ALTER TABLE journal_entries DROP CONSTRAINT chk_journal_entries_source;
ALTER TABLE journal_entries ADD CONSTRAINT chk_journal_entries_source CHECK (source_type IN ('GOODS_RECEIPT', 'SHIPMENT',
    'CLAIM_OPENED', 'CLAIM_SETTLED', 'CLAIM_REJECTED', 'CUTTING_JOB', 'ADJUSTMENT', 'OPENING_STOCK',
    'SALES_INVOICE', 'TILL_OPENED', 'TILL_CLOSED', 'SALES_DELIVERY', 'SALES_BALANCE', 'CREDIT_NOTE'));

-- Numbers: CREDIT_NOTE (CN-WH-2026-000001) was seeded with the other document types in V5

-- ---------------------------------------------------------------------
-- Pages, permissions, grants (SRS 2.2: the cashier takes returns at the
-- counter, the owner too; the accountant and auditor read credit notes)
-- ---------------------------------------------------------------------
INSERT INTO pages (code, name, module, path, icon, sort_order) VALUES
('CREDIT_NOTES', 'Credit Notes', 'Sales & POS', '/credit-notes', 'rotate-ccw', 8);

INSERT INTO permissions (code, name, module, action, description) VALUES
('VIEW_CREDIT_NOTE', 'View Credit Notes', 'Sales', 'VIEW',   'View credit notes and print them'),
('RETURN_SALE',      'Take Returns',      'Sales', 'RETURN', 'Take glass back against an invoice and issue its credit note and refund');

INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE p.code = 'CREDIT_NOTES' AND r.code IN ('ADMIN', 'CASHIER', 'OWNER', 'ACCOUNTANT', 'AUDITOR')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE (p.code = 'VIEW_CREDIT_NOTE' AND r.code IN ('ADMIN', 'CASHIER', 'OWNER', 'ACCOUNTANT', 'AUDITOR'))
   OR (p.code = 'RETURN_SALE' AND r.code IN ('ADMIN', 'CASHIER', 'OWNER'))
ON CONFLICT DO NOTHING;
