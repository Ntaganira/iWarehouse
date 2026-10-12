package com.ntaganira.heritier.iWarehouse.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : VehicleCosts.java
 * - Date      : 2026. 10. 12.
 * - User      : Hntaganira
 * - Desc      : The sums of vehicle costs, pure: whether a cost waits for approval (above the limit), the most VAT an amount
 *               can include (18 %, rounded to the RWF), what a driver may still pay from a trip's cash (its cash sales less
 *               what was paid from it already, waiting or posted) and the cost per km.
 * </pre>
 */
public final class VehicleCosts {

    /** Rwanda's standard VAT rate, included in a receipt's amount. */
    public static final BigDecimal VAT_RATE = new BigDecimal("18");

    private VehicleCosts() {
    }

    /** Above the limit, another person approves it first; at or under it, it posts when recorded. */
    public static boolean needsApproval(BigDecimal amount, BigDecimal limit) {
        return amount.compareTo(limit) > 0;
    }

    /** The VAT 18 % included in an amount: the most a receipt of that amount can show. */
    public static BigDecimal maxVat(BigDecimal amount) {
        return amount.multiply(VAT_RATE).divide(VAT_RATE.add(new BigDecimal("100")), 0, RoundingMode.HALF_UP);
    }

    /** What is left of a trip's cash for the driver to pay costs from: never below nothing. */
    public static BigDecimal floatLeft(BigDecimal cashSales, BigDecimal alreadyPaid) {
        return cashSales.subtract(alreadyPaid).max(BigDecimal.ZERO);
    }

    /** RWF per km, to the RWF; null when no km were driven. */
    public static BigDecimal perKm(BigDecimal cost, long km) {
        return km <= 0 ? null : cost.divide(BigDecimal.valueOf(km), 0, RoundingMode.HALF_UP);
    }
}
