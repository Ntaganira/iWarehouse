package com.ntaganira.heritier.iWarehouse.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : Ageing.java
 * - Date      : 2026. 10. 09.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The ageing of an account (ACC-09). Its entries are taken in date order: each charge (debit) is due its
 *               date plus the payment terms; each credit (a payment, a credit note) settles the oldest charges first,
 *               and a credit with nothing to settle waits for the next charges. What is left of each charge falls in
 *               its bucket by the days past due on the day asked: not due yet, 1-30, 31-60, 61-90, over 90. Pure,
 *               unit-tested.
 * </pre>
 */
public final class Ageing {

    private Ageing() {
    }

    /**
     * An entry of the account: its date and amounts (one of them zero), and the days a charge is given beyond the terms
     * (an invoice approved with extended terms, F-03).
     */
    public record Entry(LocalDate date, BigDecimal debit, BigDecimal credit, int extraDays) {

        public Entry(LocalDate date, BigDecimal debit, BigDecimal credit) {
            this(date, debit, credit, 0);
        }
    }

    /** Days past due, as the reports group them. */
    public enum Bucket {
        NOT_DUE, DAYS_1_30, DAYS_31_60, DAYS_61_90, OVER_90;

        static Bucket of(long daysPastDue) {
            if (daysPastDue <= 0) {
                return NOT_DUE;
            }
            return daysPastDue <= 30 ? DAYS_1_30 : daysPastDue <= 60 ? DAYS_31_60 : daysPastDue <= 90 ? DAYS_61_90 : OVER_90;
        }
    }

    /**
     * What is owed in each bucket, the balance (owed less any credit not settled yet), the credit waiting and the due date
     * of the oldest charge still open.
     */
    public record Result(Map<Bucket, BigDecimal> buckets, BigDecimal balance, BigDecimal unsettledCredit, LocalDate oldestDue) {

        public BigDecimal get(Bucket bucket) {
            return buckets.getOrDefault(bucket, BigDecimal.ZERO);
        }

        /** Owed and past its due date. */
        public BigDecimal getOverdue() {
            return buckets.entrySet().stream().filter(e -> e.getKey() != Bucket.NOT_DUE).map(Map.Entry::getValue)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    private static final class Open {
        final LocalDate due;
        BigDecimal left;

        Open(LocalDate due, BigDecimal left) {
            this.due = due;
            this.left = left;
        }
    }

    /** A charge still open: the day it is due and what is left of it. */
    public record Due(LocalDate due, BigDecimal amount) {
    }

    public static Result of(List<Entry> entries, int termsDays, LocalDate asOf) {
        Settled settled = settle(entries, termsDays);
        Map<Bucket, BigDecimal> buckets = new EnumMap<>(Bucket.class);
        BigDecimal owed = BigDecimal.ZERO;
        for (Open o : settled.open) {
            buckets.merge(Bucket.of(ChronoUnit.DAYS.between(o.due, asOf)), o.left, BigDecimal::add);
            owed = owed.add(o.left);
        }
        return new Result(buckets, owed.subtract(settled.waiting), settled.waiting, settled.open.isEmpty() ? null : settled.open.peekFirst().due);
    }

    /**
     * The charges still open, oldest first, each with its due date and what is left of it, once credit waiting has been taken
     * off the oldest (the cash-flow forecast, P.11: what is expected and when).
     */
    public static List<Due> open(List<Entry> entries, int termsDays) {
        Settled settled = settle(entries, termsDays);
        List<Due> due = new ArrayList<>();
        BigDecimal waiting = settled.waiting;
        for (Open o : settled.open) {
            BigDecimal used = waiting.min(o.left);
            waiting = waiting.subtract(used);
            if (o.left.subtract(used).signum() > 0) {
                due.add(new Due(o.due, o.left.subtract(used)));
            }
        }
        return due;
    }

    private record Settled(Deque<Open> open, BigDecimal waiting) {
    }

    private static Settled settle(List<Entry> entries, int termsDays) {
        List<Entry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparing(Entry::date));
        Deque<Open> open = new ArrayDeque<>();
        BigDecimal waiting = BigDecimal.ZERO;                 // credit with no charge to settle yet
        for (Entry e : sorted) {
            BigDecimal net = nz(e.debit()).subtract(nz(e.credit()));
            if (net.signum() > 0) {
                BigDecimal charge = net;
                BigDecimal used = waiting.min(charge);
                waiting = waiting.subtract(used);
                charge = charge.subtract(used);
                if (charge.signum() > 0) {
                    open.addLast(new Open(e.date().plusDays(termsDays + (long) e.extraDays()), charge));
                }
            } else if (net.signum() < 0) {
                BigDecimal credit = net.negate();
                while (credit.signum() > 0 && !open.isEmpty()) {
                    Open oldest = open.peekFirst();
                    BigDecimal used = oldest.left.min(credit);
                    oldest.left = oldest.left.subtract(used);
                    credit = credit.subtract(used);
                    if (oldest.left.signum() == 0) {
                        open.removeFirst();
                    }
                }
                waiting = waiting.add(credit);
            }
        }
        return new Settled(open, waiting);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
