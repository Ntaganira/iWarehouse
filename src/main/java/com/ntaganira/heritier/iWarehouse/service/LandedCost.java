package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.enums.AllocationMethod;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : LandedCost.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Pure landed cost rules (PRC-04, PRC-05): each crate's basis (m², purchase value or kg,
 *               broken sheets included), splitting an amount in proportion so the parts add up to it
 *               exactly (largest remainder), and the extra cost per m². No Spring here, so they are
 *               unit-tested directly.
 * </pre>
 */
public final class LandedCost {

    /** Decimals of an allocated amount (NUMERIC(18,2), like unit costs). */
    public static final int MONEY_SCALE = 2;
    /** Precision kept while splitting, before the parts are rounded. */
    private static final int WORK_SCALE = 20;

    private LandedCost() {
    }

    /**
     * A crate as the costs see it: good and broken sheets of one size, its purchase cost per m² in RWF
     * and its glass's kg per m². Broken sheets were shipped too, so they carry their share.
     */
    public record CrateBasis(int sheets, int broken, BigDecimal sheetArea, BigDecimal costPerM2, BigDecimal weightPerM2) {

        public int pieces() {
            return sheets + broken;
        }

        /** m² shipped (good + broken). */
        public BigDecimal area() {
            return sheetArea.multiply(BigDecimal.valueOf(pieces()));
        }

        /** Purchase value in RWF of the m² shipped. */
        public BigDecimal value() {
            return area().multiply(costPerM2);
        }

        /** kg shipped. */
        public BigDecimal weight() {
            return area().multiply(weightPerM2);
        }

        /** What the crate's share is in proportion to, 4 decimals (shipment_allocations.basis). */
        public BigDecimal basis(AllocationMethod method) {
            BigDecimal basis = switch (method) {
                case AREA -> area();
                case VALUE -> value();
                case WEIGHT -> weight();
            };
            return basis.setScale(4, RoundingMode.HALF_UP);
        }
    }

    /** RWF total of a posting: the costs converted unrounded, rounded once to the base currency's decimals. */
    public static BigDecimal postingTotal(Collection<BigDecimal> baseAmounts, int decimals) {
        BigDecimal sum = baseAmounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return CurrencyMath.round(sum, decimals);
    }

    /**
     * Splits {@code total} in proportion to {@code weights}, at {@code scale} decimals, so the parts add up
     * to it exactly: each part is rounded down, and the units left over go to the largest remainders
     * (the earlier part first on a tie). A negative total (a credit) splits the same way, negated.
     */
    public static List<BigDecimal> split(BigDecimal total, List<BigDecimal> weights, int scale) {
        if (weights.isEmpty()) {
            throw new IllegalArgumentException("Nothing to split over");
        }
        BigDecimal sum = weights.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.signum() <= 0 || weights.stream().anyMatch(w -> w.signum() < 0)) {
            throw new IllegalArgumentException("Weights must be positive in total and none negative");
        }
        BigDecimal units = total.abs().movePointRight(scale).setScale(0, RoundingMode.HALF_UP);
        int n = weights.size();
        BigDecimal[] floors = new BigDecimal[n];
        BigDecimal[] remainders = new BigDecimal[n];
        BigDecimal given = BigDecimal.ZERO;
        for (int i = 0; i < n; i++) {
            BigDecimal exact = units.multiply(weights.get(i)).divide(sum, WORK_SCALE, RoundingMode.DOWN);
            floors[i] = exact.setScale(0, RoundingMode.DOWN);
            remainders[i] = exact.subtract(floors[i]);
            given = given.add(floors[i]);
        }
        int left = units.subtract(given).intValueExact();
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        Arrays.sort(order, Comparator.comparing((Integer i) -> remainders[i]).reversed().thenComparing(i -> i));
        for (int k = 0; k < left; k++) {
            floors[order[k % n]] = floors[order[k % n]].add(BigDecimal.ONE);
        }
        List<BigDecimal> parts = new ArrayList<>(n);
        for (BigDecimal part : floors) {
            BigDecimal value = part.movePointLeft(scale).setScale(scale, RoundingMode.UNNECESSARY);
            parts.add(total.signum() < 0 ? value.negate() : value);
        }
        return parts;
    }

    /** Splits a crate's amount into equal parts, one per piece (all its sheets are the same size). */
    public static List<BigDecimal> perPiece(BigDecimal crateAmount, int pieces) {
        return split(crateAmount, Collections.nCopies(pieces, BigDecimal.ONE), MONEY_SCALE);
    }

    /** Amount per m², 4 decimals; zero when there is no area. */
    public static BigDecimal perM2(BigDecimal amount, BigDecimal areaM2) {
        if (areaM2 == null || areaM2.signum() == 0) {
            return BigDecimal.ZERO.setScale(Costing.RATE_SCALE);
        }
        return amount.divide(areaM2, Costing.RATE_SCALE, RoundingMode.HALF_UP);
    }
}
