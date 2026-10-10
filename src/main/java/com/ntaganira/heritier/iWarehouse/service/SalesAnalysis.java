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
 * - File      : SalesAnalysis.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : Sales by customer, glass or processing, salesperson, day, month or invoice, with the gross margin at MAC
 *               (RPT-05). It covers the invoices issued in a period, each less its credit notes (whenever issued), so
 *               an invoice's margin is final once its pieces are handed over:
 *               - net sales (VAT out): each invoice's net amount split over its lines by their amounts, less each
 *                 credit note's net amount split over its lines (LandedCost.split: the parts add up exactly), so every
 *                 grouping adds up to the invoices' net less their credit notes';
 *               - cost: the Cost of Goods Sold of the invoice's journals (sale, hand-over) less that of its credit
 *                 notes, as posted at MAC; each journal's amount is split over the glass by its inventory lines (the
 *                 stock value each glass lost or got back), or by the invoice's glass when it has none (cullet back);
 *                 processing has no cost;
 *               - m²: the glass's own area (width x height x pieces), less what came back.
 *               An invoice with pieces still to hand over has part of its cost to come ("pending"). Pure, unit-tested.
 * </pre>
 */
public final class SalesAnalysis {

    private static final int MONEY = 2;

    private SalesAnalysis() {
    }

    public enum GroupBy { CUSTOMER, PRODUCT, SALESPERSON, DAY, MONTH, INVOICE }

    /** An issued invoice: who bought, who sold, its net amount, and whether pieces are still to hand over. */
    public record Invoice(UUID id, String number, LocalDate date, UUID customerId, String customer, String buyer, String salesperson,
                          String salespersonName, BigDecimal net, boolean pending) {
    }

    /** An invoice line: what it sells (a glass by product id, or a processing service), its m² and amount (VAT included). */
    public record Line(UUID invoiceId, UUID lineId, String itemKey, String itemLabel, UUID productId, boolean glass, BigDecimal areaM2,
                       BigDecimal amount) {
    }

    /** A credit note of one of the invoices, with its net amount. */
    public record CreditNote(UUID id, UUID invoiceId, BigDecimal net) {
    }

    /** A credit note line: the invoice line it credits, the m² back and its amount (VAT included). */
    public record Credit(UUID creditNoteId, UUID lineId, BigDecimal areaM2, BigDecimal amount) {
    }

    /**
     * Cost of Goods Sold one journal posted for an invoice (its sale, a hand-over, a credit note: negative), and its inventory
     * lines' amounts per glass, to split it by.
     */
    public record Cost(UUID invoiceId, BigDecimal amount, Map<UUID, BigDecimal> weights) {
    }

    /** One invoice and one thing it sold. */
    public record Fact(Invoice invoice, String itemKey, String itemLabel, boolean glass, BigDecimal areaM2, BigDecimal net, BigDecimal cost) {
    }

    /** A line of the report: what it groups, how many invoices, m², net sales, cost and the margin. */
    public record Row(String key, String label, LocalDate date, Invoice invoice, long invoices, BigDecimal areaM2, BigDecimal net,
                      BigDecimal cost) {

        public BigDecimal getMargin() {
            return net.subtract(cost);
        }

        /** Margin as a percentage of net sales (1 decimal); null without sales. */
        public BigDecimal getMarginPercent() {
            return net.signum() == 0 ? null : getMargin().multiply(BigDecimal.valueOf(100)).divide(net, 1, RoundingMode.HALF_UP);
        }
    }

    /** The facts of the invoices: per invoice and thing sold. */
    public static List<Fact> facts(Collection<Invoice> invoices, Collection<Line> lines, Collection<CreditNote> creditNotes,
                                   Collection<Credit> credits, Collection<Cost> costs, Map<UUID, String> productCodes) {
        Map<UUID, Invoice> byId = new LinkedHashMap<>();
        invoices.forEach(i -> byId.put(i.id(), i));
        Map<UUID, Line> lineById = new HashMap<>();
        Map<UUID, List<Line>> linesOf = new HashMap<>();
        for (Line l : lines) {
            if (byId.containsKey(l.invoiceId())) {
                lineById.put(l.lineId(), l);
                linesOf.computeIfAbsent(l.invoiceId(), k -> new ArrayList<>()).add(l);
            }
        }
        // (invoice, item) -> [m², net, cost]
        Map<UUID, Map<String, BigDecimal[]>> sums = new LinkedHashMap<>();
        Map<String, Line> itemOf = new HashMap<>();
        for (Invoice inv : byId.values()) {
            List<Line> own = linesOf.getOrDefault(inv.id(), List.of());
            List<BigDecimal> parts = split(inv.net(), own.stream().map(Line::amount).toList());
            for (int k = 0; k < own.size(); k++) {
                Line l = own.get(k);
                itemOf.putIfAbsent(l.itemKey(), l);
                add(sums, inv.id(), l.itemKey(), l.areaM2(), parts.get(k), BigDecimal.ZERO);
            }
        }
        Map<UUID, List<Credit>> creditsOf = new HashMap<>();
        credits.forEach(c -> creditsOf.computeIfAbsent(c.creditNoteId(), k -> new ArrayList<>()).add(c));
        for (CreditNote cn : creditNotes) {
            if (!byId.containsKey(cn.invoiceId())) {
                continue;
            }
            List<Credit> own = creditsOf.getOrDefault(cn.id(), List.of()).stream().filter(c -> lineById.containsKey(c.lineId())).toList();
            List<BigDecimal> parts = split(cn.net(), own.stream().map(Credit::amount).toList());
            for (int k = 0; k < own.size(); k++) {
                Line l = lineById.get(own.get(k).lineId());
                add(sums, cn.invoiceId(), l.itemKey(), own.get(k).areaM2().negate(), parts.get(k).negate(), BigDecimal.ZERO);
            }
        }
        for (Cost c : costs) {
            if (!byId.containsKey(c.invoiceId()) || c.amount().signum() == 0) {
                continue;
            }
            Map<UUID, BigDecimal> weights = new LinkedHashMap<>();
            c.weights().forEach((product, w) -> {
                if (product != null && w.signum() != 0) {
                    weights.merge(product, w.abs(), BigDecimal::add);
                }
            });
            if (weights.isEmpty()) {
                for (Line l : linesOf.getOrDefault(c.invoiceId(), List.of())) {
                    if (l.glass()) {
                        weights.merge(l.productId(), l.amount().max(BigDecimal.ZERO), BigDecimal::add);
                    }
                }
            }
            if (weights.isEmpty()) {
                continue;
            }
            List<UUID> products = new ArrayList<>(weights.keySet());
            List<BigDecimal> parts = split(c.amount(), products.stream().map(weights::get).toList());
            for (int k = 0; k < products.size(); k++) {
                String key = glassKey(products.get(k));
                itemOf.putIfAbsent(key, new Line(c.invoiceId(), null, key, productCodes.getOrDefault(products.get(k), ""), products.get(k), true,
                        BigDecimal.ZERO, BigDecimal.ZERO));
                add(sums, c.invoiceId(), key, BigDecimal.ZERO, BigDecimal.ZERO, parts.get(k));
            }
        }
        List<Fact> facts = new ArrayList<>();
        for (Map.Entry<UUID, Map<String, BigDecimal[]>> e : sums.entrySet()) {
            Invoice inv = byId.get(e.getKey());
            for (Map.Entry<String, BigDecimal[]> item : e.getValue().entrySet()) {
                Line l = itemOf.get(item.getKey());
                BigDecimal[] v = item.getValue();
                facts.add(new Fact(inv, item.getKey(), l.itemLabel(), l.glass(), v[0], v[1], v[2]));
            }
        }
        return facts;
    }

    /** The key of a glass item: its product id. */
    public static String glassKey(UUID productId) {
        return "P:" + productId;
    }

    /** The key of a processing item: its service id. */
    public static String serviceKey(UUID serviceId) {
        return "S:" + serviceId;
    }

    /** One row per customer, item, salesperson, day, month or invoice. */
    public static List<Row> group(Collection<Fact> facts, GroupBy by) {
        Function<Fact, String> key = switch (by) {
            case CUSTOMER -> f -> String.valueOf(f.invoice().customerId());
            case PRODUCT -> Fact::itemKey;
            case SALESPERSON -> f -> String.valueOf(f.invoice().salesperson());
            case DAY -> f -> f.invoice().date().toString();
            case MONTH -> f -> f.invoice().date().withDayOfMonth(1).toString();
            case INVOICE -> f -> f.invoice().id().toString();
        };
        Map<String, List<Fact>> grouped = new LinkedHashMap<>();
        facts.forEach(f -> grouped.computeIfAbsent(key.apply(f), k -> new ArrayList<>()).add(f));
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<String, List<Fact>> e : grouped.entrySet()) {
            Fact first = e.getValue().get(0);
            String label = switch (by) {
                case CUSTOMER -> first.invoice().customer();
                case PRODUCT -> first.itemLabel();
                case SALESPERSON -> first.invoice().salespersonName();
                case DAY, MONTH, INVOICE -> first.invoice().number();
            };
            LocalDate date = switch (by) {
                case DAY, INVOICE -> first.invoice().date();
                case MONTH -> first.invoice().date().withDayOfMonth(1);
                default -> null;
            };
            rows.add(row(e.getKey(), label, date, by == GroupBy.INVOICE ? first.invoice() : null, e.getValue(), by == GroupBy.PRODUCT));
        }
        Comparator<Row> order = switch (by) {
            case DAY, MONTH -> Comparator.comparing(Row::date);
            case INVOICE -> Comparator.comparing(Row::date).thenComparing(r -> r.invoice().number());
            default -> Comparator.comparing(Row::net).reversed().thenComparing(r -> r.label() == null ? "" : r.label());
        };
        rows.sort(order);
        return rows;
    }

    /** All the facts in one row (m² of the glass only). */
    public static Row total(Collection<Fact> facts) {
        return row("total", null, null, null, facts, true);
    }

    /** The invoices among the facts with pieces still to hand over. */
    public static long pending(Collection<Fact> facts) {
        return facts.stream().map(Fact::invoice).filter(Invoice::pending).map(Invoice::id).distinct().count();
    }

    private static Row row(String key, String label, LocalDate date, Invoice invoice, Collection<Fact> facts, boolean withArea) {
        Set<UUID> invoices = new HashSet<>();
        BigDecimal area = BigDecimal.ZERO;
        BigDecimal net = BigDecimal.ZERO;
        BigDecimal cost = BigDecimal.ZERO;
        for (Fact f : facts) {
            invoices.add(f.invoice().id());
            if (f.glass()) {
                area = area.add(f.areaM2());
            }
            net = net.add(f.net());
            cost = cost.add(f.cost());
        }
        return new Row(key, label, date, invoice, invoices.size(), withArea ? area : null, net, cost);
    }

    private static void add(Map<UUID, Map<String, BigDecimal[]>> sums, UUID invoice, String item, BigDecimal area, BigDecimal net,
                            BigDecimal cost) {
        BigDecimal[] v = sums.computeIfAbsent(invoice, k -> new LinkedHashMap<>())
                .computeIfAbsent(item, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
        v[0] = v[0].add(area);
        v[1] = v[1].add(net);
        v[2] = v[2].add(cost);
    }

    /** {@code total} over the amounts, adding up exactly; all to the first part when the amounts add up to nothing. */
    private static List<BigDecimal> split(BigDecimal total, List<BigDecimal> amounts) {
        if (amounts.isEmpty()) {
            return List.of();
        }
        BigDecimal sum = amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.signum() <= 0 || amounts.stream().anyMatch(a -> a.signum() < 0)) {
            List<BigDecimal> parts = new ArrayList<>(Collections.nCopies(amounts.size(), BigDecimal.ZERO.setScale(MONEY)));
            parts.set(0, total.setScale(MONEY, RoundingMode.HALF_UP));
            return parts;
        }
        return LandedCost.split(total, amounts, MONEY);
    }
}
