package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : TicketKind.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : What a Transaction Ticket covers (decision D6): every stock movement document but sales, which answer to
 *               their paid invoice.
 * </pre>
 */
public enum TicketKind {

    /** A goods receipt posted (or waiting for its ticket to post). */
    RECEIPT,
    /** Glass moved between racks. */
    TRANSFER,
    /** Glass sent to another site. */
    SITE_DISPATCH,
    /** Glass arrived from another site. */
    SITE_ARRIVAL,
    /** Glass loaded on a vehicle. */
    LOAD,
    /** Glass back from a vehicle, or broken on the road. */
    RETURN,
    /** Glass written off, found, added or resized. */
    ADJUSTMENT,
    /** Glass brought back by a customer on a credit note. */
    CUSTOMER_RETURN
}
