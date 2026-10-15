package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : SaleApprovalKind.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : What a manager is asked to approve at the counter (F-03): a line's price cut deeper than the
 *               cashier's discount limit or below what the glass cost (POS-06, SDP 8), customer credit above what
 *               the customer has left or while their invoices are overdue (POS-05, SDP 11), or longer payment terms
 *               for this invoice.
 * </pre>
 */
public enum SaleApprovalKind {

    PRICE,
    CREDIT,
    TERMS
}
