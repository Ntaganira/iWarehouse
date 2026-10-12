package com.ntaganira.heritier.iWarehouse.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : VehicleCostsTest.java
 * - Date      : 2026. 10. 12.
 * - User      : Hntaganira
 * - Desc      : The sums of vehicle costs and service plans: the approval limit, the VAT an amount can hold, what is left of
 *               a trip's cash, the cost per km, when a service falls due (by km, by date, whichever first), and the costs per
 *               vehicle of the fleet report.
 * </pre>
 */
class VehicleCostsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 12);

    @Test
    void aCostAboveTheLimitWaitsForApprovalAndOneAtItPostsAtOnce() {
        assertThat(VehicleCosts.needsApproval(new BigDecimal("100001"), new BigDecimal("100000"))).isTrue();
        assertThat(VehicleCosts.needsApproval(new BigDecimal("100000"), new BigDecimal("100000"))).isFalse();
        assertThat(VehicleCosts.needsApproval(new BigDecimal("1"), BigDecimal.ZERO)).isTrue();          // 0: every cost
    }

    @Test
    void anAmountHoldsAtMostItsEighteenPercentVat() {
        assertThat(VehicleCosts.maxVat(new BigDecimal("118000"))).isEqualByComparingTo("18000");
        assertThat(VehicleCosts.maxVat(new BigDecimal("80000"))).isEqualByComparingTo("12203");         // 12,203.39 to the RWF
        assertThat(VehicleCosts.maxVat(new BigDecimal("1"))).isEqualByComparingTo("0");
    }

    @Test
    void aDriverPaysAtMostWhatIsLeftOfTheTripsCash() {
        assertThat(VehicleCosts.floatLeft(new BigDecimal("195008"), new BigDecimal("45000"))).isEqualByComparingTo("150008");
        assertThat(VehicleCosts.floatLeft(new BigDecimal("40000"), new BigDecimal("45000"))).isEqualByComparingTo("0");
    }

    @Test
    void theCostPerKmIsRoundedToTheRwfAndNoneWithoutKm() {
        assertThat(VehicleCosts.perKm(new BigDecimal("148000"), 420)).isEqualByComparingTo("352");     // 352.38
        assertThat(VehicleCosts.perKm(new BigDecimal("148000"), 0)).isNull();
    }

    @Test
    void aServiceIsDueAtItsKmOrOnItsDayWhicheverComesFirst() {
        // Oil change every 5,000 km or 3 months, last done on 01/09/2026 at 42,000 km
        ServicePlans.Status ok = ServicePlans.of(5000, 3, LocalDate.of(2026, 9, 1), 42000, 44000, TODAY, 500, 30);
        assertThat(ok.stage()).isEqualTo(ServicePlans.Stage.OK);
        assertThat(ok.nextKm()).isEqualTo(47000);
        assertThat(ok.nextDate()).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(ok.kmLeft()).isEqualTo(3000);
        assertThat(ok.daysLeft()).isEqualTo(50);

        // Within 500 km of its km: due soon; at or past it: due, whatever the date says
        assertThat(ServicePlans.of(5000, 3, LocalDate.of(2026, 9, 1), 42000, 46600, TODAY, 500, 30).stage()).isEqualTo(ServicePlans.Stage.SOON);
        assertThat(ServicePlans.of(5000, 3, LocalDate.of(2026, 9, 1), 42000, 47000, TODAY, 500, 30).stage()).isEqualTo(ServicePlans.Stage.DUE);

        // By date: within the alert days soon, on its day due
        assertThat(ServicePlans.of(5000, 3, LocalDate.of(2026, 7, 20), 42000, 43000, TODAY, 500, 30).stage()).isEqualTo(ServicePlans.Stage.SOON);
        assertThat(ServicePlans.of(null, 3, LocalDate.of(2026, 7, 12), null, null, TODAY, 500, 30).stage()).isEqualTo(ServicePlans.Stage.DUE);
    }

    @Test
    void withoutAnOdometerOnlyTheDateCanMakeAServiceDue() {
        ServicePlans.Status s = ServicePlans.of(5000, null, LocalDate.of(2026, 1, 1), 42000, null, TODAY, 500, 30);
        assertThat(s.stage()).isEqualTo(ServicePlans.Stage.OK);
        assertThat(s.kmLeft()).isNull();
        assertThat(s.nextDate()).isNull();
        assertThat(ServicePlans.latest(45210, 45400)).isEqualTo(45400);
        assertThat(ServicePlans.latest(null, 45400)).isEqualTo(45400);
        assertThat(ServicePlans.latest(45210, null)).isEqualTo(45210);
    }

    @Test
    void anAlertKeyChangesWhenTheServiceIsDoneOrItsStageMoves() {
        UUID plan = UUID.randomUUID();
        String soon = ServicePlans.alertKey(plan, LocalDate.of(2026, 9, 1), 42000, ServicePlans.Stage.SOON);
        assertThat(soon).startsWith(ServicePlans.ALERT_PREFIX + plan);
        assertThat(ServicePlans.alertKey(plan, LocalDate.of(2026, 9, 1), 42000, ServicePlans.Stage.DUE)).isNotEqualTo(soon);
        assertThat(ServicePlans.alertKey(plan, LocalDate.of(2026, 10, 12), 47100, ServicePlans.Stage.SOON)).isNotEqualTo(soon);
    }

    @Test
    void theFleetReportAddsTripsAndCostsPerVehicle() {
        UUID rac = UUID.randomUUID();
        UUID rab = UUID.randomUUID();
        List<FleetAnalysis.VehicleRow> rows = FleetAnalysis.byVehicle(
                List.of(new FleetAnalysis.TripLeg(rac, "RAC123A", 86, new BigDecimal("390016")),
                        new FleetAnalysis.TripLeg(rac, "RAC123A", null, new BigDecimal("195008"))),
                List.of(new FleetAnalysis.CostLine(rac, "RAC123A", "FUEL", new BigDecimal("45000"), new BigDecimal("30")),
                        new FleetAnalysis.CostLine(rac, "RAC123A", "MAINTENANCE", new BigDecimal("100000"), BigDecimal.ZERO),
                        new FleetAnalysis.CostLine(rab, "RAB777C", "OTHER", new BigDecimal("5000"), BigDecimal.ZERO)));
        assertThat(rows).extracting(FleetAnalysis.VehicleRow::plate).containsExactly("RAB777C", "RAC123A");
        FleetAnalysis.VehicleRow r = rows.get(1);
        assertThat(r.trips()).isEqualTo(2);
        assertThat(r.km()).isEqualTo(86);                                                                // a trip without readings adds no km
        assertThat(r.sales()).isEqualByComparingTo("585024");
        assertThat(r.getTotalCost()).isEqualByComparingTo("145000");
        assertThat(r.litres()).isEqualByComparingTo("30");
        assertThat(r.getCostPerKm()).isEqualByComparingTo("1686");                                        // 1,686.05
        FleetAnalysis.VehicleRow onlyCosts = rows.get(0);
        assertThat(onlyCosts.trips()).isZero();
        assertThat(onlyCosts.getCostPerKm()).isNull();
        FleetAnalysis.VehicleRow total = FleetAnalysis.totalVehicles(rows);
        assertThat(total.getTotalCost()).isEqualByComparingTo("150000");
        assertThat(total.trips()).isEqualTo(2);
    }
}
