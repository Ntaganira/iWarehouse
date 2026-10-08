package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : CostEntryType.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Why a stock unit's cost changed: its receipt cost at the PO price, a landed cost
 *               (freight, duty, clearing...) added by a shipment posting (PRC-05), or its part of the
 *               cost of the unit it was cut from (PRD-07). Each module that changes costs adds its type
 *               here and to chk_stock_cost_entries_type in its migration.
 * </pre>
 */
public enum CostEntryType {

    RECEIPT,
    LANDED_COST,
    CUTTING,
    /** A unit added or resized by a stock adjustment (INV-07). */
    ADJUSTMENT
}
