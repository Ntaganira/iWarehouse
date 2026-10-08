package com.ntaganira.heritier.iWarehouse.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rules of a cut (PRD-02, PRD-04..07, PRD-09) and AT-02: a 7.22 m² sheet cut into 3 customer pieces of
 * 5.0 m², one 1.8 m² off-cut and 0.42 m² of cullet; areas balance and costs are allocated by area.
 */
class CuttingTest {

    private static final BigDecimal MIN_AREA = new BigDecimal("0.25");
    private static final int MIN_SIDE = 300;

    @Test
    void aPieceFitsEitherWayRound() {
        assertThat(Cutting.fits(2000, 1000, 3210, 2250)).isTrue();
        assertThat(Cutting.fits(2250, 3210, 3210, 2250)).isTrue();   // turned
        assertThat(Cutting.fits(1000, 3000, 3210, 2250)).isTrue();   // turned
        assertThat(Cutting.fits(3300, 1000, 3210, 2250)).isFalse();  // too long either way
        assertThat(Cutting.fits(2300, 2300, 3210, 2250)).isFalse();  // too wide either way
    }

    @Test
    void aLeftoverIsAnOffcutFromTheMinimumAreaWithBothSidesLongEnough() {
        assertThat(Cutting.isOffcut(500, 500, MIN_AREA, MIN_SIDE)).isTrue();    // 0.25 m² exactly
        assertThat(Cutting.isOffcut(499, 500, MIN_AREA, MIN_SIDE)).isFalse();   // 0.2495 m²
        assertThat(Cutting.isOffcut(300, 900, MIN_AREA, MIN_SIDE)).isTrue();    // 0.27 m², short side 300
        assertThat(Cutting.isOffcut(299, 2000, MIN_AREA, MIN_SIDE)).isFalse();  // 0.598 m² but a strip
        assertThat(Cutting.isOffcut(1800, 1000, MIN_AREA, MIN_SIDE)).isTrue();
    }

    @Test
    void at02AreasBalanceWithTheTrimAsCullet() {
        // 3210 x 2250 = 7.2225 m²; pieces 2000x1000 + 2 x 1500x1000 = 5.0 m²; off-cut 1800x1000 = 1.8 m²
        Cutting.Balance balance = new Cutting.Balance(new BigDecimal("7.2225"), new BigDecimal("5.0000"),
                new BigDecimal("1.8000"), BigDecimal.ZERO, BigDecimal.ZERO);

        assertThat(balance.getTrim()).isEqualByComparingTo("0.4225");
        assertThat(balance.getCullet()).isEqualByComparingTo("0.4225");
        assertThat(balance.getExcess()).isEqualByComparingTo("0");
        assertThat(balance.isBalanced()).isTrue();
        assertThat(balance.getYieldPercent()).isEqualByComparingTo("94.15");
    }

    @Test
    void smallLeftoversAreCulletAndBreakageIsKeptApart() {
        Cutting.Balance balance = new Cutting.Balance(new BigDecimal("7.2225"), new BigDecimal("5.0000"),
                new BigDecimal("1.8000"), new BigDecimal("0.2000"), new BigDecimal("0.1500"));

        assertThat(balance.getRecorded()).isEqualByComparingTo("7.1500");
        assertThat(balance.getTrim()).isEqualByComparingTo("0.0725");
        assertThat(balance.getCullet()).isEqualByComparingTo("0.2725");
        assertThat(balance.getYieldPercent()).isEqualByComparingTo("94.15");
    }

    @Test
    void outputsMayExceedTheSourceByOnePercentAtMost() {
        BigDecimal source = new BigDecimal("7.2225");
        // 1% of 7.2225 = 0.072225
        Cutting.Balance within = new Cutting.Balance(source, new BigDecimal("7.2900"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        Cutting.Balance over = new Cutting.Balance(source, new BigDecimal("7.3000"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        assertThat(within.isBalanced()).isTrue();
        assertThat(within.getTrim()).isEqualByComparingTo("0");
        assertThat(within.getYieldPercent()).isEqualByComparingTo("100.00");  // never above 100
        assertThat(over.isBalanced()).isFalse();
        assertThat(over.getExcess()).isEqualByComparingTo("0.0775");
    }

    @Test
    void at02TheSheetCostIsSharedByAreaToTheCent() {
        // AT-01 landed cost of the sheet: 249,017.61 RWF, 34,478.04 per m²
        List<BigDecimal> parts = Cutting.costByArea(new BigDecimal("249017.61"), List.of(
                new BigDecimal("2.0000"), new BigDecimal("1.5000"), new BigDecimal("1.5000"),  // pieces
                new BigDecimal("1.8000"),                                                   // off-cut
                new BigDecimal("0.4225")));                                                 // cullet (trim)

        assertThat(parts).containsExactly(new BigDecimal("68956.07"), new BigDecimal("51717.05"),
                new BigDecimal("51717.05"), new BigDecimal("62060.47"), new BigDecimal("14566.97"));
        assertThat(parts.stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("249017.61");
    }

    @Test
    void aZeroAreaGetsNothing() {
        List<BigDecimal> parts = Cutting.costByArea(new BigDecimal("100.00"),
                List.of(new BigDecimal("1.0000"), new BigDecimal("2.0000"), BigDecimal.ZERO));

        assertThat(parts).containsExactly(new BigDecimal("33.33"), new BigDecimal("66.67"), new BigDecimal("0.00"));
    }

    @Test
    void yieldIsZeroWithoutAConsumedArea() {
        assertThat(Cutting.yieldPercent(BigDecimal.ONE, BigDecimal.ZERO)).isEqualByComparingTo("0");
        assertThat(Cutting.yieldPercent(new BigDecimal("3"), new BigDecimal("4"))).isEqualByComparingTo("75.00");
    }

    @Test
    void cuttingASheetAtTheMacLeavesTheMacAsItWas() {
        // 20 sheets of 7.2225 m² held at 34,478.0353; the cullet of one cut is expensed at the same rate
        BigDecimal mac = Costing.afterCut(new BigDecimal("144.4500"), new BigDecimal("34478.0353"),
                new BigDecimal("-0.4225"), new BigDecimal("-14566.97"));

        assertThat(mac).isEqualByComparingTo("34478.0353");
    }

    @Test
    void cuttingADearerSheetLowersTheMacOfWhatIsLeft() {
        // 10 m² held at 1,000 (10,000); a 2 m² sheet costing 3,000 (1,500 per m²) cut, 0.5 m² of it lost: 750 expensed
        BigDecimal mac = Costing.afterCut(new BigDecimal("10"), new BigDecimal("1000.0000"),
                new BigDecimal("-0.5"), new BigDecimal("-750"));

        // (10,000 - 750) / 9.5 m²
        assertThat(mac).isEqualByComparingTo("973.6842");
    }

    @Test
    void withoutAMacOrStockLeftTheMacStays() {
        assertThat(Costing.afterCut(new BigDecimal("5"), null, new BigDecimal("-1"), new BigDecimal("-10"))).isNull();
        assertThat(Costing.afterCut(new BigDecimal("1"), new BigDecimal("500.0000"), new BigDecimal("-1"),
                new BigDecimal("-500"))).isEqualByComparingTo("500.0000");
    }
}
