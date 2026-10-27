package com.ntaganira.heritier.iWarehouse.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : SplitPurchases.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Splitting purchases to stay under a threshold (CMP 06, 09: prohibited): per supplier, two or more orders
 *               or bills within the Settings' days, each under a threshold, whose sum reaches it. The highest threshold
 *               crossed is reported first, and a purchase is reported once. Pure and tested.
 * </pre>
 */
public final class SplitPurchases {

    private SplitPurchases() {
    }

    /** An order or a bill: its number and link, supplier, day and amount in RWF. */
    public record Purchase(String number, String link, UUID supplierId, String supplierName, LocalDate day, BigDecimal amount) {
    }

    /** Purchases that together reach a threshold none of them reaches alone. */
    public record Flag(UUID supplierId, String supplierName, BigDecimal threshold, List<Purchase> purchases) {

        public BigDecimal getTotal() {
            return purchases.stream().map(Purchase::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        public LocalDate getFrom() {
            return purchases.get(0).day();
        }

        public LocalDate getTo() {
            return purchases.get(purchases.size() - 1).day();
        }

        public String getNumbers() {
            return String.join(", ", purchases.stream().map(Purchase::number).toList());
        }
    }

    public static List<Flag> detect(Collection<Purchase> purchases, int windowDays, Collection<BigDecimal> thresholds) {
        List<BigDecimal> limits = thresholds.stream().filter(t -> t != null && t.signum() > 0).distinct()
                .sorted(Comparator.reverseOrder()).toList();
        Map<UUID, List<Purchase>> bySupplier = new LinkedHashMap<>();
        for (Purchase p : purchases) {
            bySupplier.computeIfAbsent(p.supplierId(), k -> new ArrayList<>()).add(p);
        }
        List<Flag> flags = new ArrayList<>();
        for (List<Purchase> list : bySupplier.values()) {
            list.sort(Comparator.comparing(Purchase::day).thenComparing(Purchase::number));
            Set<String> reported = new HashSet<>();
            for (BigDecimal limit : limits) {
                List<Purchase> under = list.stream().filter(p -> p.amount().compareTo(limit) < 0 && !reported.contains(p.number())).toList();
                int i = 0;
                while (i < under.size()) {
                    LocalDate end = under.get(i).day().plusDays(Math.max(0, windowDays));
                    int j = i;
                    BigDecimal sum = BigDecimal.ZERO;
                    while (j < under.size() && !under.get(j).day().isAfter(end)) {
                        sum = sum.add(under.get(j).amount());
                        j++;
                    }
                    if (j - i >= 2 && sum.compareTo(limit) >= 0) {
                        List<Purchase> group = new ArrayList<>(under.subList(i, j));
                        group.forEach(p -> reported.add(p.number()));
                        Purchase first = group.get(0);
                        flags.add(new Flag(first.supplierId(), first.supplierName(), limit, group));
                        i = j;
                    } else {
                        i++;
                    }
                }
            }
        }
        flags.sort(Comparator.comparing((Flag f) -> f.getFrom()).thenComparing(Flag::supplierName));
        return flags;
    }
}
