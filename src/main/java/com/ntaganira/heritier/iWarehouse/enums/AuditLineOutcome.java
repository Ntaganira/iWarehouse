package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : AuditLineOutcome.java
 * - Date      : 2026. 10. 11.
 * - User      : Hntaganira
 * - Desc      : How a unit of an audit case was settled (FLT-10, FLT-11). A missing unit: found (back on a rack),
 *               sold (a sale from the vehicle synced late), written off (the business's loss) or charged to the driver
 *               (with the owner's approval). An unexpected unit: explained.
 * </pre>
 */
public enum AuditLineOutcome {

    FOUND,
    SOLD,
    WRITTEN_OFF,
    CHARGED,
    EXPLAINED
}
