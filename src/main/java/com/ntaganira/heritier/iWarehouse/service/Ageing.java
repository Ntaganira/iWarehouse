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
 * - User      : Hntaganira
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

    /** An entry of the account: its date and amounts (one of them zero). */
    public record Entry(LocalDate date, BigDecimal debit, BigDecimal credit) {
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

    public static Result of(List<Entry> entries, int termsDays, LocalDate asOf) {
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
                    open.addLast(new Open(e.date().plusDays(termsDays), charge));
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
        Map<Bucket, BigDecimal> buckets = new EnumMap<>(Bucket.class);
        BigDecimal owed = BigDecimal.ZERO;
        for (Open o : open) {
            buckets.merge(Bucket.of(ChronoUnit.DAYS.between(o.due, asOf)), o.left, BigDecimal::add);
            owed = owed.add(o.left);
        }
        return new Result(buckets, owed.subtract(waiting), waiting, open.isEmpty() ? null : open.peekFirst().due);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
