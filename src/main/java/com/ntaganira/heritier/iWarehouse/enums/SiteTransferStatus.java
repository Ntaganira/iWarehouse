package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : SiteTransferStatus.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : Life of a transfer between sites (IMP 11): requested, approved (or rejected), dispatched (in transit),
 *               received in full or short; cancelled with a reason before it leaves.
 * </pre>
 */
public enum SiteTransferStatus {

    REQUESTED,
    APPROVED,
    REJECTED,
    CANCELLED,
    IN_TRANSIT,
    RECEIVED,
    RECEIVED_SHORT;

    /** Still open: waiting, on its way. */
    public boolean isOpen() {
        return this == REQUESTED || this == APPROVED || this == IN_TRANSIT;
    }
}
