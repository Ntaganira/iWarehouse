package com.ntaganira.heritier.iWarehouse.enums;

import java.util.Arrays;
import java.util.List;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : StockStatus.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : The states of a stock unit (SRS 6.2), plus LOST (V13). A unit has one state and one
 *               location at a time. Posted receipts create units as AVAILABLE on their rack; RECEIVED is
 *               kept for a later put-away scan. Consumed, sold, broken and lost units have left stock;
 *               a lost unit (written off as missing) can be found again by an adjustment.
 * </pre>
 */
public enum StockStatus {

    RECEIVED,
    AVAILABLE,
    RESERVED,
    IN_CUTTING,
    CONSUMED,
    ON_VEHICLE,
    SOLD,
    BROKEN,
    LOST;

    /** Still physically held by the business (valued in stock, counted on its rack or vehicle). */
    public boolean isOnHand() {
        return this != CONSUMED && this != SOLD && this != BROKEN && this != LOST;
    }

    public static List<StockStatus> onHand() {
        return Arrays.stream(values()).filter(StockStatus::isOnHand).toList();
    }
}
