package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : AllocationMethod.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : How a shipment's import costs are shared between its crates (PRC-04): by m² (default),
 *               by purchase value in RWF, or by weight. Broken sheets count, so they carry their share.
 * </pre>
 */
public enum AllocationMethod {

    AREA,
    VALUE,
    WEIGHT
}
