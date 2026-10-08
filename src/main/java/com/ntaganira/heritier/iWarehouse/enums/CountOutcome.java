package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : CountOutcome.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : What a stock count found for one unit (INV-08): matched, misplaced, missing, or extra
 *               (a lost unit found, a unit the records place elsewhere or show as gone, an unknown label).
 * </pre>
 */
public enum CountOutcome {

    /** In stock where the records say, and scanned there. */
    MATCHED,
    /** In stock, but scanned on another rack or slot of the count. */
    MISPLACED,
    /** In stock on the counted places, but not scanned. */
    MISSING,
    /** Written off as lost, scanned on a counted place. */
    FOUND_LOST,
    /** Recorded as being cut or on a vehicle, yet scanned on a rack. */
    ELSEWHERE,
    /** Recorded as gone (sold, cut up, broken), yet scanned on a rack. */
    NOT_IN_STOCK,
    /** No unit has the scanned code. */
    UNKNOWN;

    /** The SRS's "extra": found on the racks although the records do not have it in stock there. */
    public boolean isExtra() {
        return this == FOUND_LOST || this == ELSEWHERE || this == NOT_IN_STOCK || this == UNKNOWN;
    }
}
