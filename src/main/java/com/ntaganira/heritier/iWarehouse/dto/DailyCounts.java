package com.ntaganira.heritier.iWarehouse.dto;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.dto
 * - File      : DailyCounts.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : One count per calendar day for a chart, with zero for days that had nothing
 * </pre>
 */
public record DailyCounts(List<LocalDate> days, List<Long> counts) {

    /** {@code days} consecutive days starting at {@code first}; days missing from {@code totals} count as zero. */
    public static DailyCounts of(LocalDate first, int days, Map<LocalDate, Long> totals) {
        List<LocalDate> dayList = new ArrayList<>(days);
        List<Long> countList = new ArrayList<>(days);
        for (int i = 0; i < days; i++) {
            LocalDate day = first.plusDays(i);
            dayList.add(day);
            countList.add(totals.getOrDefault(day, 0L));
        }
        return new DailyCounts(List.copyOf(dayList), List.copyOf(countList));
    }

    /** Rows of (day as yyyy-MM-dd text, count) from a native GROUP BY query. */
    public static Map<LocalDate, Long> toMap(List<Object[]> rows) {
        Map<LocalDate, Long> totals = new HashMap<>();
        for (Object[] row : rows) {
            totals.merge(LocalDate.parse((String) row[0]), ((Number) row[1]).longValue(), Long::sum);
        }
        return totals;
    }

    /** Days as ISO text for the chart script (formatted in the browser's language there). */
    public List<String> isoDays() {
        return days.stream().map(LocalDate::toString).toList();
    }
}
