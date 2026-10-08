package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : ShipmentStatus.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Life of a shipment's cost sheet (PRC-03). Open while bills still come in (each can be
 *               posted as it arrives); closed when the costs are complete; cancelled with a reason when
 *               nothing was posted on it.
 * </pre>
 */
public enum ShipmentStatus {

    OPEN,
    CLOSED,
    CANCELLED
}
