package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : TripStatus.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : Where a trip stands (FLT-05..07). Planned: its manifest can change and holds its units, each confirmed
 *               by a scan. Departed: the units are on the vehicle, in the driver's charge. Cancelled before departure,
 *               with a reason. The return scan and the end of day add their states with M9 (and chk_trips_status).
 * </pre>
 */
public enum TripStatus {

    PLANNED,
    DEPARTED,
    CANCELLED
}
