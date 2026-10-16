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
 * - File      : DiscountAnalysis.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : The discount analysis of a period (SDP 15-16, R.06, M.15): the lines of issued invoices charged below
 *               their list price, each with what it would have charged at the list price and what it did, grouped by
 *               seller, customer, glass or processing, or invoice, largest discount first, with the discount as a % of
 *               the list amount. Lines charged at or above the list price are not discounts. Pure and tested.
 * </pre>
 */
public final class DiscountAnalysis {

    private DiscountAnalysis() {
    }

    public enum GroupBy { SELLER, CUSTOMER, PRODUCT, INVOICE }

    /** A line charged below its list price: the invoice, its day, who sold it, the customer, the item and the reason. */
    public record Line(UUID invoiceId, String invoiceNumber, LocalDate day, String seller, String sellerName, UUID customerId,
                       String customerName, String item, String reason, BigDecimal listAmount, BigDecimal amount) {

        public BigDecimal getDiscount() {
            return listAmount.subtract(amount);
        }
    }

    /** A group: its key and label, the lines and invoices in it, the list amount, the amount charged and the discount. */
    public record Row(String key, String label, UUID id, long lines, long invoices, BigDecimal listAmount, BigDecimal amount) {

        public BigDecimal getDiscount() {
            return listAmount.subtract(amount);
        }

        /** The discount as a % of the list amount, one decimal. */
        public BigDecimal getPercent() {
            return listAmount.signum() <= 0 ? BigDecimal.ZERO.setScale(1)
                    : getDiscount().multiply(BigDecimal.valueOf(100)).divide(listAmount, 1, RoundingMode.HALF_UP);
        }
    }

    /** Only the lines that were discounted. */
    public static List<Line> discounted(Collection<Line> lines) {
        return lines.stream().filter(l -> l.getDiscount().signum() > 0).toList();
    }

    public static List<Row> group(Collection<Line> lines, GroupBy by) {
        Function<Line, String> key = switch (by) {
            case SELLER -> l -> l.seller() == null ? "" : l.seller();
            case CUSTOMER -> l -> String.valueOf(l.customerId());
            case PRODUCT -> l -> l.item() == null ? "" : l.item();
            case INVOICE -> l -> String.valueOf(l.invoiceId());
        };
        Map<String, List<Line>> groups = new LinkedHashMap<>();
        for (Line l : discounted(lines)) {
            groups.computeIfAbsent(key.apply(l), k -> new ArrayList<>()).add(l);
        }
        List<Row> rows = new ArrayList<>();
        groups.forEach((k, list) -> {
            Line first = list.get(0);
            String label = switch (by) {
                case SELLER -> first.sellerName() != null ? first.sellerName() : first.seller();
                case CUSTOMER -> first.customerName();
                case PRODUCT -> first.item();
                case INVOICE -> first.invoiceNumber();
            };
            UUID id = switch (by) {
                case CUSTOMER -> first.customerId();
                case INVOICE -> first.invoiceId();
                default -> null;
            };
            rows.add(row(k, label, id, list));
        });
        rows.sort(Comparator.comparing(Row::getDiscount).reversed().thenComparing(r -> r.label() == null ? "" : r.label()));
        return rows;
    }

    public static Row total(Collection<Line> lines) {
        return row("", null, null, discounted(lines));
    }

    private static Row row(String key, String label, UUID id, List<Line> lines) {
        BigDecimal list = BigDecimal.ZERO;
        BigDecimal amount = BigDecimal.ZERO;
        Set<UUID> invoices = new HashSet<>();
        for (Line l : lines) {
            list = list.add(l.listAmount());
            amount = amount.add(l.amount());
            invoices.add(l.invoiceId());
        }
        return new Row(key, label, id, lines.size(), invoices.size(), list, amount);
    }
}
