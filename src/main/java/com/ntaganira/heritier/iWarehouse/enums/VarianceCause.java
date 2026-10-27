package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : VarianceCause.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The root cause of a count variance (VR-010): a counting or recording error, glass misplaced, theft, damage, the supplier, unknown or another cause.
 * </pre>
 */
public enum VarianceCause {

    COUNTING_ERROR,
    RECORDING_ERROR,
    MISPLACEMENT,
    THEFT,
    DAMAGE,
    SUPPLIER,
    UNKNOWN,
    OTHER
}
