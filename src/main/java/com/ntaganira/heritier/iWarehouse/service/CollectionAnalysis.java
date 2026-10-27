package com.ntaganira.heritier.iWarehouse.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : CollectionAnalysis.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Collection performance over a period (BP 5.1, Annex D, Q.05), from a customer's receivable lines: each
 *               credit settles the oldest charges first (as Ageing does), a credit that finds nothing open waits for the
 *               next charge, and a charge is settled on the later of its day and the credit's. So every charge knows who
 *               sold it, what was owed on it when the period began, what was charged, what was collected on it in the
 *               period (payments) and what else settled it (credit notes, write-offs), what is still owed at the end and
 *               how much of that is past due, and how much was collected by its due day. Grouped by territory,
 *               salesperson or customer; credits left over are the customer's unapplied credit. Pure and tested.
 * </pre>
 */
public final class CollectionAnalysis {

    private CollectionAnalysis() {
    }

    public enum GroupBy { TERRITORY, SALESPERSON, CUSTOMER }

    /** A debit on the receivable account: its customer, day, due day, who sold it (null: nobody) and amount. */
    public record Charge(UUID customerId, LocalDate date, LocalDate due, String salesperson, BigDecimal amount) {
    }

    /** A credit on the receivable account: a collection (payment) or another credit (credit note, write-off...). */
    public record Credit(UUID customerId, LocalDate date, boolean collection, BigDecimal amount) {
    }

    /** One charge's figures over the period. */
    public record Part(Charge charge, BigDecimal opening, BigDecimal charged, BigDecimal collected, BigDecimal otherCredits,
                       BigDecimal closing, BigDecimal overdue, BigDecimal collectedOnTime) {
    }

    public record Unapplied(UUID customerId, BigDecimal amount) {
    }

    public record Result(List<Part> parts, List<Unapplied> unapplied) {
    }

    /** A group's figures. Owed = still open on charges less unapplied credit. */
    public record Row(String key, BigDecimal opening, BigDecimal charged, BigDecimal collected, BigDecimal otherCredits,
                      BigDecimal closing, BigDecimal overdue, BigDecimal collectedOnTime, BigDecimal unapplied) {

        public BigDecimal getOwed() {
            return closing.subtract(unapplied);
        }

        /** Collected as a % of what was there to collect (owed at the start and charged), one decimal; null when nothing was. */
        public BigDecimal getRate() {
            BigDecimal due = opening.add(charged);
            return due.signum() <= 0 ? null : collected.multiply(BigDecimal.valueOf(100)).divide(due, 1, RoundingMode.HALF_UP);
        }

        /** What was collected by its due day as a % of what was collected; null without collections. */
        public BigDecimal getOnTimePercent() {
            return collected.signum() <= 0 ? null
                    : collectedOnTime.multiply(BigDecimal.valueOf(100)).divide(collected, 1, RoundingMode.HALF_UP);
        }

        /** Past due as a % of what is still owed on charges; null when nothing is. */
        public BigDecimal getOverduePercent() {
            return closing.signum() <= 0 ? null : overdue.multiply(BigDecimal.valueOf(100)).divide(closing, 1, RoundingMode.HALF_UP);
        }

        Row plus(Row o) {
            return new Row(key, opening.add(o.opening), charged.add(o.charged), collected.add(o.collected), otherCredits.add(o.otherCredits),
                    closing.add(o.closing), overdue.add(o.overdue), collectedOnTime.add(o.collectedOnTime), unapplied.add(o.unapplied));
        }
    }

    private static final BigDecimal Z = BigDecimal.ZERO;

    private record Settlement(LocalDate on, boolean collection, BigDecimal amount) {
    }

    private static final class Open {
        final Charge charge;
        BigDecimal left;
        final List<Settlement> settled = new ArrayList<>();

        Open(Charge charge) {
            this.charge = charge;
            this.left = charge.amount();
        }
    }

    private static final class Pool {
        final LocalDate date;
        final boolean collection;
        BigDecimal left;

        Pool(Credit credit) {
            this.date = credit.date();
            this.collection = credit.collection();
            this.left = credit.amount();
        }
    }

    /** Settles each customer's charges up to the period's end and works out every charge's figures. */
    public static Result allocate(List<Charge> charges, List<Credit> credits, LocalDate from, LocalDate to) {
        Map<UUID, List<Charge>> chargesOf = new LinkedHashMap<>();
        for (Charge c : charges) {
            if (!c.date().isAfter(to) && c.amount().signum() > 0) {
                chargesOf.computeIfAbsent(c.customerId(), k -> new ArrayList<>()).add(c);
            }
        }
        Map<UUID, List<Credit>> creditsOf = new LinkedHashMap<>();
        for (Credit c : credits) {
            if (!c.date().isAfter(to) && c.amount().signum() > 0) {
                creditsOf.computeIfAbsent(c.customerId(), k -> new ArrayList<>()).add(c);
                chargesOf.computeIfAbsent(c.customerId(), k -> new ArrayList<>());
            }
        }
        List<Part> parts = new ArrayList<>();
        List<Unapplied> unapplied = new ArrayList<>();
        for (Map.Entry<UUID, List<Charge>> e : chargesOf.entrySet()) {
            List<Charge> cs = new ArrayList<>(e.getValue());
            cs.sort(Comparator.comparing(Charge::date));
            List<Credit> rs = new ArrayList<>(creditsOf.getOrDefault(e.getKey(), List.of()));
            rs.sort(Comparator.comparing(Credit::date));
            List<Open> open = new ArrayList<>();
            List<Open> all = new ArrayList<>();
            Deque<Pool> pool = new ArrayDeque<>();
            int i = 0, j = 0;
            // Day by day: a day's charges come before its credits (a payment on the invoice's day settles it)
            while (i < cs.size() || j < rs.size()) {
                boolean chargeNext = j >= rs.size() || (i < cs.size() && !cs.get(i).date().isAfter(rs.get(j).date()));
                if (chargeNext) {
                    Open o = new Open(cs.get(i++));
                    all.add(o);
                    while (o.left.signum() > 0 && !pool.isEmpty()) {
                        Pool p = pool.peekFirst();
                        BigDecimal take = o.left.min(p.left);
                        o.settled.add(new Settlement(o.charge.date(), p.collection, take));
                        o.left = o.left.subtract(take);
                        p.left = p.left.subtract(take);
                        if (p.left.signum() == 0) {
                            pool.pollFirst();
                        }
                    }
                    if (o.left.signum() > 0) {
                        open.add(o);
                    }
                } else {
                    Credit c = rs.get(j++);
                    BigDecimal left = c.amount();
                    Iterator<Open> it = open.iterator();
                    while (left.signum() > 0 && it.hasNext()) {
                        Open o = it.next();
                        BigDecimal take = o.left.min(left);
                        o.settled.add(new Settlement(c.date(), c.collection(), take));
                        o.left = o.left.subtract(take);
                        left = left.subtract(take);
                        if (o.left.signum() == 0) {
                            it.remove();
                        }
                    }
                    if (left.signum() > 0) {
                        Pool p = new Pool(c);
                        p.left = left;
                        pool.addLast(p);
                    }
                }
            }
            for (Open o : all) {
                parts.add(part(o, from, to));
            }
            BigDecimal rest = pool.stream().map(p -> p.left).reduce(Z, BigDecimal::add);
            if (rest.signum() > 0) {
                unapplied.add(new Unapplied(e.getKey(), rest));
            }
        }
        return new Result(parts, unapplied);
    }

    private static Part part(Open o, LocalDate from, LocalDate to) {
        Charge c = o.charge;
        boolean before = c.date().isBefore(from);
        BigDecimal settledBefore = Z, collected = Z, other = Z, onTime = Z, settledByEnd = Z;
        for (Settlement s : o.settled) {
            settledByEnd = settledByEnd.add(s.amount());
            if (s.on().isBefore(from)) {
                settledBefore = settledBefore.add(s.amount());
            } else if (s.collection()) {
                collected = collected.add(s.amount());
                if (c.due() == null || !s.on().isAfter(c.due())) {
                    onTime = onTime.add(s.amount());
                }
            } else {
                other = other.add(s.amount());
            }
        }
        BigDecimal opening = before ? c.amount().subtract(settledBefore) : Z;
        BigDecimal charged = before ? Z : c.amount();
        BigDecimal closing = c.amount().subtract(settledByEnd);
        BigDecimal overdue = c.due() != null && c.due().isBefore(to) ? closing : Z;
        return new Part(c, opening, charged, collected, other, closing, overdue, onTime);
    }

    /**
     * The rows of a grouping, largest owed first: parts by {@code partKey}, unapplied credit by {@code customerKey}
     * (the customer's territory, nobody's sales, or the customer).
     */
    public static List<Row> group(Result result, Function<Part, String> partKey, Function<UUID, String> customerKey) {
        Map<String, Row> rows = new LinkedHashMap<>();
        for (Part p : result.parts()) {
            String k = Objects.requireNonNullElse(partKey.apply(p), "");
            rows.merge(k, new Row(k, p.opening(), p.charged(), p.collected(), p.otherCredits(), p.closing(), p.overdue(), p.collectedOnTime(), Z),
                    Row::plus);
        }
        for (Unapplied u : result.unapplied()) {
            String k = Objects.requireNonNullElse(customerKey.apply(u.customerId()), "");
            rows.merge(k, new Row(k, Z, Z, Z, Z, Z, Z, Z, u.amount()), Row::plus);
        }
        List<Row> list = new ArrayList<>(rows.values().stream().filter(CollectionAnalysis::moved).toList());
        list.sort(Comparator.comparing(Row::getOwed).reversed().thenComparing(Row::key));
        return list;
    }

    public static Row total(List<Row> rows) {
        return rows.stream().reduce(new Row("", Z, Z, Z, Z, Z, Z, Z, Z), Row::plus);
    }

    private static boolean moved(Row r) {
        return r.opening().signum() != 0 || r.charged().signum() != 0 || r.collected().signum() != 0 || r.otherCredits().signum() != 0
                || r.closing().signum() != 0 || r.unapplied().signum() != 0;
    }
}
