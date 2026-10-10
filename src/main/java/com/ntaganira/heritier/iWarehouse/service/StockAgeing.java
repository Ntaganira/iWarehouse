package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.Location;
import com.ntaganira.heritier.iWarehouse.entity.Product;
import com.ntaganira.heritier.iWarehouse.enums.StockStatus;
import com.ntaganira.heritier.iWarehouse.enums.UnitKind;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : StockAgeing.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : How long glass has been held (RPT-02). A unit's age is the days since it was created: received for a
 *               sheet, cut for a piece or an off-cut. Off-cut ageing puts the off-cuts held in four bands (0-30, 31-90,
 *               91-180, over 180 days) per glass. Slow-moving stock is, per glass, what has been held longer than a
 *               number of days (Settings), with what was sold in those days and the last sale; the units themselves
 *               oldest first. Values are m² x the glass's MAC rounded once per row, as the stock summary values them.
 *               Pure, unit-tested.
 * </pre>
 */
public final class StockAgeing {

    private StockAgeing() {
    }

    /** A unit held: what it is, where, and the day it was created. */
    public record Unit(UUID id, String code, Product product, UnitKind kind, int widthMm, int heightMm, BigDecimal areaM2,
                       Location location, StockStatus status, LocalDate since) {

        public long days(LocalDate today) {
            return Math.max(0, ChronoUnit.DAYS.between(since, today));
        }

        /** m² x its glass's MAC (none without a MAC). */
        public BigDecimal getValue() {
            return product.getMacPerM2() == null ? null : money(areaM2.multiply(product.getMacPerM2()));
        }
    }

    /** Age bands of off-cuts, in days. */
    public enum Band {
        DAYS_0_30(30), DAYS_31_90(90), DAYS_91_180(180), OVER_180(Long.MAX_VALUE);

        private final long upTo;

        Band(long upTo) {
            this.upTo = upTo;
        }

        public static Band of(long days) {
            for (Band b : values()) {
                if (days <= b.upTo) {
                    return b;
                }
            }
            return OVER_180;
        }
    }

    /** Pieces and m² in one band. */
    public record Cell(long pieces, BigDecimal areaM2) {

        static final Cell EMPTY = new Cell(0, BigDecimal.ZERO);

        Cell plus(Unit u) {
            return new Cell(pieces + 1, areaM2.add(u.areaM2()));
        }

        public boolean isEmpty() {
            return pieces == 0;
        }
    }

    /** The off-cuts of one glass by age band (product null: the total). */
    public record AgeRow(Product product, Map<Band, Cell> bands, long pieces, BigDecimal areaM2, BigDecimal value) {

        public Cell get(Band band) {
            return bands.getOrDefault(band, Cell.EMPTY);
        }
    }

    /** One glass: all it holds, the part held longer than the days asked, what was sold in those days and when last. */
    public record SlowRow(Product product, long pieces, BigDecimal areaM2, BigDecimal value, long oldPieces, BigDecimal oldAreaM2,
                          BigDecimal oldValue, BigDecimal soldM2, LocalDate lastSold) {

        /** Nothing of this glass was sold in the days asked. */
        public boolean isUnsold() {
            return soldM2.signum() == 0;
        }
    }

    /** What a glass sold: m² in the days asked, and the last day it sold anything (null: never). */
    public record Sold(BigDecimal areaM2, LocalDate lastSold) {
    }

    /** Off-cuts per glass by age band, by glass code; the last row (product null) is the total. */
    public static List<AgeRow> offcuts(Collection<Unit> units, LocalDate today) {
        Map<UUID, List<Unit>> byProduct = new LinkedHashMap<>();
        for (Unit u : units) {
            if (u.kind() == UnitKind.OFFCUT) {
                byProduct.computeIfAbsent(u.product().getId(), k -> new ArrayList<>()).add(u);
            }
        }
        List<AgeRow> rows = new ArrayList<>();
        Map<Band, Cell> all = new EnumMap<>(Band.class);
        long allPieces = 0;
        BigDecimal allArea = BigDecimal.ZERO;
        BigDecimal allValue = BigDecimal.ZERO;
        for (List<Unit> list : byProduct.values()) {
            Map<Band, Cell> bands = new EnumMap<>(Band.class);
            BigDecimal area = BigDecimal.ZERO;
            for (Unit u : list) {
                Band b = Band.of(u.days(today));
                bands.put(b, bands.getOrDefault(b, Cell.EMPTY).plus(u));
                all.put(b, all.getOrDefault(b, Cell.EMPTY).plus(u));
                area = area.add(u.areaM2());
            }
            Product p = list.get(0).product();
            BigDecimal value = valueOf(p, area);
            rows.add(new AgeRow(p, bands, list.size(), area, value));
            allPieces += list.size();
            allArea = allArea.add(area);
            allValue = allValue.add(value);
        }
        rows.sort(Comparator.comparing(r -> r.product().getCode()));
        rows.add(new AgeRow(null, all, allPieces, allArea, allValue));
        return rows;
    }

    /**
     * Per glass held, what is older than {@code days}; only glass with such stock, the largest value first. The units
     * created more than {@code days} days ago are "old".
     */
    public static List<SlowRow> slow(Collection<Unit> units, Map<UUID, Sold> sold, LocalDate today, int days) {
        Map<UUID, List<Unit>> byProduct = new LinkedHashMap<>();
        for (Unit u : units) {
            byProduct.computeIfAbsent(u.product().getId(), k -> new ArrayList<>()).add(u);
        }
        List<SlowRow> rows = new ArrayList<>();
        for (Map.Entry<UUID, List<Unit>> e : byProduct.entrySet()) {
            Product p = e.getValue().get(0).product();
            BigDecimal area = BigDecimal.ZERO;
            BigDecimal oldArea = BigDecimal.ZERO;
            long oldPieces = 0;
            for (Unit u : e.getValue()) {
                area = area.add(u.areaM2());
                if (u.days(today) > days) {
                    oldArea = oldArea.add(u.areaM2());
                    oldPieces++;
                }
            }
            if (oldPieces == 0) {
                continue;
            }
            Sold s = sold.getOrDefault(e.getKey(), new Sold(BigDecimal.ZERO, null));
            rows.add(new SlowRow(p, e.getValue().size(), area, valueOf(p, area), oldPieces, oldArea, valueOf(p, oldArea),
                    s.areaM2(), s.lastSold()));
        }
        rows.sort(Comparator.comparing(SlowRow::oldValue).reversed().thenComparing(r -> r.product().getCode()));
        return rows;
    }

    /** Pieces, m² and value of the rows. */
    public static SlowRow total(Collection<SlowRow> rows) {
        long pieces = 0;
        long oldPieces = 0;
        BigDecimal area = BigDecimal.ZERO;
        BigDecimal value = BigDecimal.ZERO;
        BigDecimal oldArea = BigDecimal.ZERO;
        BigDecimal oldValue = BigDecimal.ZERO;
        BigDecimal sold = BigDecimal.ZERO;
        for (SlowRow r : rows) {
            pieces += r.pieces();
            oldPieces += r.oldPieces();
            area = area.add(r.areaM2());
            value = value.add(r.value());
            oldArea = oldArea.add(r.oldAreaM2());
            oldValue = oldValue.add(r.oldValue());
            sold = sold.add(r.soldM2());
        }
        return new SlowRow(null, pieces, area, value, oldPieces, oldArea, oldValue, sold, null);
    }

    /** The units held longer than {@code days}, oldest first, then by code. */
    public static List<Unit> olderThan(Collection<Unit> units, LocalDate today, int days) {
        return oldestFirst(units.stream().filter(u -> u.days(today) > days).toList());
    }

    /** The units oldest first, then by code. */
    public static List<Unit> oldestFirst(Collection<Unit> units) {
        return units.stream().sorted(Comparator.comparing(Unit::since).thenComparing(Unit::code)).toList();
    }

    private static BigDecimal valueOf(Product p, BigDecimal area) {
        return p.getMacPerM2() == null ? money(BigDecimal.ZERO) : money(area.multiply(p.getMacPerM2()));
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(Costing.MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
