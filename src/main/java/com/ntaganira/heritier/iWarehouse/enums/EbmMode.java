package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : EbmMode.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : Who signs receipts (Settings, ebm.mode; TAX-02): the simulator (development and tests; its receipts
 *               print that they are not fiscal) or the business's VSDC.
 * </pre>
 */
public enum EbmMode {

    SIMULATOR,
    VSDC
}
