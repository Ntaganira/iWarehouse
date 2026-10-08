package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.Location;
import com.ntaganira.heritier.iWarehouse.entity.Product;
import com.ntaganira.heritier.iWarehouse.enums.StockStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : StockSummary.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Stock in pieces, m² and value at MAC, by product, location or status (INV-09), and the
 *               products below their reorder level (INV-10), without the database. The value of a row is
 *               the m² of each product times its MAC, rounded once per row: m² held x MAC is the stock
 *               value the MAC is kept for, so it matches the inventory account (AT-10).
 * </pre>
 */
public final class StockSummary {

    private StockSummary() {
    }

    public enum GroupBy { PRODUCT, LOCATION, STATUS }

    /** Units of one product, on one location, in one state. */
    public record Fact(UUID productId, UUID locationId, StockStatus status, long pieces, BigDecimal areaM2) {
    }

    /** One line of the summary: what it groups (product, location or status), pieces, m², value (null without MACs). */
    public record Row(String key, Product product, Location location, StockStatus status, long pieces, BigDecimal areaM2,
                      BigDecimal value) {
    }

    /** A product whose available m² is below its reorder level. */
    public record Reorder(Product product, BigDecimal availableM2, BigDecimal levelM2) {

        public BigDecimal getShortM2() {
            return levelM2.subtract(availableM2);
        }
    }

    public static List<Row> group(Collection<Fact> facts, GroupBy by, Map<UUID, Product> products, Map<UUID, Location> locations) {
        Map<String, long[]> pieces = new LinkedHashMap<>();
        Map<String, BigDecimal> areas = new HashMap<>();
        Map<String, BigDecimal> values = new HashMap<>();
        Map<String, Fact> first = new HashMap<>();
        for (Fact f : facts) {
            String key = switch (by) {
                case PRODUCT -> String.valueOf(f.productId());
                case LOCATION -> String.valueOf(f.locationId());
                case STATUS -> f.status().name();
            };
            first.putIfAbsent(key, f);
            pieces.computeIfAbsent(key, k -> new long[1])[0] += f.pieces();
            areas.merge(key, f.areaM2(), BigDecimal::add);
            Product product = products.get(f.productId());
            BigDecimal mac = product == null ? null : product.getMacPerM2();
            values.merge(key, mac == null ? BigDecimal.ZERO : f.areaM2().multiply(mac), BigDecimal::add);
        }
        List<Row> rows = new ArrayList<>();
        for (String key : pieces.keySet()) {
            Fact f = first.get(key);
            rows.add(new Row(key,
                    by == GroupBy.PRODUCT ? products.get(f.productId()) : null,
                    by == GroupBy.LOCATION ? locations.get(f.locationId()) : null,
                    by == GroupBy.STATUS ? f.status() : null,
                    pieces.get(key)[0], areas.get(key), money(values.get(key))));
        }
        Comparator<Row> order = switch (by) {
            case PRODUCT -> Comparator.comparing(r -> r.product() == null ? "" : r.product().getCode());
            case LOCATION -> Comparator.comparing(r -> r.location() == null ? "" : r.location().getCode());
            case STATUS -> Comparator.comparing(r -> r.status().ordinal());
        };
        rows.sort(order);
        return rows;
    }

    /** Pieces, m² and value of all the rows. */
    public static Row total(Collection<Row> rows) {
        long pieces = 0;
        BigDecimal area = BigDecimal.ZERO;
        BigDecimal value = BigDecimal.ZERO;
        for (Row r : rows) {
            pieces += r.pieces();
            area = area.add(r.areaM2());
            value = value.add(r.value());
        }
        return new Row("total", null, null, null, pieces, area, value);
    }

    /** Enabled products with a reorder level whose AVAILABLE m² is below it, the shortest first (INV-10). */
    public static List<Reorder> reorder(Collection<Fact> facts, Collection<Product> products) {
        Map<UUID, BigDecimal> available = new HashMap<>();
        for (Fact f : facts) {
            if (f.status() == StockStatus.AVAILABLE) {
                available.merge(f.productId(), f.areaM2(), BigDecimal::add);
            }
        }
        List<Reorder> list = new ArrayList<>();
        for (Product p : products) {
            BigDecimal level = p.getReorderLevelM2();
            if (!p.isEnabled() || level == null || level.signum() <= 0) {
                continue;
            }
            BigDecimal have = available.getOrDefault(p.getId(), BigDecimal.ZERO);
            if (have.compareTo(level) < 0) {
                list.add(new Reorder(p, have, level));
            }
        }
        list.sort(Comparator.comparing(Reorder::getShortM2).reversed().thenComparing(r -> r.product().getCode()));
        return list;
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(Costing.MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
