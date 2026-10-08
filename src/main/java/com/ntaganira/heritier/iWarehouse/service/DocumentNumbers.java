package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.enums.ResetPolicy;

import java.time.LocalDate;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : DocumentNumbers.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : Pure numbering rules (MD-07): the number format, the period a counter belongs to, and
 *               which value the next document gets. No database, so they are unit-tested directly.
 * </pre>
 */
public final class DocumentNumbers {

    private DocumentNumbers() {
    }

    /** Period key stored with the counter: "" (never resets), "2026" or "2026-10". */
    public static String periodKey(ResetPolicy policy, LocalDate date) {
        return switch (policy) {
            case NEVER -> "";
            case YEARLY -> String.valueOf(date.getYear());
            case MONTHLY -> String.format("%d-%02d", date.getYear(), date.getMonthValue());
        };
    }

    /**
     * Value the next document gets. The stored counter, except after a period rollover (new year or
     * month), when it starts again at 1. A blank stored period means nothing was issued yet, so a
     * starting number set before the first document is kept.
     */
    public static long effectiveNext(ResetPolicy policy, String storedPeriod, long storedNext, LocalDate date) {
        if (policy == ResetPolicy.NEVER || storedPeriod == null || storedPeriod.isEmpty()) {
            return storedNext;
        }
        return storedPeriod.equals(periodKey(policy, date)) ? storedNext : 1;
    }

    /**
     * True when the date falls in a period before the one of the last number issued, i.e. the server
     * clock is behind. Issuing then could repeat old numbers, so the caller refuses.
     */
    public static boolean isBehind(ResetPolicy policy, String storedPeriod, LocalDate date) {
        if (policy == ResetPolicy.NEVER || storedPeriod == null || storedPeriod.isEmpty()) {
            return false;
        }
        return periodKey(policy, date).compareTo(storedPeriod) < 0;
    }

    /** PREFIX-BRANCH[-PERIOD]-SEQUENCE, e.g. INV-WH-2026-000123 or INV-WH-202610-000123. */
    public static String format(String prefix, String branch, ResetPolicy policy, LocalDate date,
                                long value, int padding) {
        StringBuilder number = new StringBuilder(prefix).append('-').append(branch);
        switch (policy) {
            case YEARLY -> number.append('-').append(date.getYear());
            case MONTHLY -> number.append('-').append(String.format("%d%02d", date.getYear(), date.getMonthValue()));
            case NEVER -> {
                // no period part
            }
        }
        return number.append('-').append(String.format("%0" + padding + "d", value)).toString();
    }
}
