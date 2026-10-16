package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : FixedAssetStatus.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : A fixed asset: in use (depreciated each month until its book value is its residual value), disposed of, or
 *               cancelled (the expense bill it came from was reversed before it was depreciated).
 * </pre>
 */
public enum FixedAssetStatus {
    ACTIVE,
    DISPOSED,
    CANCELLED
}
