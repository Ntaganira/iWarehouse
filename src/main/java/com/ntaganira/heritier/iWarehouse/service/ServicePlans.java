package com.ntaganira.heritier.iWarehouse.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : ServicePlans.java
 * - Date      : 2026. 10. 12.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : When a vehicle's service falls due, pure: so many km after it was last done (when the km are known) or so
 *               many months after, whichever comes first. Due on the day or at the km; due soon within the Settings' km
 *               or days before. The alert key names the plan, when it was last done and the stage, so doing the work
 *               (a new last time) or a later stage raises a new alert, once.
 * </pre>
 */
public final class ServicePlans {

    public static final String ALERT_PREFIX = "SERVICE:";

    private ServicePlans() {
    }

    public enum Stage {
        OK, SOON, DUE
    }

    /** Where a plan stands: when it is next due (km and day, either may be unknown) and how far that is. */
    public record Status(Stage stage, Integer nextKm, LocalDate nextDate, Integer kmLeft, Long daysLeft) {

        public boolean isDue() {
            return stage == Stage.DUE;
        }

        public boolean isSoon() {
            return stage == Stage.SOON;
        }
    }

    /**
     * @param everyKm     km between two services, or null
     * @param everyMonths months between two services, or null
     * @param lastOn      when it was last done
     * @param lastKm      the odometer then, or null (then the km cannot fall due)
     * @param currentKm   the vehicle's latest odometer, or null
     */
    public static Status of(Integer everyKm, Integer everyMonths, LocalDate lastOn, Integer lastKm, Integer currentKm, LocalDate today,
                            int warnKm, int warnDays) {
        Integer nextKm = everyKm != null && lastKm != null ? lastKm + everyKm : null;
        LocalDate nextDate = everyMonths != null ? lastOn.plusMonths(everyMonths) : null;
        Integer kmLeft = nextKm != null && currentKm != null ? nextKm - currentKm : null;
        Long daysLeft = nextDate != null ? ChronoUnit.DAYS.between(today, nextDate) : null;
        Stage stage = Stage.OK;
        if ((kmLeft != null && kmLeft <= 0) || (daysLeft != null && daysLeft <= 0)) {
            stage = Stage.DUE;
        } else if ((kmLeft != null && kmLeft <= warnKm) || (daysLeft != null && daysLeft <= warnDays)) {
            stage = Stage.SOON;
        }
        return new Status(stage, nextKm, nextDate, kmLeft, daysLeft);
    }

    /** The highest of two readings (either may be unknown). */
    public static Integer latest(Integer a, Integer b) {
        if (a == null) {
            return b;
        }
        return b == null ? a : Math.max(a, b);
    }

    public static String alertKey(UUID planId, LocalDate lastOn, Integer lastKm, Stage stage) {
        return ALERT_PREFIX + planId + ":" + lastOn + ":" + (lastKm == null ? "-" : lastKm) + ":" + stage;
    }
}
