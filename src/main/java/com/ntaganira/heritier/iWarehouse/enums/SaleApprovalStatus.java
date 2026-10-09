package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : SaleApprovalStatus.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : Life of an approval request at the counter (POS-05, POS-06): pending until another person
 *               approves or rejects it (with a reason). It is withdrawn, with a note, when the cashier takes it
 *               back or it no longer applies: its line left the sale, the customer changed, the sale was
 *               cancelled or a newer request replaced it.
 * </pre>
 */
public enum SaleApprovalStatus {

    PENDING,
    APPROVED,
    REJECTED,
    WITHDRAWN
}
