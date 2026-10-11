package com.ntaganira.heritier.iWarehouse.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : FleetAnalysis.java
 * - Date      : 2026. 10. 11.
 * - User      : Hntaganira
 * - Desc      : The fleet reports (RPT-04), pure: the trips back in a period with what became of their units, their sales
 *               (cash and mobile money) and their float count; the same per driver with the breakage rate (units broken on
 *               the road over units loaded); the end-of-day discrepancies (audit case units); the floats outstanding per
 *               driver (trips back with cash not counted yet).
 * </pre>
 */
public final class FleetAnalysis {

    private FleetAnalysis() {
    }

    public enum View {
        TRIPS, DRIVERS, DISCREPANCIES, FLOATS
    }

    /** A trip back: what left, what became of it, what it sold, how its float was counted (counted null: not cleared). */
    public record TripRow(UUID tripId, String number, LocalDate date, String vehicle, UUID driverId, String driver, String status,
                          int loaded, int sold, int broken, int returned, int missing, BigDecimal sales, BigDecimal cash,
                          BigDecimal counted, BigDecimal shortage, BigDecimal overage, int discrepancies) {

        public boolean isCleared() {
            return counted != null;
        }
    }

    /** A driver's trips back in the period, added up. */
    public record DriverRow(UUID driverId, String driver, int trips, int loaded, int sold, int broken, int missing, BigDecimal sales,
                            BigDecimal cash, BigDecimal shortage, BigDecimal overage) {

        /** Units broken on the road over units loaded, in %, one decimal; none without a load. */
        public BigDecimal getBreakageRate() {
            return rate(broken, loaded);
        }
    }

    /** A unit of an audit case: missing or unexpected, how it was settled (outcome null: still open). */
    public record DiscrepancyRow(UUID caseId, String caseNumber, String caseStatus, UUID tripId, String trip, LocalDate date, String driver,
                                 String unitCode, String kind, BigDecimal value, String outcome) {
    }

    /** A trip back whose float is not cleared. */
    public record Uncleared(UUID driverId, String driver, String trip, LocalDateTime returnedAt, BigDecimal cash) {
    }

    /** What a driver still holds from trips back and not cleared: how many, how much, since when. */
    public record FloatRow(UUID driverId, String driver, int trips, BigDecimal amount, LocalDateTime oldestReturn, String oldestTrip) {
    }

    public static BigDecimal rate(int part, int whole) {
        return whole == 0 ? null : BigDecimal.valueOf(part * 100L).divide(BigDecimal.valueOf(whole), 1, RoundingMode.HALF_UP);
    }

    /** The trips per driver, by name. */
    public static List<DriverRow> byDriver(List<TripRow> trips) {
        Map<UUID, List<TripRow>> grouped = new LinkedHashMap<>();
        trips.forEach(t -> grouped.computeIfAbsent(t.driverId(), k -> new ArrayList<>()).add(t));
        List<DriverRow> rows = new ArrayList<>();
        grouped.forEach((driverId, list) -> rows.add(add(driverId, list.get(0).driver(), list)));
        rows.sort(Comparator.comparing(DriverRow::driver, String.CASE_INSENSITIVE_ORDER));
        return rows;
    }

    /** Every trip added up (the totals row). */
    public static DriverRow total(List<TripRow> trips) {
        return add(null, null, trips);
    }

    private static DriverRow add(UUID driverId, String driver, List<TripRow> trips) {
        int loaded = 0, sold = 0, broken = 0, missing = 0;
        BigDecimal sales = BigDecimal.ZERO, cash = BigDecimal.ZERO, shortage = BigDecimal.ZERO, overage = BigDecimal.ZERO;
        for (TripRow t : trips) {
            loaded += t.loaded();
            sold += t.sold();
            broken += t.broken();
            missing += t.missing();
            sales = sales.add(t.sales());
            cash = cash.add(t.cash());
            shortage = shortage.add(t.shortage());
            overage = overage.add(t.overage());
        }
        return new DriverRow(driverId, driver, trips.size(), loaded, sold, broken, missing, sales, cash, shortage, overage);
    }

    /** The floats outstanding per driver, the largest first. */
    public static List<FloatRow> floats(List<Uncleared> uncleared) {
        Map<UUID, List<Uncleared>> grouped = new LinkedHashMap<>();
        uncleared.forEach(u -> grouped.computeIfAbsent(u.driverId(), k -> new ArrayList<>()).add(u));
        List<FloatRow> rows = new ArrayList<>();
        grouped.forEach((driverId, list) -> {
            Uncleared oldest = list.stream().min(Comparator.comparing(Uncleared::returnedAt)).orElseThrow();
            BigDecimal amount = list.stream().map(Uncleared::cash).reduce(BigDecimal.ZERO, BigDecimal::add);
            rows.add(new FloatRow(driverId, oldest.driver(), list.size(), amount, oldest.returnedAt(), oldest.trip()));
        });
        rows.sort(Comparator.comparing(FloatRow::amount).reversed().thenComparing(FloatRow::driver, String.CASE_INSENSITIVE_ORDER));
        return rows;
    }
}
