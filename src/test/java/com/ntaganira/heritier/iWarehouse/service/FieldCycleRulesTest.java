package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.enums.PaymentMethod;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : FieldCycleRulesTest.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : The field cycle's pure rules (M15): a van count against what should be on board (SDP 6), the credit a customer
 *               may take on the road (decision D8), the phone's payments with credit, and the fleet KPIs of Annex D (loads
 *               after the last trip was cleared, van shortages against the tolerance).
 * </pre>
 */
class FieldCycleRulesTest {

    @Test
    void aVanCountGivesWhatWasFoundMissingAndNotExpected() {   // SDP 6
        VanCounts.Result r = VanCounts.compare(List.of("U-WH-000101", "U-WH-000102", "U-WH-000103"),
                List.of(" u-wh-000102", "U-WH-000101", "U-WH-000101", "", "U-WH-000999"));
        assertThat(r.found()).containsExactly("U-WH-000101", "U-WH-000102");
        assertThat(r.missing()).containsExactly("U-WH-000103");
        assertThat(r.extra()).containsExactly("U-WH-000999");                  // each once, upper case, blanks skipped
        assertThat(r.isClean()).isFalse();
        assertThat(VanCounts.compare(List.of("U-WH-000101"), List.of("U-WH-000101")).isClean()).isTrue();
        assertThat(VanCounts.compare(List.of("U-WH-000101"), null).missing()).containsExactly("U-WH-000101");
        // As a scanner types them: one per line, or separated by commas or spaces
        assertThat(VanCounts.codes("u-wh-000101\nU-WH-000102, U-WH-000103;U-WH-000104  ")).containsExactly(
                "U-WH-000101", "U-WH-000102", "U-WH-000103", "U-WH-000104");
        assertThat(VanCounts.codes(null)).isEmpty();
    }

    @Test
    void theCreditLeftOnTheRoadIsTheLimitLessWhatIsOwed() {   // decision D8
        assertThat(FieldCredit.left(true, new BigDecimal("500000"), new BigDecimal("420000"), false)).isEqualByComparingTo("80000");
        assertThat(FieldCredit.left(true, new BigDecimal("500000"), new BigDecimal("650000"), false)).isEqualByComparingTo("0");
        assertThat(FieldCredit.left(true, new BigDecimal("500000"), null, false)).isEqualByComparingTo("500000");
        // Blocked while an invoice is overdue past the grace; none without credit or without a limit
        assertThat(FieldCredit.left(true, new BigDecimal("500000"), BigDecimal.ZERO, true)).isEqualByComparingTo("0");
        assertThat(FieldCredit.left(false, new BigDecimal("500000"), BigDecimal.ZERO, false)).isNull();
        assertThat(FieldCredit.left(true, null, BigDecimal.ZERO, false)).isNull();
        assertThat(FieldCredit.left(true, BigDecimal.ZERO, BigDecimal.ZERO, false)).isNull();

        assertThat(FieldCredit.problem(false, new BigDecimal("1000"), new BigDecimal("80000"))).hasValueSatisfying(p -> assertThat(p).contains("not given"));
        assertThat(FieldCredit.problem(true, new BigDecimal("1000"), null)).hasValueSatisfying(p -> assertThat(p).contains("no credit"));
        assertThat(FieldCredit.problem(true, new BigDecimal("80001"), new BigDecimal("80000")))
                .hasValueSatisfying(p -> assertThat(p).isEqualTo("credit of 80001 beyond the 80000 left"));
        assertThat(FieldCredit.problem(true, new BigDecimal("80000"), new BigDecimal("80000"))).isEmpty();
        assertThat(FieldCredit.problem(false, BigDecimal.ZERO, null)).isEmpty();      // no credit asked
    }

    @Test
    void thePhoneTakesCreditOnlyWhenFieldCreditIsOn() {   // decision D8, MPOS-04
        List<MobileSales.Payment> mixed = List.of(new MobileSales.Payment(PaymentMethod.CREDIT, new BigDecimal("20000"), null),
                new MobileSales.Payment(PaymentMethod.CASH, new BigDecimal("5920"), null));
        assertThat(MobileSales.paymentProblem(mixed, new BigDecimal("25920"))).hasValue("CREDIT is not taken on the road");
        assertThat(MobileSales.paymentProblem(mixed, new BigDecimal("25920"), true)).isEmpty();
        assertThat(MobileSales.paymentProblem(mixed, new BigDecimal("30000"), true)).hasValue("paid 25920, total 30000");
        assertThat(MobileSales.paymentProblem(List.of(new MobileSales.Payment(PaymentMethod.CARD, new BigDecimal("25920"), "X")),
                new BigDecimal("25920"), true)).hasValue("CARD is not taken on the road");
    }

    @Test
    void theFleetKpisCountLoadsAfterTheLastTripWasClearedAndShortages() {   // Annex D, N.21
        LocalDateTime monday = LocalDateTime.of(2026, 10, 12, 7, 0);
        List<FleetAnalysis.Departure> departures = List.of(
                new FleetAnalysis.Departure(monday, false, null),                                  // the vehicle's first trip
                new FleetAnalysis.Departure(monday.plusDays(2), true, monday.plusDays(1)),         // the last closed the day before
                new FleetAnalysis.Departure(monday.plusDays(3), true, null),                       // the last never closed
                new FleetAnalysis.Departure(monday.plusDays(4), true, monday.plusDays(5)));        // closed after this one left
        FleetAnalysis.LoadKpis k = FleetAnalysis.loads(departures, 400, 3, new BigDecimal("0.5"));
        assertThat(k.loads()).isEqualTo(4);
        assertThat(k.afterCleared()).isEqualTo(2);
        assertThat(k.clearedPercent()).isEqualByComparingTo("50.0");
        assertThat(k.isAllCleared()).isFalse();
        assertThat(k.shortagePercent()).isEqualByComparingTo("0.75");                            // 3 of 400 loaded
        assertThat(k.isShortageBreached()).isTrue();
        FleetAnalysis.LoadKpis none = FleetAnalysis.loads(List.of(), 0, 0, new BigDecimal("0.5"));
        assertThat(none.shortagePercent()).isNull();
        assertThat(none.isShortageBreached()).isFalse();
        assertThat(none.isAllCleared()).isTrue();
    }
}
