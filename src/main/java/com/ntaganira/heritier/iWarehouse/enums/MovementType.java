package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : MovementType.java
 * - Date      : 2026. 10. 08.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
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
    COUNT,
    /** Sold at the counter: the unit leaves stock (POS-01). */
    SALE,
    /** Brought back by the customer on a credit note (POS-09): back in stock, or cullet. */
    RETURN,
    /** Loaded on a vehicle when its trip departed (FLT-07): off its rack, ON_VEHICLE in the driver's charge. */
    LOAD,
    /** Back off a vehicle onto a rack at the end of the day, or found after it (FLT-09, FLT-10): AVAILABLE again. */
    UNLOAD,
    /** Broken on the road, the breakage accepted by the supervisor (FLT-08): BROKEN. */
    BREAKAGE,
    /** Missing after a trip and written off or charged to the driver (FLT-10, FLT-11): LOST. */
    LOSS,
    /** Sent to another site on a transfer (IMP 11.2): AVAILABLE to IN_TRANSIT. */
    DISPATCH,
    /** Arrived at the receiving site, its label scanned (IMP 11.2): IN_TRANSIT to AVAILABLE on a rack there. */
    ARRIVAL
}
