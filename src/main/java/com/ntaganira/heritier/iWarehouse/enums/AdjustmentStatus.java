package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : AdjustmentStatus.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Life of a stock adjustment (INV-07). Within the approval limit it posts when saved;
 *               above it, it waits for a second person, who approves (it posts) or rejects it with a
 *               reason; the requester can withdraw it (cancelled, with a reason). Posted adjustments
 *               never change.
 * </pre>
 */
public enum AdjustmentStatus {

    PENDING_APPROVAL,
    POSTED,
    REJECTED,
    CANCELLED
}
