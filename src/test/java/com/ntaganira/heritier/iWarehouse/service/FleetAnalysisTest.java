package com.ntaganira.heritier.iWarehouse.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : FleetAnalysisTest.java
 * - Date      : 2026. 10. 11.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The fleet reports (RPT-04): trips per driver with the breakage rate (broken over loaded), the totals, and
 *               the floats outstanding per driver, the largest first, since the oldest trip back.
 * </pre>
 */
class FleetAnalysisTest {

    private final UUID jean = UUID.randomUUID();
    private final UUID alice = UUID.randomUUID();

    @Test
    void tripsAddUpPerDriverWithTheBreakageRate() {
        List<FleetAnalysis.TripRow> trips = List.of(
                trip("TRP-1", jean, "Jean Driver", 20, 15, 1, 4, 0, "300000", "250000", "1000"),
                trip("TRP-2", jean, "Jean Driver", 4, 3, 0, 0, 1, "60000", "60000", "0"),
                trip("TRP-3", alice, "Alice Mukamana", 10, 10, 0, 0, 0, "200000", "0", "0"));
        List<FleetAnalysis.DriverRow> drivers = FleetAnalysis.byDriver(trips);
        assertThat(drivers).extracting(FleetAnalysis.DriverRow::driver).containsExactly("Alice Mukamana", "Jean Driver");
        FleetAnalysis.DriverRow j = drivers.get(1);
        assertThat(j.trips()).isEqualTo(2);
        assertThat(j.loaded()).isEqualTo(24);
        assertThat(j.broken()).isEqualTo(1);
        assertThat(j.missing()).isEqualTo(1);
        assertThat(j.getBreakageRate()).isEqualByComparingTo("4.2");                 // 1 of 24
        assertThat(j.sales()).isEqualByComparingTo("360000");
        assertThat(j.shortage()).isEqualByComparingTo("1000");
        assertThat(drivers.get(0).getBreakageRate()).isEqualByComparingTo("0.0");
        FleetAnalysis.DriverRow total = FleetAnalysis.total(trips);
        assertThat(total.trips()).isEqualTo(3);
        assertThat(total.loaded()).isEqualTo(34);
        assertThat(total.sales()).isEqualByComparingTo("560000");
        assertThat(FleetAnalysis.total(List.of()).getBreakageRate()).isNull();       // nothing loaded: no rate
    }

    @Test
    void floatsOutstandingPerDriverTheLargestFirst() {
        LocalDateTime monday = LocalDateTime.of(2026, 10, 12, 18, 0);
        List<FleetAnalysis.FloatRow> rows = FleetAnalysis.floats(List.of(
                new FleetAnalysis.Uncleared(jean, "Jean Driver", "TRP-5", monday.plusDays(1), new BigDecimal("40000")),
                new FleetAnalysis.Uncleared(alice, "Alice Mukamana", "TRP-6", monday, new BigDecimal("90000")),
                new FleetAnalysis.Uncleared(jean, "Jean Driver", "TRP-4", monday, new BigDecimal("60000"))));
        assertThat(rows).extracting(FleetAnalysis.FloatRow::driver).containsExactly("Jean Driver", "Alice Mukamana");
        assertThat(rows.get(0).trips()).isEqualTo(2);
        assertThat(rows.get(0).amount()).isEqualByComparingTo("100000");
        assertThat(rows.get(0).oldestTrip()).isEqualTo("TRP-4");
        assertThat(rows.get(0).oldestReturn()).isEqualTo(monday);
    }

    private static FleetAnalysis.TripRow trip(String number, UUID driverId, String driver, int loaded, int sold, int broken, int returned,
                                              int missing, String sales, String cash, String shortage) {
        return new FleetAnalysis.TripRow(UUID.randomUUID(), number, LocalDate.of(2026, 10, 11), "RAC123A", driverId, driver, "CLOSED",
                loaded, sold, broken, returned, missing, new BigDecimal(sales), new BigDecimal(cash), new BigDecimal(cash),
                new BigDecimal(shortage), BigDecimal.ZERO, missing);
    }
}
