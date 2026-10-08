package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : StockCountStatus.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Life of a stock count (INV-08). Open while labels are scanned (the units on its places
 *               are held); closed when its result is recorded; cancelled with a reason while open.
 * </pre>
 */
public enum StockCountStatus {

    OPEN,
    CLOSED,
    CANCELLED
}
