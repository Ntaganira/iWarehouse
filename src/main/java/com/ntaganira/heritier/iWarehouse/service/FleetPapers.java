package com.ntaganira.heritier.iWarehouse.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : FleetPapers.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : The papers a trip needs (FLT-04): the driver's licence, the vehicle's insurance and inspection. A paper
 *               covers a day up to and including its expiry date. An expired one refuses a trip on that day; within the
 *               Settings' days before it expires the fleet is told (once per paper, expiry date and stage). Pure, tested.
 * </pre>
 */
public final class FleetPapers {

    /** Prefix of the alert states these papers raise. */
    public static final String ALERT_PREFIX = "FLEET:";

    private FleetPapers() {
    }

    public enum Paper {
        LICENCE, INSURANCE, INSPECTION
    }

    /** VALID: more than the alert days left; SOON: expires within them; EXPIRED: no longer covers today. */
    public enum Stage {
        VALID, SOON, EXPIRED
    }

    /** A paper that refuses a trip: which, and when it expired. */
    public record Blocker(Paper paper, LocalDate expiry) {
    }

    /** True when the paper covers the day: it expires on it or later. */
    public static boolean coversDay(LocalDate expiry, LocalDate day) {
        return expiry != null && !expiry.isBefore(day);
    }

    public static Stage stage(LocalDate expiry, LocalDate today, int alertDays) {
        if (!coversDay(expiry, today)) {
            return Stage.EXPIRED;
        }
        return expiry.isAfter(today.plusDays(alertDays)) ? Stage.VALID : Stage.SOON;
    }

    /** Days from today to the expiry date: 0 on the last day, negative once expired. */
    public static long daysLeft(LocalDate expiry, LocalDate today) {
        return ChronoUnit.DAYS.between(today, expiry);
    }

    /** The papers that do not cover the day of a trip, the licence first (FLT-04). */
    public static List<Blocker> blocking(LocalDate day, LocalDate licenceExpiry, LocalDate insuranceExpiry, LocalDate inspectionExpiry) {
        List<Blocker> blockers = new ArrayList<>();
        if (!coversDay(licenceExpiry, day)) {
            blockers.add(new Blocker(Paper.LICENCE, licenceExpiry));
        }
        if (!coversDay(insuranceExpiry, day)) {
            blockers.add(new Blocker(Paper.INSURANCE, insuranceExpiry));
        }
        if (!coversDay(inspectionExpiry, day)) {
            blockers.add(new Blocker(Paper.INSPECTION, inspectionExpiry));
        }
        return blockers;
    }

    /**
     * The alert state of a paper at a stage: "FLEET:INSURANCE:&lt;vehicle id&gt;:2026-11-05:SOON". A renewal changes the
     * expiry date, so the old key clears and a new one is raised only when the new date comes near.
     */
    public static String alertKey(Paper paper, UUID ownerId, LocalDate expiry, Stage stage) {
        return ALERT_PREFIX + paper.name() + ":" + ownerId + ":" + expiry + ":" + stage.name();
    }
}
