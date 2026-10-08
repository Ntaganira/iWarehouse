package com.ntaganira.heritier.iWarehouse.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : Pricing.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Pure price rules (MD-06): area and chargeable area of a piece, which list a price comes
 *               from, and reading a typed price. No Spring here, so they are unit-tested; quotations
 *               and the counter POS (POS-02) price with these.
 * </pre>
 */
public final class Pricing {

    public static final int AREA_SCALE = 4;
    private static final BigDecimal MM2_PER_M2 = BigDecimal.valueOf(1_000_000);
    private static final Pattern THOUSANDS_WITH_COMMAS = Pattern.compile("^\\d{1,3}(,\\d{3})+$");
    private static final int MAX_INTEGER_DIGITS = 16;

    private Pricing() {
    }

    /** m² of a piece: width x height / 1,000,000, to 4 decimals. */
    public static BigDecimal areaM2(int widthMm, int heightMm) {
        return BigDecimal.valueOf((long) widthMm * heightMm).divide(MM2_PER_M2, AREA_SCALE, RoundingMode.HALF_UP);
    }

    /** The area a piece is charged for: its own area, but never less than the minimum (0.25 m² by default). */
    public static BigDecimal chargeableArea(int widthMm, int heightMm, BigDecimal minArea) {
        BigDecimal area = areaM2(widthMm, heightMm);
        if (minArea != null && area.compareTo(minArea) < 0) {
            return minArea.setScale(AREA_SCALE, RoundingMode.HALF_UP);
        }
        return area;
    }

    /** A price and whether it came from the default list because the customer's own list has none. */
    public record ResolvedPrice(BigDecimal price, boolean fromDefaultList) {
    }

    /** The customer's list first, then the default list; empty when neither prices it. */
    public static Optional<ResolvedPrice> resolve(BigDecimal ownListPrice, BigDecimal defaultListPrice) {
        if (ownListPrice != null) {
            return Optional.of(new ResolvedPrice(ownListPrice, false));
        }
        if (defaultListPrice != null) {
            return Optional.of(new ResolvedPrice(defaultListPrice, true));
        }
        return Optional.empty();
    }

    /**
     * Reads a price typed on the price form: blank is null (not priced). Spaces and thousands commas
     * are dropped ("25 000", "25,000"); a lone comma is a decimal mark ("12,5"). Throws
     * IllegalArgumentException with a message key when the text is not a positive amount with at
     * most 2 decimals.
     */
    public static BigDecimal parsePrice(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String s = text.replaceAll("[\\s\\u00a0\\u202f]", "");
        if (s.contains(".")) {
            s = s.replace(",", "");
        } else if (THOUSANDS_WITH_COMMAS.matcher(s).matches()) {
            s = s.replace(",", "");
        } else {
            s = s.replace(',', '.');
        }
        BigDecimal value;
        try {
            value = new BigDecimal(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("price.invalid");
        }
        if (value.signum() <= 0) {
            throw new IllegalArgumentException("price.positive");
        }
        value = value.stripTrailingZeros();
        if (value.scale() > 2) {
            throw new IllegalArgumentException("price.decimals");
        }
        if (value.precision() - value.scale() > MAX_INTEGER_DIGITS) {
            throw new IllegalArgumentException("price.tooLarge");
        }
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }
}
