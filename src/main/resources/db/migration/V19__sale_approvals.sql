-- =====================================================================
-- V19: approvals at the counter (POS-05, POS-06, AUD: approvals log the
-- requester, approver, reason and the before/after values).
--  - a role's discount limit: the largest discount (or price cut) its users
--    give without approval; empty = the Settings value DISCOUNT_APPROVAL_PERCENT
--  - sale_approvals: a price change above the cashier's limit, or customer
--    credit above what the customer has left, waiting for a manager
--  - sale lines keep the list price and the reason when their price changed
-- =====================================================================

ALTER TABLE roles ADD COLUMN discount_limit_percent NUMERIC(5,2);
ALTER TABLE roles ADD CONSTRAINT chk_roles_discount_limit
    CHECK (discount_limit_percent IS NULL OR (discount_limit_percent >= 0 AND discount_limit_percent <= 100));

-- The owner gives any discount (SRS 2.2: the owner approves price overrides)
UPDATE roles SET discount_limit_percent = 100 WHERE code = 'OWNER';

CREATE TABLE sale_approvals (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version          BIGINT         NOT NULL DEFAULT 0,
    number           VARCHAR(30)    NOT NULL,          -- APR-WH-2026-000001
    kind             VARCHAR(10)    NOT NULL,          -- PRICE (discount or price change), CREDIT (over the limit)
    status           VARCHAR(10)    NOT NULL,
    invoice_id       UUID           NOT NULL REFERENCES sales_invoices(id),
    line_id          UUID           REFERENCES sales_invoice_lines(id),   -- PRICE: the line (cleared if it leaves the sale)
    customer_id      UUID           NOT NULL REFERENCES customers(id),
    subject          VARCHAR(200)   NOT NULL,          -- what it is about: the line, or the customer
    -- PRICE: the list price and the price asked (per m² or per unit of the service), the discount, the line before/after
    list_price       NUMERIC(18,2),
    requested_price  NUMERIC(18,2),
    discount_percent NUMERIC(5,2),
    limit_percent    NUMERIC(5,2),                     -- the requester's limit then
    amount_before    NUMERIC(18,2),
    amount_after     NUMERIC(18,2),
    -- CREDIT: the limit and what the customer owed then, the credit asked on this sale
    credit_limit     NUMERIC(18,2),
    owed             NUMERIC(18,2),
    credit_amount    NUMERIC(18,2),
    reason           VARCHAR(200)   NOT NULL,
    requested_by     VARCHAR(50)    NOT NULL,
    requested_by_id  BIGINT,
    decided_by       VARCHAR(50),
    decided_at       TIMESTAMP,
    decision_note    VARCHAR(200),                     -- why rejected or withdrawn, or the approver's note
    created_at       TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by       VARCHAR(50),
    updated_at       TIMESTAMP,
    updated_by       VARCHAR(50),
    CONSTRAINT uk_sale_approvals_number UNIQUE (number),
    CONSTRAINT chk_sale_approvals_kind CHECK (kind IN ('PRICE', 'CREDIT')),
    CONSTRAINT chk_sale_approvals_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN')),
    CONSTRAINT chk_sale_approvals_price CHECK (kind <> 'PRICE' OR (list_price > 0 AND requested_price > 0
        AND discount_percent > 0 AND discount_percent <= 100 AND limit_percent >= 0 AND amount_before >= 0 AND amount_after >= 0)),
    CONSTRAINT chk_sale_approvals_credit CHECK (kind <> 'CREDIT' OR (credit_limit >= 0 AND owed IS NOT NULL AND credit_amount > 0)),
    CONSTRAINT chk_sale_approvals_decided CHECK (status = 'PENDING' OR (decided_by IS NOT NULL AND decided_at IS NOT NULL)),
    CONSTRAINT chk_sale_approvals_note CHECK (status NOT IN ('REJECTED', 'WITHDRAWN') OR decision_note IS NOT NULL)
);
CREATE INDEX idx_sale_approvals_invoice ON sale_approvals (invoice_id);
CREATE INDEX idx_sale_approvals_pending ON sale_approvals (status) WHERE status = 'PENDING';

-- A line whose price was changed: the list price it replaced and why (POS-06)
ALTER TABLE sales_invoice_lines ADD COLUMN list_price NUMERIC(18,2);
ALTER TABLE sales_invoice_lines ADD COLUMN price_reason VARCHAR(200);
ALTER TABLE sales_invoice_lines ADD CONSTRAINT chk_sales_invoice_lines_override
    CHECK ((list_price IS NULL) = (price_reason IS NULL) AND (list_price IS NULL OR list_price > 0));

INSERT INTO number_sequences (doc_type, branch_code, prefix, created_by) VALUES
('SALE_APPROVAL', 'WH', 'APR', 'system');

-- ---------------------------------------------------------------------
-- Pages, permissions, grants (SRS 2.2: the owner approves; the cashier sees
-- their requests; the accountant and auditor read them)
-- ---------------------------------------------------------------------
INSERT INTO pages (code, name, module, path, icon, sort_order) VALUES
('SALE_APPROVALS', 'Sale Approvals', 'Sales & POS', '/sale-approvals', 'check', 7);

INSERT INTO permissions (code, name, module, action, description) VALUES
('APPROVE_SALE', 'Approve Sale Discounts and Credit', 'Sales', 'APPROVE',
 'Approve or reject price changes above the cashier''s limit and credit above the customer''s limit (not your own)');

INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE p.code = 'SALE_APPROVALS' AND r.code IN ('ADMIN', 'CASHIER', 'OWNER', 'ACCOUNTANT', 'AUDITOR')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE p.code = 'APPROVE_SALE' AND r.code IN ('ADMIN', 'OWNER')
ON CONFLICT DO NOTHING;
