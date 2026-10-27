package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : WriteOffStatus.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Life of a receivable write-off (F-14): reviewed when Settings ask for it, then approved by another
 *               person within the Delegation of Authority, which posts it; or rejected, or withdrawn, with a reason.
 * </pre>
 */
public enum WriteOffStatus {

    PENDING_REVIEW,
    PENDING_APPROVAL,
    POSTED,
    REJECTED,
    WITHDRAWN;

    public boolean isWaiting() {
        return this == PENDING_REVIEW || this == PENDING_APPROVAL;
    }
}
