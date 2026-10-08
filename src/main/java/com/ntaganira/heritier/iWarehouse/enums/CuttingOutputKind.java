package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : CuttingOutputKind.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : What came out of a cut (PRD-03..08): a cut piece or an off-cut (each a new stock unit),
 *               cullet (leftovers below the off-cut threshold and the trim) or glass broken while cutting.
 *               Cullet and breakage are expensed to spoilage.
 * </pre>
 */
public enum CuttingOutputKind {

    PIECE,
    OFFCUT,
    CULLET,
    BROKEN;

    /** Becomes a stock unit. */
    public boolean isUnit() {
        return this == PIECE || this == OFFCUT;
    }
}
