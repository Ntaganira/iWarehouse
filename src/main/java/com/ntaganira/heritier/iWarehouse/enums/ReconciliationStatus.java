package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : ReconciliationStatus.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : A bank or mobile-money reconciliation (ACC-12): RECONCILED when saved (its lines are cleared); CANCELLED,
 *               the latest of an account undone with a reason, its lines cleared no more.
 * </pre>
 */
public enum ReconciliationStatus {

    RECONCILED,
    CANCELLED
}
