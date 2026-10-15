package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : TicketStatus.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : Steps of a Transaction Ticket (F-05, Annex I): raised, verified by Supply Chain, countersigned by
 *               Commercial, posted by Finance; or rejected with a reason.
 * </pre>
 */
public enum TicketStatus {

    RAISED,
    VERIFIED,
    COUNTERSIGNED,
    POSTED,
    REJECTED;

    /** Still waiting for a signature. */
    public boolean isOpen() {
        return this == RAISED || this == VERIFIED || this == COUNTERSIGNED;
    }
}
