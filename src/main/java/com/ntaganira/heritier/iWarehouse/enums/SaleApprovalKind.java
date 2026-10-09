package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : SaleApprovalKind.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : What a manager is asked to approve at the counter: a line's price cut deeper than the
 *               cashier's discount limit (POS-06), or customer credit above what the customer has left
 *               (POS-05).
 * </pre>
 */
public enum SaleApprovalKind {

    PRICE,
    CREDIT
}
