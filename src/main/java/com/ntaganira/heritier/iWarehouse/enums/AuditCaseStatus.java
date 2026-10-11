package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : AuditCaseStatus.java
 * - Date      : 2026. 10. 11.
 * - User      : Hntaganira
 * - Desc      : Where an audit case stands (FLT-10): open, escalated to the owner (the trip may close; the units are
 *               still to settle), or resolved (every unit settled).
 * </pre>
 */
public enum AuditCaseStatus {

    OPEN,
    ESCALATED,
    RESOLVED
}
