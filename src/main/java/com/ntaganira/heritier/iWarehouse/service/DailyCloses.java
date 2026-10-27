package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.enums.PaymentMethod;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : DailyCloses.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The sums of the end-of-day sales report (F-12): each channel's sales split into cash, MoMo, card and
 *               transfer, and credit (on account, and a deposit's balance left to pay), the invoices it took; the
 *               totals. Pure and tested.
 * </pre>
 */
public final class DailyCloses {

    private DailyCloses() {
    }

    /** An invoice issued that day: its channel, what was paid at issue per method, the balance left to pay. */
    public record Sale(String channel, Map<PaymentMethod, BigDecimal> paid, BigDecimal balanceDue) {
    }

    public record ChannelRow(String channel, int invoices, BigDecimal cash, BigDecimal momo, BigDecimal other, BigDecimal credit) {

        public BigDecimal getTotal() {
            return cash.add(momo).add(other).add(credit);
        }

        ChannelRow plus(Sale s) {
            Map<PaymentMethod, BigDecimal> p = s.paid();
            return new ChannelRow(channel, invoices + 1, cash.add(p.getOrDefault(PaymentMethod.CASH, BigDecimal.ZERO)),
                    momo.add(p.getOrDefault(PaymentMethod.MOBILE_MONEY, BigDecimal.ZERO)),
                    other.add(p.getOrDefault(PaymentMethod.CARD, BigDecimal.ZERO)).add(p.getOrDefault(PaymentMethod.BANK_TRANSFER, BigDecimal.ZERO)),
                    credit.add(p.getOrDefault(PaymentMethod.CREDIT, BigDecimal.ZERO)).add(s.balanceDue() == null ? BigDecimal.ZERO : s.balanceDue()));
        }

        ChannelRow plus(ChannelRow o) {
            return new ChannelRow(channel, invoices + o.invoices, cash.add(o.cash), momo.add(o.momo), other.add(o.other), credit.add(o.credit));
        }
    }

    static ChannelRow empty(String channel) {
        return new ChannelRow(channel, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /** One row per channel, in the order of the channels given (a channel without sales is left out; one not listed comes last). */
    public static List<ChannelRow> byChannel(List<Sale> sales, List<String> order) {
        Map<String, ChannelRow> rows = new LinkedHashMap<>();
        order.forEach(c -> rows.put(c, empty(c)));
        for (Sale s : sales) {
            rows.put(s.channel(), rows.getOrDefault(s.channel(), empty(s.channel())).plus(s));
        }
        List<ChannelRow> list = new ArrayList<>();
        rows.values().stream().filter(r -> r.invoices() > 0).forEach(list::add);
        return list;
    }

    public static ChannelRow total(List<ChannelRow> rows) {
        return rows.stream().reduce(empty(null), ChannelRow::plus);
    }

    /** What came in less what was expected (negative when short). */
    public static BigDecimal variance(BigDecimal expected, BigDecimal actual) {
        return (actual == null ? BigDecimal.ZERO : actual).subtract(expected == null ? BigDecimal.ZERO : expected);
    }
}
