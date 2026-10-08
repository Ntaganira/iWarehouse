package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : MovementType.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Why a stock movement happened (INV-04). Each module that moves units adds its type here
 *               and to chk_stock_movements_type in its migration (transfers, cutting, sales, loading...).
 * </pre>
 */
public enum MovementType {

    /** Created on its rack by a posted goods receipt (PRC-02). */
    RECEIPT
}
