package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : PayRunStatus.java
 * - Date      : 2026. 10. 11.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : A month's pay run: prepared (DRAFT), waiting for approval (SUBMITTED), approved and posted (POSTED),
 *               its salaries paid (PAID), or given up (CANCELLED). Posted, it never changes.
 * </pre>
 */
public enum PayRunStatus {

    DRAFT,
    SUBMITTED,
    POSTED,
    PAID,
    CANCELLED;

    public boolean isPosted() {
        return this == POSTED || this == PAID;
    }
}
