package com.ntaganira.heritier.iWarehouse.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : CurrencyMath.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : Pure currency arithmetic (ACC-02). Rates are RWF for 1 unit of a currency.
 *               Conversions are not rounded: documents round once, at the total (CLAUDE.md).
 * </pre>
 */
public final class CurrencyMath {

    /** Decimal places stored for a rate (exchange_rates.rate NUMERIC(18,6)). */
    public static final int RATE_SCALE = 6;
    /** Extra precision kept by divisions before the document rounds its total. */
    private static final int WORK_SCALE = 10;

    private CurrencyMath() {
    }

    /** Foreign amount in RWF, unrounded. */
    public static BigDecimal toBase(BigDecimal amount, BigDecimal rate) {
        return amount.multiply(rate);
    }

    /** RWF amount in the foreign currency, unrounded (10 decimals). */
    public static BigDecimal fromBase(BigDecimal baseAmount, BigDecimal rate) {
        return baseAmount.divide(rate, WORK_SCALE, RoundingMode.HALF_UP);
    }

    /** Rounds an amount to the currency's minor units, half up (RWF: whole francs). */
    public static BigDecimal round(BigDecimal amount, int decimals) {
        return amount.setScale(decimals, RoundingMode.HALF_UP);
    }

    /** Units of currency B for 1 unit of currency A, from their RWF rates (e.g. EUR in USD). */
    public static BigDecimal cross(BigDecimal rateA, BigDecimal rateB) {
        return rateA.divide(rateB, RATE_SCALE, RoundingMode.HALF_UP);
    }

    /** Change from the previous rate to the new one, in percent with 2 decimals (+1.25 = up 1.25%). */
    public static BigDecimal changePercent(BigDecimal previous, BigDecimal next) {
        return next.subtract(previous).multiply(BigDecimal.valueOf(100)).divide(previous, 2, RoundingMode.HALF_UP);
    }

    /** True when the new rate moves more than the threshold (percent) from the previous one: likely a typo. */
    public static boolean isLargeChange(BigDecimal previous, BigDecimal next, BigDecimal thresholdPercent) {
        return changePercent(previous, next).abs().compareTo(thresholdPercent) > 0;
    }
}
