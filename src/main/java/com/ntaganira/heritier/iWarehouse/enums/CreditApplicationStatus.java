package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : CreditApplicationStatus.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : Life of a credit application (F-02 C-D): assessed by Credit Control, reviewed by the Director of Finance (when
 *               Settings ask), approved within the Delegation of Authority, which sets the terms; or rejected, or withdrawn.
 * </pre>
 */
public enum CreditApplicationStatus {

    PENDING_ASSESSMENT,
    PENDING_REVIEW,
    PENDING_APPROVAL,
    APPROVED,
    REJECTED,
    WITHDRAWN;

    public boolean isWaiting() {
        return this == PENDING_ASSESSMENT || this == PENDING_REVIEW || this == PENDING_APPROVAL;
    }
}
