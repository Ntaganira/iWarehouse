package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : WriteOffCause.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Why a unit is written off (INV-07): damaged in the warehouse (it becomes BROKEN, to
 *               spoilage) or missing (it becomes LOST and can be found again).
 * </pre>
 */
public enum WriteOffCause {

    DAMAGED,
    MISSING;

    public StockStatus status() {
        return this == DAMAGED ? StockStatus.BROKEN : StockStatus.LOST;
    }
}
