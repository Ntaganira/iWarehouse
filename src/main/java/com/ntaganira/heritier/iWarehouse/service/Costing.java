package com.ntaganira.heritier.iWarehouse.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : Costing.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Pure stock costing rules (PRC-05, INV-01): cost per m² in RWF from a foreign price,
 *               the cost of one unit, and the moving average cost per m² after stock comes in.
 *               No Spring here, so they are unit-tested directly.
 * </pre>
 */
public final class Costing {

    /** Decimals kept for costs per m² (NUMERIC(18,4)): they are multiplied by areas later. */
    public static final int RATE_SCALE = 4;
    /** Decimals of a unit's cost (NUMERIC(18,2)). */
    public static final int MONEY_SCALE = 2;

    private Costing() {
    }

    /** RWF per m²: price per m² in the order's currency x rate to RWF, 4 decimals. */
    public static BigDecimal costPerM2(BigDecimal pricePerM2, BigDecimal rate) {
        return pricePerM2.multiply(rate).setScale(RATE_SCALE, RoundingMode.HALF_UP);
    }

    /** Cost of one unit in RWF: its area x cost per m², 2 decimals. */
    public static BigDecimal unitCost(BigDecimal areaM2, BigDecimal costPerM2) {
        return areaM2.multiply(costPerM2).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Moving average cost per m² after stock comes in: (m² held x current MAC + value added) / (m² held
     * + m² added), 4 decimals. A product without a MAC yet (nothing costed held) takes the cost of what
     * comes in. Nothing added (only broken sheets) leaves the MAC as it was.
     */
    public static BigDecimal movingAverage(BigDecimal heldM2, BigDecimal currentMac, BigDecimal addedM2,
                                           BigDecimal addedValue) {
        if (addedM2.signum() <= 0) {
            return currentMac;
        }
        if (currentMac == null || heldM2.signum() <= 0) {
            return addedValue.divide(addedM2, RATE_SCALE, RoundingMode.HALF_UP);
        }
        BigDecimal value = heldM2.multiply(currentMac).add(addedValue);
        return value.divide(heldM2.add(addedM2), RATE_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Moving average cost per m² after value is added to stock already held (landed cost, PRC-05):
     * current MAC + value added / m² held, 4 decimals. Nothing held, or no MAC yet, leaves it as it was.
     */
    public static BigDecimal addValue(BigDecimal heldM2, BigDecimal currentMac, BigDecimal addedValue) {
        if (currentMac == null || heldM2.signum() <= 0) {
            return currentMac;
        }
        return currentMac.add(addedValue.divide(heldM2, RATE_SCALE + 6, RoundingMode.HALF_UP))
                .setScale(RATE_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Moving average cost per m² after a cut (PRD-07): the m² held change by the pieces and off-cuts less
     * the source, and the value by what was expensed (cullet and breakage, negative). (m² held x MAC +
     * value change) / (m² held + area change), 4 decimals. A sheet cut at the MAC leaves the MAC as it was.
     * Without a MAC, or with nothing left held, it stays as it was.
     */
    public static BigDecimal afterCut(BigDecimal heldM2, BigDecimal currentMac, BigDecimal areaChange,
                                      BigDecimal valueChange) {
        return afterStockChange(heldM2, currentMac, areaChange, valueChange);
    }

    /**
     * Moving average cost per m² after m² and value leave or enter stock at a unit's own cost (cuts, write-offs,
     * units found or resized, INV-07): (m² held x MAC + value change) / (m² held + area change), 4 decimals.
     * Without a MAC, or with nothing left held, it stays as it was.
     */
    public static BigDecimal afterStockChange(BigDecimal heldM2, BigDecimal currentMac, BigDecimal areaChange,
                                              BigDecimal valueChange) {
        BigDecimal heldAfter = heldM2.add(areaChange);
        if (currentMac == null || heldAfter.signum() <= 0) {
            return currentMac;
        }
        return heldM2.multiply(currentMac).add(valueChange).divide(heldAfter, RATE_SCALE, RoundingMode.HALF_UP);
    }
}
