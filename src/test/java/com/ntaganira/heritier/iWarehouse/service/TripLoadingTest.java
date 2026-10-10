package com.ntaganira.heritier.iWarehouse.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : TripLoadingTest.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : Loading a vehicle (FLT-06): a load over the pieces or kg limit is not within it (AT-03: 40 units on a 35-unit
 *               vehicle, 5 too many), and a scanned label loads a planned unit once while one not on the manifest is refused.
 * </pre>
 */
class TripLoadingTest {

    @Test
    void fortyUnitsOnAThirtyFiveUnitVehicleAreFiveTooMany() {
        TripLoading.Load load = TripLoading.Load.of(Collections.nCopies(40, new BigDecimal("14.40")));
        TripLoading.Check check = TripLoading.check(load, 35, 2000);

        assertThat(load.pieces()).isEqualTo(40);
        assertThat(load.kg()).isEqualByComparingTo("576.00");
        assertThat(check.overPieces()).isTrue();
        assertThat(check.overKg()).isFalse();
        assertThat(check.within()).isFalse();
        assertThat(check.piecesOver()).isEqualTo(5);
    }

    @Test
    void theLimitItselfIsWithin() {
        TripLoading.Check atLimit = TripLoading.check(TripLoading.Load.of(Collections.nCopies(35, new BigDecimal("20"))), 35, 700);
        assertThat(atLimit.within()).isTrue();
        assertThat(atLimit.piecesOver()).isZero();
        assertThat(atLimit.kgOver()).isEqualByComparingTo("0");
    }

    @Test
    void aHeavyLoadIsOverTheKgLimit() {
        TripLoading.Check check = TripLoading.check(TripLoading.Load.of(List.of(new BigDecimal("108.34"), new BigDecimal("108.34"))), 35, 200);
        assertThat(check.overPieces()).isFalse();
        assertThat(check.overKg()).isTrue();
        assertThat(check.kgOver()).isEqualByComparingTo("16.68");
        assertThat(TripLoading.Load.EMPTY.pieces()).isZero();
    }

    @Test
    void aScanLoadsAPlannedUnitOnceAndRefusesTheOthers() {
        Map<String, Boolean> manifest = Map.of("U-WH-000041", false, "U-WH-000042", true);
        assertThat(TripLoading.scan(manifest, "U-WH-000041")).isEqualTo(TripLoading.ScanOutcome.LOADED);
        assertThat(TripLoading.scan(manifest, "U-WH-000042")).isEqualTo(TripLoading.ScanOutcome.ALREADY_LOADED);
        assertThat(TripLoading.scan(manifest, "U-WH-000099")).isEqualTo(TripLoading.ScanOutcome.NOT_ON_MANIFEST);
    }
}
