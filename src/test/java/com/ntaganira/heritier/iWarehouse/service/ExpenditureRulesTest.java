package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.enums.AuthorityDecision;
import com.ntaganira.heritier.iWarehouse.enums.ExpenseNature;
import com.ntaganira.heritier.iWarehouse.enums.SourcingMethod;
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
 * - File      : ExpenditureRulesTest.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : The Cost Management &amp; Expenditure Control Policy's rules on worked examples: sourcing by value (CMP 06),
 *               the matrix row that approves a bill (CMP 05), the VAT ceiling, the requisition's tolerance and split
 *               purchases (CMP 06, 09).
 * </pre>
 */
class ExpenditureRulesTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static final Expenditure.Tiers POLICY = new Expenditure.Tiers(bd("100000"), bd("1000000"), bd("5000000"));

    @Test
    void aPurchaseIsSourcedByItsValue() {
        assertThat(Expenditure.sourcing(bd("99999"), POLICY)).isEqualTo(SourcingMethod.SINGLE_QUOTE);
        assertThat(Expenditure.sourcing(bd("100000"), POLICY)).isEqualTo(SourcingMethod.THREE_QUOTES);
        assertThat(Expenditure.sourcing(bd("999999.99"), POLICY)).isEqualTo(SourcingMethod.THREE_QUOTES);
        assertThat(Expenditure.sourcing(bd("1000000"), POLICY)).isEqualTo(SourcingMethod.RFQ);
        assertThat(Expenditure.sourcing(bd("5000000"), POLICY)).isEqualTo(SourcingMethod.TENDER);
        assertThat(SourcingMethod.SINGLE_QUOTE.quotesNeeded()).isEqualTo(1);
        assertThat(SourcingMethod.RFQ.quotesNeeded()).isEqualTo(3);
        assertThat(SourcingMethod.RFQ.needsReference()).isTrue();
        assertThat(SourcingMethod.THREE_QUOTES.needsReference()).isFalse();
    }

    @Test
    void theMatrixRowFollowsEmergencyThenBudgetThenNature() {
        assertThat(Expenditure.decision(false, true, ExpenseNature.OPEX)).isEqualTo(AuthorityDecision.EXPENSE_OPEX);
        assertThat(Expenditure.decision(false, true, ExpenseNature.CAPEX)).isEqualTo(AuthorityDecision.EXPENSE_CAPEX);
        assertThat(Expenditure.decision(false, false, ExpenseNature.CAPEX)).isEqualTo(AuthorityDecision.EXPENSE_UNBUDGETED);
        assertThat(Expenditure.decision(true, false, ExpenseNature.OPEX)).isEqualTo(AuthorityDecision.EXPENSE_EMERGENCY);
    }

    @Test
    void vatIsAtMost18PercentRoundedUpToTheFranc() {
        assertThat(Expenditure.maxVat(bd("200000"))).isEqualByComparingTo("36000");
        assertThat(Expenditure.maxVat(bd("1001"))).isEqualByComparingTo("181");         // 180.18 up to 181
        assertThat(Expenditure.maxVat(bd("0.50"))).isEqualByComparingTo("1");
    }

    @Test
    void anOrderStaysWithinItsRequisitionAndTheTolerance() {
        assertThat(Expenditure.withinRequisition(bd("110000"), bd("100000"), bd("10"))).isTrue();
        assertThat(Expenditure.withinRequisition(bd("110000.01"), bd("100000"), bd("10"))).isFalse();
        assertThat(Expenditure.withinRequisition(bd("100000"), bd("100000"), bd("0"))).isTrue();
        assertThat(Expenditure.needsReview(bd("500000"), bd("500000"))).isTrue();
        assertThat(Expenditure.needsReview(bd("499999"), bd("500000"))).isFalse();
        assertThat(Expenditure.needsReview(bd("9000000"), BigDecimal.ZERO)).isFalse();
        assertThat(Expenditure.cheapest(List.of(bd("3"), bd("1"), bd("2")), x -> x)).contains(bd("1"));
    }

    private static SplitPurchases.Purchase p(String number, UUID supplier, int day, String amount) {
        return new SplitPurchases.Purchase(number, "/" + number, supplier, supplier.toString().substring(0, 4), LocalDate.of(2026, 10, day), bd(amount));
    }

    @Test
    void ordersSplitUnderAThresholdAreFound() {
        UUID office = UUID.randomUUID(), garage = UUID.randomUUID();
        List<BigDecimal> tiers = List.of(bd("100000"), bd("1000000"), bd("5000000"));
        List<SplitPurchases.Flag> flags = SplitPurchases.detect(List.of(
                // Three bills of 60,000-90,000 to the office supplier in four days: 210,000, above the three-quote threshold
                p("EXP-1", office, 10, "60000"), p("EXP-2", office, 12, "90000"), p("EXP-3", office, 14, "60000"),
                // A month later, one small bill alone: not split
                p("EXP-4", office, 28, "50000"),
                // The garage: two bills of 600,000 a day apart (1,200,000, above the RFQ threshold), reported once at the highest
                p("EXP-5", garage, 1, "600000"), p("EXP-6", garage, 2, "600000")), 7, tiers);
        assertThat(flags).hasSize(2);
        SplitPurchases.Flag garageFlag = flags.stream().filter(f -> f.supplierId().equals(garage)).findFirst().orElseThrow();
        assertThat(garageFlag.threshold()).isEqualByComparingTo("1000000");
        assertThat(garageFlag.getNumbers()).isEqualTo("EXP-5, EXP-6");
        SplitPurchases.Flag officeFlag = flags.stream().filter(f -> f.supplierId().equals(office)).findFirst().orElseThrow();
        assertThat(officeFlag.threshold()).isEqualByComparingTo("100000");
        assertThat(officeFlag.getTotal()).isEqualByComparingTo("210000");
        assertThat(officeFlag.getFrom()).isEqualTo(LocalDate.of(2026, 10, 10));
        assertThat(officeFlag.getTo()).isEqualTo(LocalDate.of(2026, 10, 14));
        // Purchases each above the threshold, or too far apart, are not splitting
        assertThat(SplitPurchases.detect(List.of(p("A", office, 1, "150000"), p("B", office, 2, "150000")), 7, List.of(bd("100000")))).isEmpty();
        assertThat(SplitPurchases.detect(List.of(p("A", office, 1, "60000"), p("B", office, 20, "60000")), 7, List.of(bd("100000")))).isEmpty();
    }
}
