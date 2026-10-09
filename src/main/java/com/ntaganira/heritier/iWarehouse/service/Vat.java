package com.ntaganira.heritier.iWarehouse.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : Vat.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : VAT on sales (TAX-01). A line is charged in whole RWF with VAT included: price per m² x
 *               chargeable area x quantity, plus VAT when the price list's prices exclude it, rounded once.
 *               VAT is then worked out per tax letter on the invoice's totals, as EBM reports it (A exempt,
 *               B standard 18%, C zero-rated): VAT = gross x rate / (100 + rate), 2 decimals; net = gross - VAT.
 *               Pure, unit-tested.
 * </pre>
 */
public final class Vat {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private Vat() {
    }

    /** A line as VAT sees it: its tax letter, rate and amount VAT included. */
    public record Line(String taxCode, BigDecimal rate, BigDecimal amount) {
    }

    /** One tax letter on an invoice: the amounts charged under it, the VAT in them and the rest. */
    public record Group(String taxCode, BigDecimal rate, BigDecimal gross, BigDecimal vat) {

        public BigDecimal getNet() {
            return gross.subtract(vat);
        }
    }

    /** The invoice's groups (by letter) and totals: net + VAT = gross. */
    public record Totals(List<Group> groups, BigDecimal net, BigDecimal vat, BigDecimal gross) {

        public static final Totals NONE = new Totals(List.of(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /**
     * What a line charges, VAT included, rounded once to {@code decimals} (0 for RWF): price per m² x chargeable
     * area x quantity, and VAT on top when the list's prices exclude it.
     */
    public static BigDecimal lineAmount(BigDecimal pricePerM2, BigDecimal chargeableArea, int quantity, boolean pricesIncludeVat,
                                        BigDecimal rate, int decimals) {
        BigDecimal amount = pricePerM2.multiply(chargeableArea).multiply(BigDecimal.valueOf(quantity));
        if (!pricesIncludeVat && rate.signum() > 0) {
            amount = amount.multiply(HUNDRED.add(rate)).divide(HUNDRED, 10, RoundingMode.HALF_UP);
        }
        return amount.setScale(Math.max(decimals, 0), RoundingMode.HALF_UP).setScale(Journal.SCALE, RoundingMode.UNNECESSARY);
    }

    /** VAT per tax letter on the invoice's lines, and the totals. Letters in alphabetical order. */
    public static Totals totals(Collection<Line> lines) {
        Map<String, BigDecimal> gross = new TreeMap<>();
        Map<String, BigDecimal> rates = new HashMap<>();
        for (Line l : lines) {
            BigDecimal previous = rates.putIfAbsent(l.taxCode(), l.rate());
            if (previous != null && previous.compareTo(l.rate()) != 0) {
                throw new IllegalArgumentException("Tax letter " + l.taxCode() + " has two rates: " + previous + " and " + l.rate());
            }
            gross.merge(l.taxCode(), l.amount(), BigDecimal::add);
        }
        List<Group> groups = new ArrayList<>();
        BigDecimal net = BigDecimal.ZERO.setScale(Journal.SCALE);
        BigDecimal vat = BigDecimal.ZERO.setScale(Journal.SCALE);
        BigDecimal total = BigDecimal.ZERO.setScale(Journal.SCALE);
        for (Map.Entry<String, BigDecimal> e : gross.entrySet()) {
            BigDecimal rate = rates.get(e.getKey());
            BigDecimal g = e.getValue().setScale(Journal.SCALE, RoundingMode.HALF_UP);
            BigDecimal v = rate.signum() == 0 ? BigDecimal.ZERO.setScale(Journal.SCALE)
                    : g.multiply(rate).divide(HUNDRED.add(rate), Journal.SCALE, RoundingMode.HALF_UP);
            Group group = new Group(e.getKey(), rate, g, v);
            groups.add(group);
            net = net.add(group.getNet());
            vat = vat.add(v);
            total = total.add(g);
        }
        return new Totals(groups, net, vat, total);
    }
}
