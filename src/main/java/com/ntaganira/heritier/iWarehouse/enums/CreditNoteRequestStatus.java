package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : CreditNoteRequestStatus.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : Life of a credit note asked (F-14): reviewed by Finance when Settings ask for it, then approved by
 *               another person within the Delegation of Authority, then issued (the glass comes back, the refund is paid);
 *               or rejected, or withdrawn by whoever asked, with a reason.
 * </pre>
 */
public enum CreditNoteRequestStatus {

    PENDING_REVIEW,
    PENDING_APPROVAL,
    APPROVED,
    REJECTED,
    WITHDRAWN,
    ISSUED;

    public boolean isWaiting() {
        return this == PENDING_REVIEW || this == PENDING_APPROVAL;
    }
}
