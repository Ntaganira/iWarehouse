package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : SaleLineKind.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : What an invoice line sells: a unit from stock (POS-01), a size to cut (POS-02, cut by a
 *               cutting job and handed over later), or processing done on that size (edging, drilling...).
 *               A new kind = a constant here and chk_sales_invoice_lines_kind.
 * </pre>
 */
public enum SaleLineKind {

    STOCK_UNIT,
    CUSTOM_PIECE,
    SERVICE
}
