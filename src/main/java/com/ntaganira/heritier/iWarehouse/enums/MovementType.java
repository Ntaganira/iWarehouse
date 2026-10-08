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
    RECEIPT,
    /** Taken for a cutting job: AVAILABLE to IN_CUTTING (PRD-02). */
    CUTTING_START,
    /** Put back unused: IN_CUTTING to AVAILABLE. */
    CUTTING_RELEASE,
    /** Cut: the source leaves stock, its pieces replace it (PRD-03). */
    CUTTING_CONSUMED,
    /** Created by a cut: a cut piece or an off-cut (PRD-03, PRD-04). */
    CUTTING_OUTPUT,
    /** Moved to another rack or slot by a transfer (INV-07). */
    TRANSFER,
    /** Written off, found again, added or replaced by an adjustment (INV-07). */
    ADJUSTMENT,
    /** Reserved for a customer (INV-05). */
    RESERVE,
    /** Released from a reservation. */
    RELEASE,
    /** Found on another rack or slot by a stock count: its location corrected (INV-08). */
    COUNT
}
