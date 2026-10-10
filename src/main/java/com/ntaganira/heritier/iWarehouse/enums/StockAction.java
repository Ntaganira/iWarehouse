package com.ntaganira.heritier.iWarehouse.enums;

import java.util.EnumSet;
import java.util.Set;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : StockAction.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : What can be done with a stock unit, and in which states (INV-05). A unit that is
 *               reserved, being cut, on a vehicle or gone cannot be sold, loaded or cut; a reserved unit
 *               can still be moved to another rack or written off. Units on a pending adjustment are
 *               held whatever their state (StockService).
 * </pre>
 */
public enum StockAction {

    /** Taken for a cutting job (PRD-02). */
    CUT(EnumSet.of(StockStatus.AVAILABLE)),
    /** Moved to another rack or slot (INV-07). */
    TRANSFER(EnumSet.of(StockStatus.RECEIVED, StockStatus.AVAILABLE, StockStatus.RESERVED)),
    /** Written off as broken or missing, or resized (INV-07). */
    ADJUST(EnumSet.of(StockStatus.RECEIVED, StockStatus.AVAILABLE, StockStatus.RESERVED)),
    /** Reserved for a customer (INV-05). */
    RESERVE(EnumSet.of(StockStatus.AVAILABLE)),
    /** Released from a reservation. */
    RELEASE(EnumSet.of(StockStatus.RESERVED)),
    /** A lost unit found again. */
    FIND(EnumSet.of(StockStatus.LOST)),
    /** Sold at the counter: available, or reserved for the buyer (POS-01). */
    SELL(EnumSet.of(StockStatus.AVAILABLE, StockStatus.RESERVED)),
    /** Planned on a trip's manifest and loaded on its vehicle (FLT-05..07): available only. */
    LOAD(EnumSet.of(StockStatus.AVAILABLE)),
    /** Sold from the vehicle on the mobile POS (MPOS-02): on the vehicle only. */
    SELL_FROM_VEHICLE(EnumSet.of(StockStatus.ON_VEHICLE));

    private final Set<StockStatus> allowed;

    StockAction(Set<StockStatus> allowed) {
        this.allowed = allowed;
    }

    public boolean allows(StockStatus status) {
        return allowed.contains(status);
    }
}
