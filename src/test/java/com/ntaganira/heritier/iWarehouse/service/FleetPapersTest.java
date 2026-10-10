package com.ntaganira.heritier.iWarehouse.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : FleetPapersTest.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : The papers a trip needs (FLT-04): a paper covers its expiry day, is told within the alert days and refuses a
 *               trip once it no longer covers the day; the licence is named first.
 * </pre>
 */
class FleetPapersTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 10);

    @Test
    void aPaperCoversItsExpiryDayAndNotTheNext() {
        assertThat(FleetPapers.coversDay(TODAY, TODAY)).isTrue();
        assertThat(FleetPapers.coversDay(TODAY.minusDays(1), TODAY)).isFalse();
        assertThat(FleetPapers.coversDay(null, TODAY)).isFalse();
    }

    @Test
    void stagesFollowTheAlertDays() {
        assertThat(FleetPapers.stage(TODAY.plusDays(31), TODAY, 30)).isEqualTo(FleetPapers.Stage.VALID);
        assertThat(FleetPapers.stage(TODAY.plusDays(30), TODAY, 30)).isEqualTo(FleetPapers.Stage.SOON);
        assertThat(FleetPapers.stage(TODAY, TODAY, 30)).isEqualTo(FleetPapers.Stage.SOON);
        assertThat(FleetPapers.stage(TODAY.minusDays(1), TODAY, 30)).isEqualTo(FleetPapers.Stage.EXPIRED);
        assertThat(FleetPapers.daysLeft(TODAY.plusDays(12), TODAY)).isEqualTo(12);
    }

    @Test
    void anExpiredPaperRefusesTheTripLicenceFirst() {
        LocalDate day = LocalDate.of(2026, 10, 20);
        assertThat(FleetPapers.blocking(day, day, day.plusDays(1), day.plusYears(1))).isEmpty();

        var blockers = FleetPapers.blocking(day, day.minusDays(5), day.minusDays(1), day.plusYears(1));
        assertThat(blockers).extracting(FleetPapers.Blocker::paper)
                .containsExactly(FleetPapers.Paper.LICENCE, FleetPapers.Paper.INSURANCE);
        assertThat(blockers.get(0).expiry()).isEqualTo(day.minusDays(5));
        assertThat(FleetPapers.blocking(day, day, day, day.minusDays(1))).extracting(FleetPapers.Blocker::paper)
                .containsExactly(FleetPapers.Paper.INSPECTION);
    }

    @Test
    void aRenewalChangesTheAlertKey() {
        UUID vehicle = UUID.fromString("7a1c8d1e-0000-4000-8000-000000000001");
        String soon = FleetPapers.alertKey(FleetPapers.Paper.INSURANCE, vehicle, LocalDate.of(2026, 11, 5), FleetPapers.Stage.SOON);
        assertThat(soon).isEqualTo("FLEET:INSURANCE:7a1c8d1e-0000-4000-8000-000000000001:2026-11-05:SOON");
        assertThat(soon).startsWith(FleetPapers.ALERT_PREFIX).hasSizeLessThanOrEqualTo(120);
        assertThat(FleetPapers.alertKey(FleetPapers.Paper.INSURANCE, vehicle, LocalDate.of(2027, 11, 5), FleetPapers.Stage.SOON))
                .isNotEqualTo(soon);
    }
}
