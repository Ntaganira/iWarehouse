-- =====================================================================
-- V21: deposits on orders (POS-08, SRS 5.3). A sale with sizes to cut
-- may be paid in part: the invoice is issued for its whole amount, the
-- deposit is what was paid and the rest is the balance due, owed by the
-- customer (Accounts Receivable) until it is paid at collection, in the
-- till of whoever takes it. Pieces are handed over only once it is paid.
-- =====================================================================

-- What the customer still owes on an issued invoice (0 once paid in full)
ALTER TABLE sales_invoices ADD COLUMN balance_due NUMERIC(18,2);
UPDATE sales_invoices SET balance_due = 0 WHERE status = 'POSTED';
ALTER TABLE sales_invoices ADD CONSTRAINT chk_sales_invoices_balance CHECK ((status = 'POSTED') = (balance_due IS NOT NULL)
    AND (balance_due IS NULL OR (balance_due >= 0 AND balance_due <= total_amount)));
CREATE INDEX idx_sales_invoices_balance ON sales_invoices (posted_at) WHERE balance_due > 0;

-- A payment belongs to the till that took it: the balance is often paid in
-- another till than the sale. Existing rows were all taken by their
-- invoice's till: the column is filled once from it (the append-only
-- trigger is off for this one statement; no amount or method changes).
ALTER TABLE sales_payments ADD COLUMN till_session_id UUID REFERENCES till_sessions(id);
ALTER TABLE sales_payments DISABLE TRIGGER trg_sales_payments_append_only;
UPDATE sales_payments p SET till_session_id = i.till_session_id FROM sales_invoices i WHERE i.id = p.invoice_id;
ALTER TABLE sales_payments ENABLE TRIGGER trg_sales_payments_append_only;
ALTER TABLE sales_payments ALTER COLUMN till_session_id SET NOT NULL;
CREATE INDEX idx_sales_payments_till ON sales_payments (till_session_id);

-- A payment of the balance (after the invoice was issued), and for cash
-- what was handed over (the change is the difference)
ALTER TABLE sales_payments ADD COLUMN balance_payment BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE sales_payments ADD COLUMN cash_tendered NUMERIC(18,2);
ALTER TABLE sales_payments ADD CONSTRAINT chk_sales_payments_tendered CHECK (cash_tendered IS NULL
    OR (method = 'CASH' AND cash_tendered >= amount));

ALTER TABLE journal_entries DROP CONSTRAINT chk_journal_entries_source;
ALTER TABLE journal_entries ADD CONSTRAINT chk_journal_entries_source CHECK (source_type IN ('GOODS_RECEIPT', 'SHIPMENT',
    'CLAIM_OPENED', 'CLAIM_SETTLED', 'CLAIM_REJECTED', 'CUTTING_JOB', 'ADJUSTMENT', 'OPENING_STOCK',
    'SALES_INVOICE', 'TILL_OPENED', 'TILL_CLOSED', 'SALES_DELIVERY', 'SALES_BALANCE'));

-- The smallest deposit, as a percentage of the sale (the glass taken from
-- stock at once is always paid in full)
INSERT INTO settings (setting_key, setting_value, created_by) VALUES
('sales.deposit-min-percent', '50', 'system');
