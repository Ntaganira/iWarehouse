package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.enums.AuthorityLevel;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : PettyCashAndAdvancesTest.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : The policy's petty cash and staff advance rules on worked examples (CMP 07, 08, 10): the top-up trigger and
 *               amount, a payment's limits, days not counted, the surprise count due; a journey's per diem, the day an
 *               advance is retired by, the rest when retired, what salary recovers, the retirement indicators; and who
 *               signs as a department's head (CMP 05, 06).
 * </pre>
 */
class PettyCashAndAdvancesTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    @Test
    void aFundBelowAQuarterOfItsFloatIsToppedUpBackToIt() {   // CMP 07
        assertThat(PettyCash.trigger(bd("200000"), bd("25"))).isEqualByComparingTo("50000");
        assertThat(PettyCash.belowTrigger(bd("49999"), bd("200000"), bd("25"))).isTrue();
        assertThat(PettyCash.belowTrigger(bd("50000"), bd("200000"), bd("25"))).isFalse();
        assertThat(PettyCash.topUpToFloat(bd("42500"), bd("200000"))).isEqualByComparingTo("157500");
        assertThat(PettyCash.topUpToFloat(bd("200000"), bd("200000"))).isEqualByComparingTo("0");
        assertThat(PettyCash.topUpToFloat(bd("210000"), bd("200000"))).isEqualByComparingTo("0");   // never negative
    }

    @Test
    void aPaymentIsAtMostFiftyThousandAndWhatTheFundHolds() {   // CMP 07
        assertThat(PettyCash.paymentRefusal(bd("50000"), bd("50000"), bd("60000"))).isNull();
        assertThat(PettyCash.paymentRefusal(bd("50001"), bd("50000"), bd("60000"))).isEqualTo("TOO_MUCH");
        assertThat(PettyCash.paymentRefusal(bd("30000"), bd("50000"), bd("20000"))).isEqualTo("NOT_ENOUGH");
        assertThat(PettyCash.paymentRefusal(bd("80000"), bd("0"), bd("90000"))).isNull();                // 0 = no limit
    }

    @Test
    void theCustodianCountsEveryWorkingDayAndFinanceBySurpriseEachMonth() {   // CMP 07
        // 12 to 16 October 2026 is Monday to Friday; the 17th and 18th are a weekend
        List<LocalDate> missing = PettyCash.daysNotCounted(List.of(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 14)),
                LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 18));
        assertThat(missing).containsExactly(LocalDate.of(2026, 10, 13), LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 16));
        assertThat(PettyCash.auditDue(false, LocalDate.of(2026, 10, 24), 25)).isFalse();
        assertThat(PettyCash.auditDue(false, LocalDate.of(2026, 10, 25), 25)).isTrue();
        assertThat(PettyCash.auditDue(true, LocalDate.of(2026, 10, 28), 25)).isFalse();
    }

    @Test
    void aJourneysPerDiemIsDaysOfMealsAndIncidentalsAndNightsOfAccommodation() {   // CMP 08
        LocalDate out = LocalDate.of(2026, 10, 20), back = LocalDate.of(2026, 10, 22);
        assertThat(Advances.days(out, back)).isEqualTo(3);
        assertThat(Advances.nights(out, back)).isEqualTo(2);
        // Upcountry: 25,000 meals + 10,000 incidentals a day, 40,000 a night
        assertThat(Advances.perDiem(bd("35000"), bd("40000"), 3, 2)).isEqualByComparingTo("185000");
        // A day trip in Kigali: 10,000 + 5,000, no night
        assertThat(Advances.perDiem(bd("15000"), bd("0"), Advances.days(out, out), Advances.nights(out, out))).isEqualByComparingTo("15000");
        // Regional, 4 days: USD 60 a day, 80 a night = 480, at 1,310.4567 = 629,019 RWF (rounded once)
        assertThat(Advances.perDiem(bd("60"), bd("80"), 4, 3)).isEqualByComparingTo("480");
        assertThat(Advances.inRwf(bd("480"), bd("1310.4567"))).isEqualByComparingTo("629019");
    }

    @Test
    void anAdvanceIsRetiredWithinTenWorkingDaysOfPaymentOrOfTheJourneysEnd() {   // CMP 08
        // Paid Friday 16 October: ten working days later is Friday 30 October
        assertThat(Advances.dueOn(LocalDate.of(2026, 10, 16), null, 10)).isEqualTo(LocalDate.of(2026, 10, 30));
        // A journey back on Thursday 22 October counts from its end
        assertThat(Advances.dueOn(LocalDate.of(2026, 10, 16), LocalDate.of(2026, 10, 22), 10)).isEqualTo(LocalDate.of(2026, 11, 5));
        // Paid after the journey: from the payment
        assertThat(Advances.dueOn(LocalDate.of(2026, 10, 23), LocalDate.of(2026, 10, 22), 1)).isEqualTo(LocalDate.of(2026, 10, 26));
        assertThat(WorkingDays.after(LocalDate.of(2026, 10, 17), 1)).isEqualTo(LocalDate.of(2026, 10, 19));   // from a Saturday
    }

    @Test
    void theRestIsReturnedOrPaidBackAndSalaryRecoversAtMostTheNetPay() {   // CMP 08
        assertThat(Advances.settlement(bd("185000"), bd("172500"))).isEqualByComparingTo("12500");      // returned
        assertThat(Advances.settlement(bd("185000"), bd("190000"))).isEqualByComparingTo("-5000");      // paid back
        assertThat(Advances.settlement(bd("125000"), bd("125000"))).isEqualByComparingTo("0");
        assertThat(Advances.deduction(bd("185000"), bd("205964"))).isEqualByComparingTo("185000");
        assertThat(Advances.deduction(bd("185000"), bd("100918"))).isEqualByComparingTo("100918");     // the rest next month
        assertThat(Advances.deduction(bd("0"), bd("100918"))).isEqualByComparingTo("0");
    }

    @Test
    void theIndicatorsCountAdvancesDueRetiredAndRetiredOnTime() {   // CMP 10
        LocalDate due = LocalDate.of(2026, 10, 30);
        Advances.Rates r = Advances.rates(List.of(new Advances.Due(due, LocalDate.of(2026, 10, 28)), new Advances.Due(due, LocalDate.of(2026, 11, 2)),
                new Advances.Due(due, null), new Advances.Due(due, due)));
        assertThat(r.due()).isEqualTo(4);
        assertThat(r.retired()).isEqualTo(3);
        assertThat(r.onTime()).isEqualTo(2);
        assertThat(r.retirementPercent()).isEqualByComparingTo("75.0");
        assertThat(r.timelinessPercent()).isEqualByComparingTo("50.0");
        assertThat(Advances.rates(List.of()).retirementPercent()).isNull();
    }

    @Test
    void aDepartmentsNamedHeadOrTheCooAndAboveSignForIt() {   // CMP 05, 06
        assertThat(Expenditure.mayActAsHead(Set.of(), 9L, null)).isTrue();                          // no head named: any holder
        assertThat(Expenditure.mayActAsHead(Set.of(4L), 4L, AuthorityLevel.DIRECTOR)).isTrue();     // the head
        assertThat(Expenditure.mayActAsHead(Set.of(4L), 9L, AuthorityLevel.DIRECTOR)).isFalse();    // another director
        assertThat(Expenditure.mayActAsHead(Set.of(4L), 9L, null)).isFalse();
        assertThat(Expenditure.mayActAsHead(Set.of(4L), 9L, AuthorityLevel.COO)).isTrue();
        assertThat(Expenditure.mayActAsHead(Set.of(4L, 5L), 5L, null)).isTrue();                    // a bill over two departments
    }
}
