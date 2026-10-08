package com.ntaganira.heritier.iWarehouse.dto;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Dashboard chart series: one value per day, zero-filled. Runs without a database. */
class DailyCountsTest {

    @Test
    void missingDaysCountAsZero() {
        LocalDate first = LocalDate.of(2026, 9, 30);
        DailyCounts counts = DailyCounts.of(first, 3, Map.of(LocalDate.of(2026, 10, 1), 7L));

        assertThat(counts.days()).containsExactly(
                LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2));
        assertThat(counts.counts()).containsExactly(0L, 7L, 0L);
        assertThat(counts.isoDays()).containsExactly("2026-09-30", "2026-10-01", "2026-10-02");
    }

    @Test
    void readsNativeQueryRows() {
        List<Object[]> rows = List.of(
                new Object[]{"2026-10-06", 4L},
                new Object[]{"2026-10-07", 12});   // driver may return Integer or Long

        assertThat(DailyCounts.toMap(rows)).containsOnly(
                Map.entry(LocalDate.of(2026, 10, 6), 4L),
                Map.entry(LocalDate.of(2026, 10, 7), 12L));
    }
}
