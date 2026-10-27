package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : DamageStatus.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
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
