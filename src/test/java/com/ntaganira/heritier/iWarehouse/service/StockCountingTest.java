package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.enums.CountOutcome;
import com.ntaganira.heritier.iWarehouse.enums.StockStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** INV-08: the comparison behind a stock count. */
class StockCountingTest {

    private static final UUID R01 = UUID.randomUUID();
    private static final UUID R02 = UUID.randomUUID();

    private static StockCounting.Unit unit(String code, StockStatus status, UUID at) {
        return new StockCounting.Unit(UUID.randomUUID(), code, status, at);
    }

    @Test
    void aUnitScannedWhereTheRecordsSayIsMatched() {
        StockCounting.Unit u = unit("U-1", StockStatus.AVAILABLE, R01);
        List<StockCounting.Line> lines = StockCounting.compare(List.of(u), List.of(new StockCounting.Scan("U-1", u, R01)));
        assertThat(lines).singleElement().satisfies(l -> {
            assertThat(l.outcome()).isEqualTo(CountOutcome.MATCHED);
            assertThat(l.expectedAt()).isEqualTo(R01);
            assertThat(l.foundAt()).isEqualTo(R01);
        });
    }

    @Test
    void aUnitScannedOnAnotherRackIsMisplaced() {
        StockCounting.Unit u = unit("U-1", StockStatus.RESERVED, R01);
        List<StockCounting.Line> lines = StockCounting.compare(List.of(u), List.of(new StockCounting.Scan("U-1", u, R02)));
        assertThat(lines).singleElement().satisfies(l -> {
            assertThat(l.outcome()).isEqualTo(CountOutcome.MISPLACED);
            assertThat(l.expectedAt()).isEqualTo(R01);
            assertThat(l.foundAt()).isEqualTo(R02);
        });
    }

    @Test
    void aUnitRecordedOutsideTheCountButScannedInItIsMisplaced() {
        StockCounting.Unit u = unit("U-9", StockStatus.AVAILABLE, R02);
        List<StockCounting.Line> lines = StockCounting.compare(List.of(), List.of(new StockCounting.Scan("U-9", u, R01)));
        assertThat(lines).extracting(StockCounting.Line::outcome).containsExactly(CountOutcome.MISPLACED);
    }

    @Test
    void anExpectedUnitNobodyScannedIsMissing() {
        StockCounting.Unit u = unit("U-1", StockStatus.AVAILABLE, R01);
        List<StockCounting.Line> lines = StockCounting.compare(List.of(u), List.of());
        assertThat(lines).singleElement().satisfies(l -> {
            assertThat(l.outcome()).isEqualTo(CountOutcome.MISSING);
            assertThat(l.expectedAt()).isEqualTo(R01);
            assertThat(l.foundAt()).isNull();
        });
    }

    @Test
    void extrasSayWhyTheyAreExtra() {
        StockCounting.Unit lost = unit("U-2", StockStatus.LOST, null);
        StockCounting.Unit cutting = unit("U-3", StockStatus.IN_CUTTING, R01);
        StockCounting.Unit sold = unit("U-4", StockStatus.SOLD, null);
        List<StockCounting.Line> lines = StockCounting.compare(List.of(), List.of(
                new StockCounting.Scan("U-2", lost, R01),
                new StockCounting.Scan("U-3", cutting, R01),
                new StockCounting.Scan("U-4", sold, R01),
                new StockCounting.Scan("X-1", null, R01)));
        assertThat(lines).extracting(StockCounting.Line::outcome).containsExactly(
                CountOutcome.FOUND_LOST, CountOutcome.ELSEWHERE, CountOutcome.NOT_IN_STOCK, CountOutcome.UNKNOWN);
        assertThat(lines).allSatisfy(l -> assertThat(l.outcome().isExtra()).isTrue());
        assertThat(lines).allSatisfy(l -> assertThat(l.expectedAt()).isNull());
    }

    @Test
    void linesComeByOutcomeThenCodeAndTheTotalsAddUp() {
        StockCounting.Unit a = unit("U-3", StockStatus.AVAILABLE, R01);
        StockCounting.Unit b = unit("U-1", StockStatus.AVAILABLE, R01);
        StockCounting.Unit c = unit("U-2", StockStatus.AVAILABLE, R01);
        StockCounting.Unit d = unit("U-5", StockStatus.AVAILABLE, R01);
        List<StockCounting.Line> lines = StockCounting.compare(List.of(a, b, c, d), List.of(
                new StockCounting.Scan("U-3", a, R01),
                new StockCounting.Scan("U-1", b, R01),
                new StockCounting.Scan("U-5", d, R02),
                new StockCounting.Scan("X-1", null, R01)));
        assertThat(lines).extracting(StockCounting.Line::code).containsExactly("U-1", "U-3", "U-5", "U-2", "X-1");
        StockCounting.Totals t = StockCounting.totals(4, 4, lines);
        assertThat(t).isEqualTo(new StockCounting.Totals(4, 4, 2, 1, 1, 1));
    }

    @Test
    void theOutcomeOfOneScanMatchesTheComparison() {
        assertThat(StockCounting.outcomeOf(null, R01)).isEqualTo(CountOutcome.UNKNOWN);
        assertThat(StockCounting.outcomeOf(unit("U", StockStatus.RECEIVED, R01), R01)).isEqualTo(CountOutcome.MATCHED);
        assertThat(StockCounting.outcomeOf(unit("U", StockStatus.ON_VEHICLE, null), R01)).isEqualTo(CountOutcome.ELSEWHERE);
        assertThat(StockCounting.outcomeOf(unit("U", StockStatus.BROKEN, null), R01)).isEqualTo(CountOutcome.NOT_IN_STOCK);
        assertThat(StockCounting.outcomeOf(unit("U", StockStatus.CONSUMED, null), R01)).isEqualTo(CountOutcome.NOT_IN_STOCK);
    }
}
