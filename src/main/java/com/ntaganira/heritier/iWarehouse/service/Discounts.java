package com.ntaganira.heritier.iWarehouse.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Objects;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : Discounts.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : Discount rules at the counter (POS-06), pure and tested. A price change is a discount on the
 *               list price, in percent to 2 decimals (negative when the price is raised). A user's limit is
 *               the largest of their roles' limits, a role without its own taking the Settings value; a
 *               discount above the limit needs another person's approval.
 * </pre>
 */
public final class Discounts {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private Discounts() {
    }

    /** (list - price) / list, in percent, 2 decimals; 0 for a list price of 0. */
    public static BigDecimal percent(BigDecimal listPrice, BigDecimal price) {
        if (listPrice == null || price == null || listPrice.signum() == 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return listPrice.subtract(price).multiply(HUNDRED).divide(listPrice, 2, RoundingMode.HALF_UP);
    }

    /** The price at a discount, 2 decimals (what "10% off" means on a line). */
    public static BigDecimal priceAt(BigDecimal listPrice, BigDecimal discountPercent) {
        return listPrice.multiply(HUNDRED.subtract(discountPercent)).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }

    /** A user's limit: the largest of their roles' limits, null standing for the Settings value; 0 without roles. */
    public static BigDecimal limitOf(Collection<BigDecimal> roleLimits, BigDecimal settingsValue) {
        return roleLimits.stream().map(l -> Objects.requireNonNullElse(l, settingsValue))
                .max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
    }

    /** A discount above the limit needs approval; a price kept or raised never does. */
    public static boolean needsApproval(BigDecimal discountPercent, BigDecimal limitPercent) {
        return discountPercent.signum() > 0 && discountPercent.compareTo(limitPercent) > 0;
    }
}
