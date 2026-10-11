package com.ntaganira.heritier.iWarehouse.service;

import java.math.BigDecimal;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : FloatClearances.java
 * - Date      : 2026. 10. 11.
 * - User      : Hntaganira
 * - Desc      : A driver's float counted (ACC-06, ACC-07), pure: less cash than the trip's cash sales is a shortage on
 *               the driver's receivable, more is an overage to Cash Over/Short; what was counted plus the shortage always
 *               equals what was expected plus the overage, so the clearance's journal balances.
 * </pre>
 */
public final class FloatClearances {

    private FloatClearances() {
    }

    public record Split(BigDecimal shortage, BigDecimal overage) {

        public boolean isExact() {
            return shortage.signum() == 0 && overage.signum() == 0;
        }
    }

    public static Split split(BigDecimal expected, BigDecimal counted) {
        BigDecimal difference = counted.subtract(expected);
        return difference.signum() < 0 ? new Split(difference.negate(), BigDecimal.ZERO) : new Split(BigDecimal.ZERO, difference);
    }
}
