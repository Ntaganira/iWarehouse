package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.enums.VarianceClass;
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
 * - File      : StockControlRulesTest.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : The pure rules of M16: the passes of a count (D14: what the first and second counts agree on, the recount for the rest) and a variance’s size, class, tolerance and due day (IMP 14, 16, 19; VR-010).
 * </pre>
 */
class StockControlRulesTest {

    private static final UUID R1 = UUID.randomUUID();
    private static final UUID R2 = UUID.randomUUID();

    @Test
    void theCountsDisagreeOnLabelsOneFoundAndNotTheOtherOrFoundOnTwoPlaces() {
        List<CountPasses.Found> first = List.of(found("U-1", R1), found("U-2", R1), found("U-3", R2));
        List<CountPasses.Found> second = List.of(found("U-1", R1), found("U-3", R1), found("U-4", R2));
        assertThat(CountPasses.differences(first, second)).containsExactly("U-2", "U-3", "U-4");
        assertThat(CountPasses.differences(first, first)).isEmpty();
    }

    @Test
    void theResultIsWhatBothAgreeOnAndTheRecountForTheRest() {
        List<CountPasses.Found> first = List.of(found("U-1", R1), found("U-2", R1), found("U-3", R2));
        List<CountPasses.Found> second = List.of(found("U-1", R1), found("U-3", R1), found("U-4", R2));
        // Without a second count, the first is the result
        assertThat(CountPasses.resolve(first, List.of(), List.of(), false)).containsExactly(found("U-1", R1), found("U-2", R1), found("U-3", R2));
        // The recount finds U-3 on R2 and U-5 (never counted before); it does not find U-2 or U-4: they are not there
        assertThat(CountPasses.resolve(first, second, List.of(found("U-3", R2), found("U-5", R1)), true))
                .containsExactly(found("U-1", R1), found("U-3", R2), found("U-5", R1));
        // The two counts agree: no recount needed
        assertThat(CountPasses.resolve(first, first, List.of(), true)).hasSize(3);
    }

    @Test
    void aVarianceIsSizedAgainstTheValueCountedAndClassified() {
        assertThat(StockVariances.percent(new BigDecimal("1000"), new BigDecimal("500"), new BigDecimal("100000")))
                .isEqualByComparingTo("1.50");                                    // missing and found both count
        assertThat(StockVariances.percent(new BigDecimal("1000"), BigDecimal.ZERO, BigDecimal.ZERO)).isNull();
        BigDecimal moderate = new BigDecimal("0.5");
        BigDecimal material = new BigDecimal("2");
        assertThat(StockVariances.classify(new BigDecimal("0.40"), moderate, material)).isEqualTo(VarianceClass.MINOR);
        assertThat(StockVariances.classify(new BigDecimal("0.50"), moderate, material)).isEqualTo(VarianceClass.MODERATE);
        assertThat(StockVariances.classify(new BigDecimal("2.00"), moderate, material)).isEqualTo(VarianceClass.MATERIAL);
        assertThat(StockVariances.classify(null, moderate, material)).isEqualTo(VarianceClass.MATERIAL);   // nothing was expected
        assertThat(StockVariances.aboveTolerance(new BigDecimal("0.50"), new BigDecimal("0.5"))).isFalse();
        assertThat(StockVariances.aboveTolerance(new BigDecimal("0.51"), new BigDecimal("0.5"))).isTrue();
    }

    @Test
    void aVarianceIsDueSoManyWorkingDaysAfterTheCountAndAccuracyIsMatchedOverExpected() {
        LocalDate thursday = LocalDate.of(2026, 10, 15);
        assertThat(StockVariances.due(thursday, 5)).isEqualTo(LocalDate.of(2026, 10, 22));   // Friday, then Monday to Thursday
        assertThat(StockVariances.due(LocalDate.of(2026, 10, 17), 1)).isEqualTo(LocalDate.of(2026, 10, 19));   // Saturday: Monday
        assertThat(StockVariances.due(thursday, 0)).isEqualTo(thursday);
        assertThat(StockVariances.accuracy(197, 200)).isEqualByComparingTo("98.5");
        assertThat(StockVariances.accuracy(0, 0)).isNull();
    }

    private static CountPasses.Found found(String code, UUID place) {
        return new CountPasses.Found(code, place);
    }
}
