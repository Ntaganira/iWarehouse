package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : DamageStatus.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : Where a damage report stands (F-07): reported, inspected by Dispatch, approved or rejected by the Director of Supply Chain, or cancelled by whoever reported it. Reported and inspected reports hold their units.
 * </pre>
 */
public enum DamageStatus {

    REPORTED,
    INSPECTED,
    APPROVED,
    REJECTED,
    CANCELLED
}
