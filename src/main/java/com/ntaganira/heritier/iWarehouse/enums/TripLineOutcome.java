package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : TripLineOutcome.java
 * - Date      : 2026. 10. 11.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : What became of a unit of a trip at the end of the day (FLT-09): sold from the vehicle, back on a
 *               rack, broken on the road (the breakage accepted) or missing (an audit case, FLT-10).
 * </pre>
 */
public enum TripLineOutcome {

    SOLD,
    RETURNED,
    BROKEN,
    MISSING
}
