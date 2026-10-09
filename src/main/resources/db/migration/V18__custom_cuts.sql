-- =====================================================================
-- V18: Counter sales, part 2a: custom cut sizes (SRS 4.6: POS-02; 5.3)
--
-- A sale can order sizes to cut (width x height x quantity, the
-- customer's mark), priced by chargeable area, with processing (edging,
-- drilling...) as service lines priced per m², metre of edge, piece or
-- hole. Paying issues the invoice and creates the cutting jobs, linked to
-- it; the pieces they cut are reserved for the customer and handed over
-- later: each piece handed over is a delivery row, the unit is sold and
-- its cost at MAC posted (SRS 5.3 step 5).
-- =====================================================================

ALTER TABLE sales_invoice_lines DROP CONSTRAINT chk_sales_invoice_lines_kind;
ALTER TABLE sales_invoice_lines ADD CONSTRAINT chk_sales_invoice_lines_kind CHECK (kind IN ('STOCK_UNIT', 'CUSTOM_PIECE', 'SERVICE'));
ALTER TABLE sales_invoice_lines ALTER COLUMN chargeable_area_m2 DROP NOT NULL;
ALTER TABLE sales_invoice_lines ALTER COLUMN price_per_m2 DROP NOT NULL;
ALTER TABLE sales_invoice_lines
    ADD COLUMN parent_line_id      UUID REFERENCES sales_invoice_lines(id),   -- a service line: the size it is done on
    ADD COLUMN service_id          UUID REFERENCES processing_services(id),
    ADD COLUMN service_quantity    NUMERIC(10,4),                              -- m², metres, pieces or holes charged
    ADD COLUMN service_unit_price  NUMERIC(18,2),
    ADD COLUMN holes               INTEGER,                                    -- per piece, for a service charged per hole
    ADD COLUMN processing          VARCHAR(200),                               -- a size: the services' codes, for the cutter
    ADD COLUMN mark                VARCHAR(60);                                -- a size: the customer's mark
ALTER TABLE sales_invoice_lines ADD CONSTRAINT chk_sales_invoice_lines_priced CHECK (kind = 'SERVICE'
    OR (chargeable_area_m2 IS NOT NULL AND price_per_m2 IS NOT NULL));
ALTER TABLE sales_invoice_lines ADD CONSTRAINT chk_sales_invoice_lines_custom CHECK (kind <> 'CUSTOM_PIECE' OR stock_unit_id IS NULL);
ALTER TABLE sales_invoice_lines ADD CONSTRAINT chk_sales_invoice_lines_service CHECK ((kind = 'SERVICE') = (service_id IS NOT NULL)
    AND (kind <> 'SERVICE' OR (parent_line_id IS NOT NULL AND service_quantity > 0 AND service_unit_price >= 0
        AND chargeable_area_m2 IS NULL AND price_per_m2 IS NULL)));
ALTER TABLE sales_invoice_lines ADD CONSTRAINT chk_sales_invoice_lines_holes CHECK (holes IS NULL OR holes > 0);
CREATE INDEX idx_sales_invoice_lines_parent ON sales_invoice_lines (parent_line_id);

-- The cutting jobs of a sale, and which size each job line cuts
ALTER TABLE cutting_jobs ADD COLUMN sales_invoice_id UUID REFERENCES sales_invoices(id);
ALTER TABLE cutting_job_lines ADD COLUMN sales_line_id UUID REFERENCES sales_invoice_lines(id);
CREATE INDEX idx_cutting_jobs_sales_invoice ON cutting_jobs (sales_invoice_id) WHERE sales_invoice_id IS NOT NULL;

-- Pieces of custom sizes handed over to the customer (append-only)
CREATE TABLE sales_deliveries (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    invoice_id     UUID         NOT NULL REFERENCES sales_invoices(id),
    line_id        UUID         NOT NULL REFERENCES sales_invoice_lines(id),
    stock_unit_id  UUID         NOT NULL REFERENCES stock_units(id),
    unit_code      VARCHAR(30)  NOT NULL,
    delivered_at   TIMESTAMP    NOT NULL,
    user_id        BIGINT,
    username       VARCHAR(50)  NOT NULL,
    CONSTRAINT uk_sales_deliveries_unit UNIQUE (stock_unit_id)
);
CREATE INDEX idx_sales_deliveries_invoice ON sales_deliveries (invoice_id);
CREATE TRIGGER trg_sales_deliveries_append_only BEFORE UPDATE OR DELETE ON sales_deliveries
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_modification();

ALTER TABLE journal_entries DROP CONSTRAINT chk_journal_entries_source;
ALTER TABLE journal_entries ADD CONSTRAINT chk_journal_entries_source CHECK (source_type IN ('GOODS_RECEIPT', 'SHIPMENT',
    'CLAIM_OPENED', 'CLAIM_SETTLED', 'CLAIM_REJECTED', 'CUTTING_JOB', 'ADJUSTMENT', 'OPENING_STOCK',
    'SALES_INVOICE', 'TILL_OPENED', 'TILL_CLOSED', 'SALES_DELIVERY'));

-- Handing over the pieces of a sale: at the counter or from the warehouse
INSERT INTO permissions (code, name, module, action, description) VALUES
('DELIVER_SALE', 'Hand Over Sales', 'Sales', 'DELIVER', 'Hand over the cut pieces of a paid sale to the customer');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE p.code = 'DELIVER_SALE' AND r.code IN ('ADMIN', 'CASHIER', 'WAREHOUSE_SUPERVISOR')
ON CONFLICT DO NOTHING;

-- The warehouse supervisor opens the invoices whose pieces are handed over
INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE p.code = 'INVOICES' AND r.code = 'WAREHOUSE_SUPERVISOR'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE p.code = 'VIEW_INVOICE' AND r.code = 'WAREHOUSE_SUPERVISOR'
ON CONFLICT DO NOTHING;
