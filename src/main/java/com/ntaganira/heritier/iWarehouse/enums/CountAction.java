package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : CountAction.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : What closing a stock count did about one unit (INV-08): moved it to where it was found,
 *               put it on the count's adjustment (missing: written off; lost: found), or nothing.
 * </pre>
 */
public enum CountAction {

    NONE,
    MOVED,
    ADJUSTMENT
}
