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
        TRIPS, DRIVERS, VEHICLES, DISCREPANCIES, FLOATS
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

    /**
     * A vehicle in the period: its trips back, the km they drove and what they sold, and what it cost to run (its posted costs
     * dated in the period, without VAT: fuel and its litres, repairs and maintenance, other).
     */
    public record VehicleRow(UUID vehicleId, String plate, int trips, long km, BigDecimal sales, BigDecimal fuel, BigDecimal litres,
                             BigDecimal maintenance, BigDecimal other) {

        public BigDecimal getTotalCost() {
            return fuel.add(maintenance).add(other);
        }

        /** RWF of running costs per km driven; none without km. */
        public BigDecimal getCostPerKm() {
            return VehicleCosts.perKm(getTotalCost(), km);
        }
    }

    /** A trip back, for the vehicles: whose, the km it drove (null: no readings) and what it sold. */
    public record TripLeg(UUID vehicleId, String plate, Integer km, BigDecimal sales) {
    }

    /** Costs of a vehicle in one group (FUEL, MAINTENANCE, OTHER), without VAT, and the litres of fuel. */
    public record CostLine(UUID vehicleId, String plate, String group, BigDecimal amount, BigDecimal litres) {
    }

    /** The trips and costs per vehicle, by plate. */
    public static List<VehicleRow> byVehicle(List<TripLeg> trips, List<CostLine> costs) {
        Map<UUID, String> plates = new HashMap<>();
        Map<UUID, int[]> count = new HashMap<>();
        Map<UUID, long[]> km = new HashMap<>();
        Map<UUID, BigDecimal[]> money = new HashMap<>();            // sales, fuel, litres, maintenance, other
        for (TripLeg t : trips) {
            plates.put(t.vehicleId(), t.plate());
            count.computeIfAbsent(t.vehicleId(), k -> new int[1])[0]++;
            km.computeIfAbsent(t.vehicleId(), k -> new long[1])[0] += t.km() == null ? 0 : t.km();
            sums(money, t.vehicleId())[0] = sums(money, t.vehicleId())[0].add(t.sales());
        }
        for (CostLine c : costs) {
            plates.put(c.vehicleId(), c.plate());
            BigDecimal[] m = sums(money, c.vehicleId());
            switch (c.group()) {
                case "FUEL" -> {
                    m[1] = m[1].add(c.amount());
                    m[2] = m[2].add(c.litres());
                }
                case "MAINTENANCE" -> m[3] = m[3].add(c.amount());
                default -> m[4] = m[4].add(c.amount());
            }
        }
        List<VehicleRow> rows = new ArrayList<>();
        plates.forEach((id, plate) -> {
            BigDecimal[] m = sums(money, id);
            rows.add(new VehicleRow(id, plate, count.containsKey(id) ? count.get(id)[0] : 0, km.containsKey(id) ? km.get(id)[0] : 0,
                    m[0], m[1], m[2], m[3], m[4]));
        });
        rows.sort(Comparator.comparing(VehicleRow::plate));
        return rows;
    }

    /** Every vehicle added up (the totals row). */
    public static VehicleRow totalVehicles(List<VehicleRow> rows) {
        int trips = 0;
        long km = 0;
        BigDecimal sales = BigDecimal.ZERO, fuel = BigDecimal.ZERO, litres = BigDecimal.ZERO, maintenance = BigDecimal.ZERO, other = BigDecimal.ZERO;
        for (VehicleRow r : rows) {
            trips += r.trips();
            km += r.km();
            sales = sales.add(r.sales());
            fuel = fuel.add(r.fuel());
            litres = litres.add(r.litres());
            maintenance = maintenance.add(r.maintenance());
            other = other.add(r.other());
        }
        return new VehicleRow(null, null, trips, km, sales, fuel, litres, maintenance, other);
    }

    private static BigDecimal[] sums(Map<UUID, BigDecimal[]> money, UUID id) {
        return money.computeIfAbsent(id, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
    }

    /** A trip back whose float is not cleared. */
    public record Uncleared(UUID driverId, String driver, String trip, LocalDateTime returnedAt, BigDecimal cash) {
    }

    /** What a driver still holds from trips back and not cleared: how many, how much, since when. */
    public record FloatRow(UUID driverId, String driver, int trips, BigDecimal amount, LocalDateTime oldestReturn, String oldestTrip) {
    }

    /** A load that left: when, and whether its vehicle had a trip before it and when that one was closed. */
    public record Departure(LocalDateTime departedAt, boolean hadPrevious, LocalDateTime previousClosedAt) {

        /** Its previous trip was cleared and closed before it left (or it had none). */
        public boolean afterCleared() {
            return !hadPrevious || (previousClosedAt != null && !previousClosedAt.isAfter(departedAt));
        }
    }

    /** Loads reconciled before the next (target 100 %) and van stock shortages against the tolerance (Annex D). */
    public record LoadKpis(int loads, int afterCleared, BigDecimal clearedPercent, int loaded, int missing, BigDecimal shortagePercent,
                           BigDecimal tolerance) {

        public boolean isShortageBreached() {
            return shortagePercent != null && shortagePercent.compareTo(tolerance) > 0;
        }

        public boolean isAllCleared() {
            return loads == afterCleared;
        }
    }

    public static LoadKpis loads(List<Departure> departures, int loaded, int missing, BigDecimal tolerance) {
        int cleared = (int) departures.stream().filter(Departure::afterCleared).count();
        BigDecimal shortage = loaded == 0 ? null : BigDecimal.valueOf(missing * 100L).divide(BigDecimal.valueOf(loaded), 2, RoundingMode.HALF_UP);
        return new LoadKpis(departures.size(), cleared, rate(cleared, departures.size()), loaded, missing, shortage, tolerance);
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
