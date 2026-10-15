package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.enums.JournalSource;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : ReceivableMovement.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : The movement of receivables over a period (BP Table 9): per customer the opening balance, the new
 *               credit (credit sales and deposit balances), the collections (balances and account payments), the credit
 *               notes, the write-offs, anything else, and the closing balance, all from the receivable account's lines.
 *               Also the credit sales of a period and the days sales outstanding (Annex D, F-02). Pure and tested.
 * </pre>
 */
public final class ReceivableMovement {

    private ReceivableMovement() {
    }

    static final Set<JournalSource> NEW_CREDIT = Set.of(JournalSource.SALES_INVOICE, JournalSource.MOBILE_SALE, JournalSource.SALES_DELIVERY);
    static final Set<JournalSource> COLLECTIONS = Set.of(JournalSource.SALES_BALANCE, JournalSource.CUSTOMER_PAYMENT);

    /** A line of the receivable account naming a customer. */
    public record Entry(UUID customerId, LocalDate date, JournalSource source, BigDecimal debit, BigDecimal credit) {
    }

    public record Row(UUID customerId, BigDecimal opening, BigDecimal newCredit, BigDecimal collections, BigDecimal creditNotes,
                      BigDecimal writeOffs, BigDecimal other) {

        public BigDecimal getClosing() {
            return opening.add(newCredit).subtract(collections).subtract(creditNotes).subtract(writeOffs).add(other);
        }

        boolean isEmpty() {
            return opening.signum() == 0 && newCredit.signum() == 0 && collections.signum() == 0 && creditNotes.signum() == 0
                    && writeOffs.signum() == 0 && other.signum() == 0;
        }

        Row plus(Row o) {
            return new Row(null, opening.add(o.opening), newCredit.add(o.newCredit), collections.add(o.collections),
                    creditNotes.add(o.creditNotes), writeOffs.add(o.writeOffs), other.add(o.other));
        }
    }

    private static final Row ZERO = new Row(null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

    /** Each customer with a balance or a movement, in the order first seen; lines after the period are left out. */
    public static List<Row> of(List<Entry> entries, LocalDate from, LocalDate to) {
        Map<UUID, BigDecimal[]> sums = new LinkedHashMap<>();
        for (Entry e : entries) {
            if (e.date().isAfter(to)) {
                continue;
            }
            BigDecimal[] s = sums.computeIfAbsent(e.customerId(), k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
            BigDecimal net = e.debit().subtract(e.credit());
            if (e.date().isBefore(from)) {
                s[0] = s[0].add(net);
            } else if (NEW_CREDIT.contains(e.source())) {
                s[1] = s[1].add(net);
            } else if (COLLECTIONS.contains(e.source())) {
                s[2] = s[2].subtract(net);
            } else if (e.source() == JournalSource.CREDIT_NOTE) {
                s[3] = s[3].subtract(net);
            } else if (e.source() == JournalSource.RECEIVABLE_WRITE_OFF) {
                s[4] = s[4].subtract(net);
            } else {
                s[5] = s[5].add(net);
            }
        }
        List<Row> rows = new ArrayList<>();
        sums.forEach((id, s) -> {
            Row row = new Row(id, s[0], s[1], s[2], s[3], s[4], s[5]);
            if (!row.isEmpty()) {
                rows.add(row);
            }
        });
        return rows;
    }

    public static Row total(List<Row> rows) {
        return rows.stream().reduce(ZERO, Row::plus);
    }

    /** Credit granted in a period: what the new-credit documents put on the receivable account. */
    public static BigDecimal creditSales(List<Entry> entries, LocalDate from, LocalDate to) {
        return entries.stream().filter(e -> NEW_CREDIT.contains(e.source()) && !e.date().isBefore(from) && !e.date().isAfter(to))
                .map(e -> e.debit().subtract(e.credit())).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Days sales outstanding: what is owed over the credit sales of so many days, times the days (one decimal); null without credit sales. */
    public static BigDecimal dso(BigDecimal receivables, BigDecimal creditSales, int days) {
        if (creditSales == null || creditSales.signum() <= 0) {
            return null;
        }
        return receivables.multiply(BigDecimal.valueOf(days)).divide(creditSales, 1, RoundingMode.HALF_UP);
    }

    /** A part of a total in per cent (one decimal); zero without a total. */
    public static BigDecimal share(BigDecimal part, BigDecimal total) {
        if (total == null || total.signum() <= 0) {
            return BigDecimal.ZERO.setScale(1);
        }
        return part.multiply(BigDecimal.valueOf(100)).divide(total, 1, RoundingMode.HALF_UP);
    }
}
