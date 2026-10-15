package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : PurchaseOrderStatus.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Life of a purchase order (PRC-01). A draft can be edited; once placed with the supplier
 *               it is fixed and receipts are recorded against it until every sheet has arrived, or it is
 *               closed short. An order nothing was received on can be cancelled.
 * </pre>
 */
public enum PurchaseOrderStatus {

    DRAFT,
    /** Placed above the Settings limit: waits for another person's approval before it is sent (CMP 06). */
    PENDING_APPROVAL,
    ORDERED,
    PARTIALLY_RECEIVED,
    RECEIVED,
    CLOSED,
    CANCELLED;

    public boolean isEditable() {
        return this == DRAFT;
    }

    /** Placed and still waiting for sheets: crates can be received against it. */
    public boolean isReceivable() {
        return this == ORDERED || this == PARTIALLY_RECEIVED;
    }

    /** Not finished yet: a draft, an order waiting for approval or one still waiting for sheets. */
    public boolean isOpen() {
        return this == DRAFT || this == PENDING_APPROVAL || isReceivable();
    }
}
