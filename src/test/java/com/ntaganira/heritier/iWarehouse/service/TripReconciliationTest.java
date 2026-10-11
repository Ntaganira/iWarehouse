package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.enums.StockStatus;
import com.ntaganira.heritier.iWarehouse.enums.TripLineOutcome;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : TripReconciliationTest.java
 * - Date      : 2026. 10. 11.
 * - User      : Hntaganira
 * - Desc      : The end of day of a trip (FLT-09, FLT-10): expected back = loaded - sold - breakage accepted, compared with
 *               the scans; missing and unexpected units are the differences an audit case opens on; a float counted short
 *               or over splits so the clearance balances (ACC-07).
 * </pre>
 */
class TripReconciliationTest {

    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private final UUID c = UUID.randomUUID();
    private final UUID d = UUID.randomUUID();
    private final UUID e = UUID.randomUUID();

    @Test
    void expectedBackIsLoadedLessSoldLessBroken() {
        TripReconciliation.Result r = TripReconciliation.reconcile(List.of(
                line(a, "U-1", StockStatus.SOLD, false, false),
                line(b, "U-2", StockStatus.ON_VEHICLE, true, false),
                line(c, "U-3", StockStatus.ON_VEHICLE, false, true),          // broken, its breakage accepted
                line(d, "U-4", StockStatus.ON_VEHICLE, true, false)), List.of());
        assertThat(r.outcomes()).containsEntry(a, TripLineOutcome.SOLD).containsEntry(b, TripLineOutcome.RETURNED)
                .containsEntry(c, TripLineOutcome.BROKEN).containsEntry(d, TripLineOutcome.RETURNED);
        assertThat(r.expectedBack()).isEqualTo(2);
        assertThat(r.countReturned()).isEqualTo(2);
        assertThat(r.hasDifference()).isFalse();
        assertThat(r.canReconcile()).isTrue();
    }

    @Test
    void aUnitNotScannedAndNotBrokenIsMissing() {   // AT-06: 1 unit missing and no breakage report
        TripReconciliation.Result r = TripReconciliation.reconcile(List.of(
                line(a, "U-1", StockStatus.ON_VEHICLE, true, false),
                line(b, "U-2", StockStatus.ON_VEHICLE, false, false)), List.of());
        assertThat(r.outcomes()).containsEntry(b, TripLineOutcome.MISSING);
        assertThat(r.missing()).extracting(TripReconciliation.Line::code).containsExactly("U-2");
        assertThat(r.countMissing()).isEqualTo(1);
        assertThat(r.expectedBack()).isEqualTo(2);
        assertThat(r.hasDifference()).isTrue();
    }

    @Test
    void anAcceptedBreakageWinsOverAScanAndARejectedOneDoesNotCount() {
        // The broken glass came back as cullet: still broken. A unit whose breakage was rejected is expected back like any other
        TripReconciliation.Result r = TripReconciliation.reconcile(List.of(
                line(a, "U-1", StockStatus.ON_VEHICLE, true, true),
                line(b, "U-2", StockStatus.ON_VEHICLE, false, false)), List.of());
        assertThat(r.outcomes()).containsEntry(a, TripLineOutcome.BROKEN).containsEntry(b, TripLineOutcome.MISSING);
    }

    @Test
    void aSoldUnitScannedBackAndAnyLabelNotOnTheTripAreUnexpected() {
        TripReconciliation.Result r = TripReconciliation.reconcile(List.of(
                line(a, "U-1", StockStatus.SOLD, true, false),
                line(b, "U-2", StockStatus.ON_VEHICLE, true, false)),
                List.of(new TripReconciliation.Extra("U-9", e), new TripReconciliation.Extra("XYZ-1", null)));
        assertThat(r.outcomes()).containsEntry(a, TripLineOutcome.SOLD);
        assertThat(r.unexpected()).extracting(TripReconciliation.Unexpected::code).containsExactly("U-1", "U-9", "XYZ-1");
        assertThat(r.unexpected().get(0).sold()).isTrue();
        assertThat(r.unexpected().get(2).unitId()).isNull();
        assertThat(r.missing()).isEmpty();
        assertThat(r.hasDifference()).isTrue();
    }

    @Test
    void aUnitMovedByAnotherDocumentStopsTheReconciliation() {
        TripReconciliation.Result r = TripReconciliation.reconcile(List.of(
                line(a, "U-1", StockStatus.AVAILABLE, false, false),
                line(b, "U-2", StockStatus.LOST, false, false),
                line(c, "U-3", StockStatus.ON_VEHICLE, true, false)), List.of());
        assertThat(r.canReconcile()).isFalse();
        assertThat(r.elsewhere()).containsExactly("U-1", "U-2");
    }

    @Test
    void aFloatCountedShortOrOverSplitsSoTheClearanceBalances() {   // ACC-07, AT-07
        FloatClearances.Split shortBy = FloatClearances.split(new BigDecimal("295008"), new BigDecimal("294008"));
        assertThat(shortBy.shortage()).isEqualByComparingTo("1000");
        assertThat(shortBy.overage()).isZero();
        FloatClearances.Split over = FloatClearances.split(new BigDecimal("10000"), new BigDecimal("10500"));
        assertThat(over.shortage()).isZero();
        assertThat(over.overage()).isEqualByComparingTo("500");
        FloatClearances.Split exact = FloatClearances.split(new BigDecimal("45000"), new BigDecimal("45000.00"));
        assertThat(exact.isExact()).isTrue();
        // counted + shortage = expected + overage, whatever was counted
        for (String counted : List.of("0", "1", "294008", "295008", "300000")) {
            FloatClearances.Split s = FloatClearances.split(new BigDecimal("295008"), new BigDecimal(counted));
            assertThat(new BigDecimal(counted).add(s.shortage())).isEqualByComparingTo(new BigDecimal("295008").add(s.overage()));
        }
    }

    private static TripReconciliation.Line line(UUID id, String code, StockStatus status, boolean scanned, boolean broken) {
        return new TripReconciliation.Line(id, code, status, scanned, broken);
    }
}
