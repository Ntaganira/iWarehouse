package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : SalesInvoiceStatus.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : A sale at the counter: a draft while it is rung up (its units held), posted once paid (an
 *               invoice with its number, never changed again), cancelled when abandoned before payment.
 * </pre>
 */
public enum SalesInvoiceStatus {

    DRAFT,
    POSTED,
    CANCELLED
}
