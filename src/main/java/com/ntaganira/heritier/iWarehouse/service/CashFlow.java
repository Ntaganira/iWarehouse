package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.enums.CashDirection;
import com.ntaganira.heritier.iWarehouse.enums.CashRepeat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : CashFlow.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The rolling cash-flow forecast (P.11), pure and tested: weeks of seven days from a day, each with the money
 *               expected in and out by kind (receivables and payables by their due dates, the payroll, planned items), what
 *               the week opens and closes with, a week closing below zero flagged. Anything due before the first day (overdue)
 *               falls in the first week; anything after the last week is left out. A planned item repeats every week or
 *               month from its day until its last day.
 * </pre>
 */
public final class CashFlow {

    private CashFlow() {
    }

    /** What the money is: in from customers or planned; out to suppliers, the net pay, RRA and RSSB, or planned. */
    public enum Kind {
        RECEIVABLES(CashDirection.IN), PLANNED_IN(CashDirection.IN), PAYABLES(CashDirection.OUT), PAYROLL(CashDirection.OUT), TAXES(CashDirection.OUT),
        PLANNED_OUT(CashDirection.OUT);

        private final CashDirection direction;

        Kind(CashDirection direction) {
            this.direction = direction;
        }

        public CashDirection direction() {
            return direction;
        }

        public boolean isIn() {
            return direction == CashDirection.IN;
        }
    }

    /** Money expected on a day (always positive: its kind says which way). */
    public record Flow(LocalDate date, Kind kind, BigDecimal amount) {
    }

    /** A week of the forecast: its days, what it opens with, the money in and out by kind, what it closes with. */
    public record Week(int no, LocalDate from, LocalDate to, BigDecimal opening, Map<Kind, BigDecimal> amounts) {

        public BigDecimal get(Kind kind) {
            return amounts.getOrDefault(kind, BigDecimal.ZERO);
        }

        public BigDecimal getTotalIn() {
            return amounts.entrySet().stream().filter(e -> e.getKey().isIn()).map(Map.Entry::getValue).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        public BigDecimal getTotalOut() {
            return amounts.entrySet().stream().filter(e -> !e.getKey().isIn()).map(Map.Entry::getValue).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        public BigDecimal getNet() {
            return getTotalIn().subtract(getTotalOut());
        }

        /** The planned items of the week, in less out. */
        public BigDecimal getPlanned() {
            return get(Kind.PLANNED_IN).subtract(get(Kind.PLANNED_OUT));
        }

        public BigDecimal getClosing() {
            return opening.add(getNet());
        }

        public boolean isShort() {
            return getClosing().signum() < 0;
        }
    }

    /** The forecast: {@code weeks} weeks from {@code start}, opening with the cash held. */
    public static List<Week> forecast(LocalDate start, int weeks, BigDecimal openingCash, Collection<Flow> flows) {
        List<Map<Kind, BigDecimal>> amounts = new ArrayList<>();
        for (int i = 0; i < weeks; i++) {
            amounts.add(new EnumMap<>(Kind.class));
        }
        LocalDate end = start.plusDays(7L * weeks - 1);
        for (Flow f : flows) {
            if (f.amount() == null || f.amount().signum() == 0 || f.date().isAfter(end)) {
                continue;
            }
            int week = f.date().isBefore(start) ? 0 : (int) (ChronoUnit.DAYS.between(start, f.date()) / 7);
            amounts.get(week).merge(f.kind(), f.amount(), BigDecimal::add);
        }
        List<Week> result = new ArrayList<>();
        BigDecimal opening = openingCash;
        for (int i = 0; i < weeks; i++) {
            LocalDate from = start.plusDays(7L * i);
            Week w = new Week(i + 1, from, from.plusDays(6), opening, amounts.get(i));
            result.add(w);
            opening = w.getClosing();
        }
        return result;
    }

    /** The days a planned item falls on between two days (both included): once, or every week or month from its day until its last day. */
    public static List<LocalDate> occurrences(LocalDate first, CashRepeat repeats, LocalDate until, LocalDate from, LocalDate to) {
        List<LocalDate> days = new ArrayList<>();
        LocalDate last = until == null || until.isAfter(to) ? to : until;
        if (repeats == null || repeats == CashRepeat.NONE) {
            if (!first.isBefore(from) && !first.isAfter(to)) {
                days.add(first);
            }
            return days;
        }
        for (int n = 0; ; n++) {
            LocalDate day = repeats == CashRepeat.WEEKLY ? first.plusWeeks(n) : first.plusMonths(n);
            if (day.isAfter(last)) {
                break;
            }
            if (!day.isBefore(from)) {
                days.add(day);
            }
        }
        return days;
    }

    /** The last day of each month from a day to another (both included): the days the payroll is paid. */
    public static List<LocalDate> monthEnds(LocalDate from, LocalDate to) {
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate d = from.withDayOfMonth(from.lengthOfMonth()); !d.isAfter(to); d = d.plusMonths(1).withDayOfMonth(d.plusMonths(1).lengthOfMonth())) {
            days.add(d);
        }
        return days;
    }
}
