package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : ReturnOutcome.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : Where glass brought back goes (POS-09): back in stock, available on a rack at its own cost, or to
 *               cullet (broken or unsellable: BROKEN, its cost moves from cost of goods sold to spoilage).
 * </pre>
 */
public enum ReturnOutcome {

    RESTOCK,
    CULLET
}
