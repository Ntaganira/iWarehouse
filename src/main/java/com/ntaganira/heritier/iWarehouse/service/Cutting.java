package com.ntaganira.heritier.iWarehouse.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : Cutting.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Rules of a cut, without the database: does a piece fit the source (PRD-02), is a leftover
 *               an off-cut (PRD-04), do the areas balance within 1% (PRD-06), what is the cullet and the
 *               yield (PRD-05, PRD-09), and how the source cost is shared by area (PRD-07).
 * </pre>
 */
public final class Cutting {

    /** PRD-06: outputs may exceed the source area by 1% at most (measuring), never more. */
    public static final BigDecimal AREA_TOLERANCE = new BigDecimal("0.01");

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private Cutting() {
    }

    /** A piece fits a source either way round (glass has no grain to keep). */
    public static boolean fits(int widthMm, int heightMm, int sourceWidthMm, int sourceHeightMm) {
        return (widthMm <= sourceWidthMm && heightMm <= sourceHeightMm)
                || (widthMm <= sourceHeightMm && heightMm <= sourceWidthMm);
    }

    /** A leftover becomes an off-cut unit when it is at least the minimum area and both sides are long enough (PRD-04). */
    public static boolean isOffcut(int widthMm, int heightMm, BigDecimal minAreaM2, int minSideMm) {
        return Math.min(widthMm, heightMm) >= minSideMm && Pricing.areaM2(widthMm, heightMm).compareTo(minAreaM2) >= 0;
    }

    /**
     * Areas of a cut in m² (PRD-05, PRD-06). Leftovers the operator measured below the off-cut threshold
     * and the trim (what nobody measured: source minus everything recorded) are both cullet.
     */
    public record Balance(BigDecimal source, BigDecimal pieces, BigDecimal offcuts, BigDecimal smallLeftovers,
                          BigDecimal broken) {

        /** Everything recorded. */
        public BigDecimal getRecorded() {
            return pieces.add(offcuts).add(smallLeftovers).add(broken);
        }

        /** The trim: the source area nothing was recorded for. */
        public BigDecimal getTrim() {
            return source.subtract(getRecorded()).max(BigDecimal.ZERO);
        }

        public BigDecimal getCullet() {
            return smallLeftovers.add(getTrim());
        }

        /** m² recorded beyond the source area (measuring); zero when within it. */
        public BigDecimal getExcess() {
            return getRecorded().subtract(source).max(BigDecimal.ZERO);
        }

        /** PRD-06: source = pieces + off-cuts + cullet (+ breakage), within 1%. */
        public boolean isBalanced() {
            return getExcess().compareTo(source.multiply(AREA_TOLERANCE)) <= 0;
        }

        /** Pieces and off-cuts as a percentage of the source, 2 decimals, at most 100 (PRD-09). */
        public BigDecimal getYieldPercent() {
            return yieldPercent(pieces.add(offcuts), source);
        }
    }

    /** Stocked area as a percentage of the consumed area, 2 decimals, at most 100; zero without a source. */
    public static BigDecimal yieldPercent(BigDecimal stockedM2, BigDecimal consumedM2) {
        if (consumedM2 == null || consumedM2.signum() <= 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return stockedM2.multiply(HUNDRED).divide(consumedM2, 2, RoundingMode.HALF_UP).min(HUNDRED.setScale(2));
    }

    /**
     * Shares the source cost over what came out of it by area (PRD-07): each part is the cost per m² times
     * its area, and the parts add up to the cost exactly (largest remainder, 2 decimals). Areas of zero
     * (no trim) get nothing.
     */
    public static List<BigDecimal> costByArea(BigDecimal sourceCost, List<BigDecimal> areas) {
        return LandedCost.split(sourceCost, areas, LandedCost.MONEY_SCALE);
    }
}
