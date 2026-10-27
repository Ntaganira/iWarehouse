package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : StaffAdvanceStatus.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : A staff advance: asked, submitted, approved by the MD, paid, then retired with receipts or recovered
 *               from salary; rejected, or cancelled before it is paid.
 * </pre>
 */
public enum StaffAdvanceStatus {
    DRAFT,
    SUBMITTED,
    APPROVED,
    PAID,
    RETIRED,
    RECOVERED,
    REJECTED,
    CANCELLED;

    /** Asked and not finished with: blocks a new advance for the same employee (CMP 08). */
    public boolean isOpen() {
        return this == SUBMITTED || this == APPROVED || this == PAID;
    }
}
