package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : TripStatus.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : Where a trip stands (FLT-05..10). Planned: its manifest can change and holds its units, each confirmed
 *               by a scan. Departed: the units are on the vehicle, in the driver's charge. Returned: scanned back and
 *               reconciled (each unit sold, back on its rack, broken or missing). Closed: its stock and cash settled (no
 *               audit case open, the cash declared and the float cleared). Cancelled before departure, with a reason.
 * </pre>
 */
public enum TripStatus {

    PLANNED,
    DEPARTED,
    RETURNED,
    CLOSED,
    CANCELLED
}
