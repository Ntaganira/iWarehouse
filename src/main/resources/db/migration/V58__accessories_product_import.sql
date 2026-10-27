-- =====================================================================
-- V58: Catalogue-only accessories and product import (owner, 2026-10-27)
--
-- An accessory is sold from the catalogue like processing (a service
-- line) but is not processing: it is added to a sale on its own, with no
-- parent size and no glass product, and it never moves stock. The kind
-- keeps accessories out of the processing pickers, quotations and
-- cutting jobs. A product import may create these catalogue rows and
-- their default-list prices, but no stock units.
-- =====================================================================

ALTER TABLE processing_services ADD COLUMN kind VARCHAR(12) NOT NULL DEFAULT 'PROCESSING';
ALTER TABLE processing_services ADD CONSTRAINT chk_processing_services_kind
    CHECK (kind IN ('PROCESSING', 'ACCESSORY'));

ALTER TABLE processing_services DROP CONSTRAINT chk_processing_services_unit;
ALTER TABLE processing_services ADD CONSTRAINT chk_processing_services_unit
    CHECK (charge_unit IN ('M2', 'METRE', 'PIECE', 'HOLE', 'ROLL', 'TUBE', 'KG', 'LITRE', 'SET', 'BOX', 'PACK', 'BAG'));

-- A standalone accessory is a SERVICE line with no parent size and no
-- glass product. Every other kind still names its product.
ALTER TABLE sales_invoice_lines ALTER COLUMN product_id DROP NOT NULL;
ALTER TABLE sales_invoice_lines ADD CONSTRAINT chk_sales_invoice_lines_product
    CHECK (product_id IS NOT NULL OR kind = 'SERVICE');

ALTER TABLE sales_invoice_lines DROP CONSTRAINT chk_sales_invoice_lines_service;
ALTER TABLE sales_invoice_lines ADD CONSTRAINT chk_sales_invoice_lines_service
    CHECK ((kind = 'SERVICE') = (service_id IS NOT NULL)
        AND (kind <> 'SERVICE' OR (service_quantity > 0 AND service_unit_price >= 0
            AND chargeable_area_m2 IS NULL AND price_per_m2 IS NULL)));

-- A service line (processing or a standalone accessory) has no m2 of glass; the glass lines still do
-- (chk_sales_invoice_lines_priced), so the size check lets a service line leave chargeable_area_m2 null.
ALTER TABLE sales_invoice_lines DROP CONSTRAINT chk_sales_invoice_lines_size;
ALTER TABLE sales_invoice_lines ADD CONSTRAINT chk_sales_invoice_lines_size
    CHECK (width_mm > 0 AND height_mm > 0 AND quantity > 0
        AND (chargeable_area_m2 IS NULL OR chargeable_area_m2 > 0));
