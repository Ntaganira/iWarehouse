package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.enums.AllocationMethod;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Landed cost rules (PRC-04, PRC-05): crate bases, exact splitting, cost per m², MAC after landed cost. */
class LandedCostTest {

    private static List<BigDecimal> dec(String... values) {
        return java.util.Arrays.stream(values).map(BigDecimal::new).toList();
    }

    @Test
    void splitAddsUpExactlyAndGivesLeftoverCentsToTheLargestRemainders() {
        assertThat(LandedCost.split(new BigDecimal("100.00"), dec("1", "1", "1"), 2))
                .containsExactly(new BigDecimal("33.34"), new BigDecimal("33.33"), new BigDecimal("33.33"));
        // 166.666 / 333.333 / 500: the cent left over goes to the first part, whose remainder is largest
        assertThat(LandedCost.split(new BigDecimal("10"), dec("1", "2", "3"), 2))
                .containsExactly(new BigDecimal("1.67"), new BigDecimal("3.33"), new BigDecimal("5.00"));
        // Two crates of AT-like sizes: 28.89 m² and 8.9304 m² share 730,530 RWF
        assertThat(LandedCost.split(new BigDecimal("730530"), dec("28.8900", "8.9304"), 2))
                .containsExactly(new BigDecimal("558032.48"), new BigDecimal("172497.52"));
    }

    @Test
    void aCreditSplitsTheSameWayNegated() {
        assertThat(LandedCost.split(new BigDecimal("-100.00"), dec("1", "1", "1"), 2))
                .containsExactly(new BigDecimal("-33.34"), new BigDecimal("-33.33"), new BigDecimal("-33.33"));
    }

    @Test
    void zeroWeightsCannotBeSplitOver() {
        assertThatThrownBy(() -> LandedCost.split(BigDecimal.TEN, dec("0", "0"), 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LandedCost.split(BigDecimal.TEN, List.of(), 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aCrateCountsItsBrokenSheetsInEveryBasis() {
        // 3 good + 1 broken sheets of 3210 x 2250, 6,091.05 RWF per m², 15 kg per m²
        LandedCost.CrateBasis crate = new LandedCost.CrateBasis(3, 1, new BigDecimal("7.2225"),
                new BigDecimal("6091.0500"), new BigDecimal("15.00"));
        assertThat(crate.pieces()).isEqualTo(4);
        assertThat(crate.basis(AllocationMethod.AREA)).isEqualByComparingTo("28.89");
        assertThat(crate.basis(AllocationMethod.VALUE)).isEqualByComparingTo("175970.4345");
        assertThat(crate.basis(AllocationMethod.WEIGHT)).isEqualByComparingTo("433.35");
    }

    @Test
    void aCrateAmountIsSharedEquallyBetweenItsSheets() {
        assertThat(LandedCost.perPiece(new BigDecimal("558032.48"), 4)).containsOnly(new BigDecimal("139508.12"));
        assertThat(LandedCost.perPiece(new BigDecimal("100.00"), 3))
                .containsExactly(new BigDecimal("33.34"), new BigDecimal("33.33"), new BigDecimal("33.33"));
    }

    @Test
    void postingTotalIsRoundedOnceToTheBaseCurrency() {
        // 2,000.00 USD x 1,452.10 + 333.33 USD x 1,452.105 + 1,200,000 RWF
        BigDecimal total = LandedCost.postingTotal(List.of(new BigDecimal("2904200.000"),
                new BigDecimal("484030.15965"), new BigDecimal("1200000")), 0);
        assertThat(total).isEqualByComparingTo("4588230");
        assertThat(total.scale()).isZero();
    }

    /**
     * SRS AT-01: a crate of 20 sheets 3210 x 2250 x 6 mm at 4.20 USD per m² (rate 1,450.25) with a USD freight
     * bill (2,000 at 1,452.10) and RWF clearing (1,200,000). Landed cost per m² must match the manual sum:
     * (20 x unit cost + import costs) / m².
     */
    @Test
    void acceptanceTestAt01LandedCostMatchesTheManualCalculation() {
        BigDecimal sheetArea = Pricing.areaM2(3210, 2250);
        BigDecimal costPerM2 = Costing.costPerM2(new BigDecimal("4.20"), new BigDecimal("1450.25"));
        BigDecimal unitCost = Costing.unitCost(sheetArea, costPerM2);
        assertThat(costPerM2).isEqualByComparingTo("6091.05");
        assertThat(unitCost).isEqualByComparingTo("43992.61");

        BigDecimal importCosts = LandedCost.postingTotal(List.of(
                CurrencyMath.toBase(new BigDecimal("2000.00"), new BigDecimal("1452.10")), new BigDecimal("1200000")), 0);
        assertThat(importCosts).isEqualByComparingTo("4104200");

        LandedCost.CrateBasis crate = new LandedCost.CrateBasis(20, 0, sheetArea, costPerM2, new BigDecimal("15.00"));
        BigDecimal crateAmount = LandedCost.split(importCosts, List.of(crate.basis(AllocationMethod.AREA)), 2).get(0);
        List<BigDecimal> perSheet = LandedCost.perPiece(crateAmount, 20);
        assertThat(perSheet).containsOnly(new BigDecimal("205210.00"));

        BigDecimal extraPerM2 = LandedCost.perM2(crateAmount, crate.area());
        assertThat(extraPerM2).isEqualByComparingTo("28412.5995");
        BigDecimal landedPerM2 = costPerM2.add(extraPerM2);
        assertThat(landedPerM2).isEqualByComparingTo("34503.6495");

        // Manual: (20 x 43,992.61 + 4,104,200) / 144.45 m² = 34,503.6497 (the unit cost was rounded to the franc cent)
        BigDecimal manual = LandedCost.perM2(unitCost.multiply(BigDecimal.valueOf(20)).add(importCosts), crate.area());
        assertThat(manual).isEqualByComparingTo("34503.6497");
        assertThat(landedPerM2.subtract(manual).abs()).isLessThan(new BigDecimal("0.01"));
        // Each sheet's cost after landing: 43,992.61 + 205,210.00
        assertThat(unitCost.add(perSheet.get(0))).isEqualByComparingTo("249202.61");
    }

    @Test
    void macMovesByTheValueAddedOverTheM2Held() {
        assertThat(Costing.addValue(new BigDecimal("100"), new BigDecimal("6000.0000"), new BigDecimal("50000")))
                .isEqualByComparingTo("6500.0000");
        assertThat(Costing.addValue(new BigDecimal("114.445"), new BigDecimal("6100.0000"), new BigDecimal("279016.24")))
                .isEqualByComparingTo("8537.9941");
        // Nothing held or no MAC yet: unchanged
        assertThat(Costing.addValue(BigDecimal.ZERO, new BigDecimal("6000"), new BigDecimal("50000"))).isEqualByComparingTo("6000");
        assertThat(Costing.addValue(new BigDecimal("10"), null, new BigDecimal("50000"))).isNull();
    }

    @Test
    void perM2IsZeroWithoutArea() {
        assertThat(LandedCost.perM2(new BigDecimal("100"), BigDecimal.ZERO)).isEqualByComparingTo("0");
        assertThat(LandedCost.perM2(new BigDecimal("100"), new BigDecimal("3"))).isEqualByComparingTo("33.3333");
    }
}
