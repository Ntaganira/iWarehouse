package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : FixedAssetCategory.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : What kind of fixed asset (C.08), with the useful life it starts with (months).
 * </pre>
 */
public enum FixedAssetCategory {
    BUILDINGS(600),
    VEHICLES(60),
    MACHINERY(120),
    FURNITURE(60),
    IT_EQUIPMENT(36),
    OTHER(60);

    private final int lifeMonths;

    FixedAssetCategory(int lifeMonths) {
        this.lifeMonths = lifeMonths;
    }

    public int lifeMonths() {
        return lifeMonths;
    }
}
