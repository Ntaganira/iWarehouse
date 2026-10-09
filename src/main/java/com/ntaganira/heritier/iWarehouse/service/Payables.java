package com.ntaganira.heritier.iWarehouse.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : Payables.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : What is owed to a supplier, per currency (ACC-08, ACC-09). Each payable line has its amount in its
 *               currency and in RWF as booked: owed (an invoice, a bill) or settled (a payment, a credit note). Per
 *               currency, settlements take the oldest items first, and the RWF of what they take goes with it (all of
 *               an item's, or its share by amount, rounded half up): so each item keeps the RWF it was booked at, and
 *               a payment knows the RWF of what it settles, against which its own RWF gives the realised FX gain or
 *               loss. A settlement with nothing to take waits for the next items. Pure, unit-tested.
 * </pre>
 */
public final class Payables {

    private Payables() {
    }

    /** A payable line: date, currency, amount in it and in RWF, both positive when owed, negative when settled. */
    public record Line(LocalDate date, String currency, BigDecimal amount, BigDecimal base) {
    }

    /** What is left of an item owed. */
    public record Item(LocalDate date, BigDecimal amount, BigDecimal base) {
    }

    /** One currency's account: the items still open, oldest first, and any settlement waiting for the next items. */
    public record Open(String currency, List<Item> items, BigDecimal waitingAmount, BigDecimal waitingBase) {

        /** Owed in the currency, less what is waiting. */
        public BigDecimal getAmount() {
            return items.stream().map(Item::amount).reduce(BigDecimal.ZERO, BigDecimal::add).subtract(waitingAmount);
        }

        /** Owed in RWF as booked, less what is waiting. */
        public BigDecimal getBase() {
            return items.stream().map(Item::base).reduce(BigDecimal.ZERO, BigDecimal::add).subtract(waitingBase);
        }
    }

    /** A settlement: the amount taken and the RWF it was booked at. */
    public record Settlement(BigDecimal amount, BigDecimal base) {
    }

    private static final class Left {
        final LocalDate date;
        BigDecimal amount;
        BigDecimal base;

        Left(LocalDate date, BigDecimal amount, BigDecimal base) {
            this.date = date;
            this.amount = amount;
            this.base = base;
        }
    }

    /** The open items per currency, from the lines in date order (lines of one day in the order given). */
    public static Map<String, Open> open(List<Line> lines) {
        List<Line> sorted = new ArrayList<>(lines);
        sorted.sort(Comparator.comparing(Line::date));
        Map<String, Deque<Left>> items = new TreeMap<>();
        Map<String, BigDecimal[]> waiting = new TreeMap<>();
        for (Line l : sorted) {
            Deque<Left> open = items.computeIfAbsent(l.currency(), k -> new ArrayDeque<>());
            BigDecimal[] wait = waiting.computeIfAbsent(l.currency(), k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            if (l.amount().signum() > 0) {
                Left item = new Left(l.date(), l.amount(), l.base());
                if (wait[0].signum() > 0) {                     // a settlement waiting takes the new item first
                    BigDecimal take = wait[0].min(item.amount);
                    BigDecimal base = take.compareTo(item.amount) == 0 ? item.base : share(item, take);
                    BigDecimal waitBase = take.compareTo(wait[0]) == 0 ? wait[1] : wait[1].multiply(take).divide(wait[0], 2, RoundingMode.HALF_UP);
                    item.amount = item.amount.subtract(take);
                    item.base = item.base.subtract(base);
                    wait[0] = wait[0].subtract(take);
                    wait[1] = wait[1].subtract(waitBase);
                }
                if (item.amount.signum() > 0) {
                    open.addLast(item);
                }
            } else if (l.amount().signum() < 0) {
                BigDecimal amount = l.amount().negate();
                BigDecimal base = l.base().negate();
                Settlement s = take(open, amount);
                wait[0] = wait[0].add(amount.subtract(s.amount()));
                wait[1] = wait[1].add(base.subtract(s.base()).max(BigDecimal.ZERO).min(base));
            }
        }
        Map<String, Open> result = new TreeMap<>();
        for (Map.Entry<String, Deque<Left>> e : items.entrySet()) {
            BigDecimal[] wait = waiting.get(e.getKey());
            List<Item> list = e.getValue().stream().map(x -> new Item(x.date, x.amount, x.base)).toList();
            result.put(e.getKey(), new Open(e.getKey(), list, wait[0], wait[1]));
        }
        return result;
    }

    /**
     * What a payment of {@code amount} in this currency settles: the oldest items first, with the RWF they were booked at.
     * Refused (IllegalArgumentException) above what is owed in it.
     */
    public static Settlement settle(Open open, BigDecimal amount) {
        if (amount.signum() <= 0 || amount.compareTo(open.getAmount()) > 0) {
            throw new IllegalArgumentException(amount + " " + open.currency() + " of " + open.getAmount());
        }
        Deque<Left> items = new ArrayDeque<>();
        open.items().forEach(i -> items.addLast(new Left(i.date(), i.amount(), i.base())));
        return take(items, amount);
    }

    private static Settlement take(Deque<Left> items, BigDecimal amount) {
        BigDecimal left = amount;
        BigDecimal base = BigDecimal.ZERO;
        while (left.signum() > 0 && !items.isEmpty()) {
            Left oldest = items.peekFirst();
            if (oldest.amount.compareTo(left) <= 0) {
                left = left.subtract(oldest.amount);
                base = base.add(oldest.base);
                items.removeFirst();
            } else {
                BigDecimal part = share(oldest, left);
                oldest.amount = oldest.amount.subtract(left);
                oldest.base = oldest.base.subtract(part);
                base = base.add(part);
                left = BigDecimal.ZERO;
            }
        }
        return new Settlement(amount.subtract(left), base);
    }

    /** The RWF of part of an item, by amount, half up to the franc cent. */
    private static BigDecimal share(Left item, BigDecimal amount) {
        return item.base.multiply(amount).divide(item.amount, 2, RoundingMode.HALF_UP);
    }
}
