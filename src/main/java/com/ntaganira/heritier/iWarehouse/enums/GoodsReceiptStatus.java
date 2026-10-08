package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : GoodsReceiptStatus.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Life of a goods receipt (PRC-02). A draft is checked and corrected, then posted: posting
 *               creates the stock units and can't be undone (corrections are stock adjustments, INV-07).
 *               A draft that should not be posted is cancelled with a reason.
 * </pre>
 */
public enum GoodsReceiptStatus {

    DRAFT,
    POSTED,
    CANCELLED
}
