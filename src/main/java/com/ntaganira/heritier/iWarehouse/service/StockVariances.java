package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.enums.VarianceClass;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : StockVariances.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : The rules of a count variance (IMP 14, 16, 19; VR-010): its size as a % of the value counted (what was
 *               missing and what was found, both counting), its class against the Settings' moderate and material %,
 *               whether it is above the tolerance, and the day it is due (so many working days after the count
 *               closed). Pure and tested.
 * </pre>
 */
public final class StockVariances {

    private StockVariances() {
    }

    /** Missing plus found value as a % of the expected value (2 decimals); null when nothing was expected. */
    public static BigDecimal percent(BigDecimal missingValue, BigDecimal foundValue, BigDecimal expectedValue) {
        if (expectedValue == null || expectedValue.signum() <= 0) {
            return null;
        }
        BigDecimal gross = missingValue.abs().add(foundValue.abs());
        return gross.multiply(BigDecimal.valueOf(100)).divide(expectedValue, 2, RoundingMode.HALF_UP);
    }

    /** MATERIAL from the material %, MODERATE from the moderate %, MINOR below; with nothing expected, any value is material. */
    public static VarianceClass classify(BigDecimal percent, BigDecimal moderate, BigDecimal material) {
        if (percent == null) {
            return VarianceClass.MATERIAL;
        }
        if (percent.compareTo(material) >= 0) {
            return VarianceClass.MATERIAL;
        }
        return percent.compareTo(moderate) >= 0 ? VarianceClass.MODERATE : VarianceClass.MINOR;
    }

    /** Above the tolerance: investigated (IMP 14.3, 16.2). */
    public static boolean aboveTolerance(BigDecimal percent, BigDecimal tolerance) {
        return percent == null || percent.compareTo(tolerance) > 0;
    }

    /** The day it is to be resolved by: the n-th working day after the day the count closed. */
    public static LocalDate due(LocalDate closed, int workingDays) {
        LocalDate day = closed;
        int left = Math.max(0, workingDays);
        while (left > 0) {
            day = day.plusDays(1);
            if (WorkingDays.isWorking(day)) {
                left--;
            }
        }
        return day;
    }

    /** Inventory accuracy (IMP 19): units found where expected as a % of units expected (1 decimal); null when none. */
    public static BigDecimal accuracy(int matched, int expected) {
        if (expected <= 0) {
            return null;
        }
        return BigDecimal.valueOf(matched * 100L).divide(BigDecimal.valueOf(expected), 1, RoundingMode.HALF_UP);
    }
}
