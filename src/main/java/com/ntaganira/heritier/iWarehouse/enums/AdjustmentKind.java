package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : AdjustmentKind.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : One line of a stock adjustment (INV-07): write a unit off (broken or missing), find a
 *               lost unit again, add a piece nobody recorded (valued at MAC), or correct a unit's size
 *               (the unit is replaced by one of the right size, at the same cost per m²).
 * </pre>
 */
public enum AdjustmentKind {

    WRITE_OFF,
    FOUND,
    NEW_UNIT,
    RESIZE;

    /** Works on a unit identified by its label code. */
    public boolean needsUnit() {
        return this != NEW_UNIT;
    }

    /** Places a unit on a location. */
    public boolean needsLocation() {
        return this == FOUND || this == NEW_UNIT;
    }

    /** Takes a size. */
    public boolean needsSize() {
        return this == NEW_UNIT || this == RESIZE;
    }
}
