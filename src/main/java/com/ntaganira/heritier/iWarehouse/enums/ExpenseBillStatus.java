package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : ExpenseBillStatus.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : An expense bill: entered, submitted, recommended by the head of department, reviewed by Finance,
 *               approved (posted) or rejected; cancelled by who entered it; reversed once posted.
 * </pre>
 */
public enum ExpenseBillStatus {
    DRAFT,
    SUBMITTED,
    RECOMMENDED,
    REVIEWED,
    APPROVED,
    REJECTED,
    CANCELLED,
    REVERSED;

    /** Waiting for a step before it is approved. */
    public boolean isWaiting() {
        return this == SUBMITTED || this == RECOMMENDED || this == REVIEWED;
    }
}
