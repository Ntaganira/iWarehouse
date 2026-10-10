package com.ntaganira.heritier.iWarehouse.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sales with the gross margin at MAC (RPT-05): each invoice's net split exactly over its lines, credit notes taken off,
 * each journal's cost split over the glass by its inventory lines (or the invoice's glass), and every grouping adding up
 * to the same total.
 */
class SalesAnalysisTest {

    private final UUID glass1 = UUID.randomUUID();
    private final UUID glass2 = UUID.randomUUID();
    private final UUID edging = UUID.randomUUID();
    private final UUID customerA = UUID.randomUUID();
    private final UUID customerB = UUID.randomUUID();
    private final Map<UUID, String> codes = Map.of(glass1, "CLR-6", glass2, "MIR-4");

    private final SalesAnalysis.Invoice inv1 = invoice("INV-1", LocalDate.of(2026, 10, 1), customerA, "Umucyo", "cashier1", "1000.00", false);
    private final SalesAnalysis.Invoice inv2 = invoice("INV-2", LocalDate.of(2026, 10, 1), customerB, "Walk-in", "cashier2", "847.46", false);
    private final SalesAnalysis.Invoice inv3 = invoice("INV-3", LocalDate.of(2026, 10, 2), customerA, "Umucyo", "cashier1", "300.00", true);

    private final UUID l1 = UUID.randomUUID();
    private final UUID l2 = UUID.randomUUID();
    private final UUID l3 = UUID.randomUUID();
    private final UUID l4 = UUID.randomUUID();
    private final UUID l5 = UUID.randomUUID();
    private final List<SalesAnalysis.Line> lines = List.of(
            glassLine(inv1.id(), l1, glass1, "1.5000", "708"),
            new SalesAnalysis.Line(inv1.id(), l2, SalesAnalysis.serviceKey(edging), "Edging", glass1, false, BigDecimal.ZERO, new BigDecimal("472")),
            glassLine(inv2.id(), l3, glass1, "1.5000", "1000"),
            glassLine(inv3.id(), l4, glass1, "1.0000", "236"),
            glassLine(inv3.id(), l5, glass2, "0.5000", "118"));
    private final UUID cn1 = UUID.randomUUID();
    private final List<SalesAnalysis.CreditNote> creditNotes = List.of(new SalesAnalysis.CreditNote(cn1, inv2.id(), new BigDecimal("423.73")));
    private final List<SalesAnalysis.Credit> credits = List.of(new SalesAnalysis.Credit(cn1, l3, new BigDecimal("0.7500"), new BigDecimal("500")));
    private final List<SalesAnalysis.Cost> costs = List.of(
            new SalesAnalysis.Cost(inv1.id(), new BigDecimal("500.00"), Map.of(glass1, new BigDecimal("-500.00"))),
            new SalesAnalysis.Cost(inv2.id(), new BigDecimal("600.00"), Map.of(glass1, new BigDecimal("-600.00"))),
            new SalesAnalysis.Cost(inv2.id(), new BigDecimal("-300.00"), Map.of(glass1, new BigDecimal("300.00"))),   // back on the rack
            new SalesAnalysis.Cost(inv2.id(), new BigDecimal("-50.00"), Map.of()),                                     // cullet: no stock line
            new SalesAnalysis.Cost(inv3.id(), new BigDecimal("150.00"), Map.of(glass1, new BigDecimal("-90.00"), glass2, new BigDecimal("-60.00"))));

    private List<SalesAnalysis.Fact> facts() {
        return SalesAnalysis.facts(List.of(inv1, inv2, inv3), lines, creditNotes, credits, costs, codes);
    }

    @Test
    void anInvoicesNetIsSplitOverItsLinesLessItsCreditNotes() {
        List<SalesAnalysis.Row> rows = SalesAnalysis.group(facts(), SalesAnalysis.GroupBy.INVOICE);

        assertThat(rows).extracting(r -> r.invoice().number()).containsExactly("INV-1", "INV-2", "INV-3");
        assertThat(rows.get(0).net()).isEqualByComparingTo("1000.00");
        assertThat(rows.get(0).cost()).isEqualByComparingTo("500.00");
        assertThat(rows.get(1).net()).isEqualByComparingTo("423.73");          // 847.46 less the credit note's 423.73
        assertThat(rows.get(1).cost()).isEqualByComparingTo("250.00");         // 600 sold, 300 back on the rack, 50 to cullet
        assertThat(rows.get(1).getMargin()).isEqualByComparingTo("173.73");
        assertThat(rows.get(1).getMarginPercent()).isEqualByComparingTo("41.0");
        assertThat(rows.get(2).invoice().pending()).isTrue();
    }

    @Test
    void byGlassAndProcessingTheCostFollowsEachGlass() {
        List<SalesAnalysis.Row> rows = SalesAnalysis.group(facts(), SalesAnalysis.GroupBy.PRODUCT);

        assertThat(rows).extracting(SalesAnalysis.Row::label).containsExactly("CLR-6", "Edging", "MIR-4");   // largest net first
        SalesAnalysis.Row clear = rows.get(0);
        // net: 708/1180 of 1,000 = 600.00; 423.73 after the credit; 236/354 of 300 = 200.00
        assertThat(clear.net()).isEqualByComparingTo("1223.73");
        assertThat(clear.cost()).isEqualByComparingTo("840.00");               // 500 + 250 + 90
        assertThat(clear.areaM2()).isEqualByComparingTo("3.25");               // 1.5 + 1.5 - 0.75 + 1.0
        assertThat(clear.invoices()).isEqualTo(3);
        assertThat(rows.get(1).net()).isEqualByComparingTo("400.00");
        assertThat(rows.get(1).cost()).isEqualByComparingTo("0");
        assertThat(rows.get(2).net()).isEqualByComparingTo("100.00");
        assertThat(rows.get(2).cost()).isEqualByComparingTo("60.00");
    }

    @Test
    void everyGroupingAddsUpToTheSameTotal() {
        List<SalesAnalysis.Fact> facts = facts();
        SalesAnalysis.Row total = SalesAnalysis.total(facts);
        assertThat(total.net()).isEqualByComparingTo("1723.73");
        assertThat(total.cost()).isEqualByComparingTo("900.00");
        assertThat(total.invoices()).isEqualTo(3);
        assertThat(total.areaM2()).isEqualByComparingTo("3.75");               // glass only
        for (SalesAnalysis.GroupBy by : SalesAnalysis.GroupBy.values()) {
            List<SalesAnalysis.Row> rows = SalesAnalysis.group(facts, by);
            assertThat(rows.stream().map(SalesAnalysis.Row::net).reduce(BigDecimal.ZERO, BigDecimal::add)).as(by.name()).isEqualByComparingTo("1723.73");
            assertThat(rows.stream().map(SalesAnalysis.Row::cost).reduce(BigDecimal.ZERO, BigDecimal::add)).as(by.name()).isEqualByComparingTo("900.00");
        }
        assertThat(SalesAnalysis.pending(facts)).isEqualTo(1);
    }

    @Test
    void byCustomerSalespersonDayAndMonth() {
        List<SalesAnalysis.Fact> facts = facts();
        List<SalesAnalysis.Row> customers = SalesAnalysis.group(facts, SalesAnalysis.GroupBy.CUSTOMER);
        assertThat(customers).extracting(SalesAnalysis.Row::label).containsExactly("Umucyo", "Walk-in");
        assertThat(customers.get(0).invoices()).isEqualTo(2);
        assertThat(customers.get(0).net()).isEqualByComparingTo("1300.00");

        assertThat(SalesAnalysis.group(facts, SalesAnalysis.GroupBy.SALESPERSON)).extracting(SalesAnalysis.Row::label)
                .containsExactly("Cashier cashier1", "Cashier cashier2");
        List<SalesAnalysis.Row> days = SalesAnalysis.group(facts, SalesAnalysis.GroupBy.DAY);
        assertThat(days).extracting(SalesAnalysis.Row::date).containsExactly(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2));
        assertThat(days.get(0).net()).isEqualByComparingTo("1423.73");
        List<SalesAnalysis.Row> months = SalesAnalysis.group(facts, SalesAnalysis.GroupBy.MONTH);
        assertThat(months).hasSize(1);
        assertThat(months.get(0).date()).isEqualTo(LocalDate.of(2026, 10, 1));
    }

    @Test
    void partsAddUpExactlyAndAnEmptyInvoiceCountsNothing() {
        SalesAnalysis.Invoice odd = invoice("INV-9", LocalDate.of(2026, 10, 3), customerA, "Umucyo", "cashier1", "100.00", false);
        List<SalesAnalysis.Line> three = List.of(glassLine(odd.id(), UUID.randomUUID(), glass1, "1", "1"),
                glassLine(odd.id(), UUID.randomUUID(), glass2, "1", "1"),
                new SalesAnalysis.Line(odd.id(), UUID.randomUUID(), SalesAnalysis.serviceKey(edging), "Edging", glass1, false, BigDecimal.ZERO, BigDecimal.ONE));
        List<SalesAnalysis.Fact> facts = SalesAnalysis.facts(List.of(odd), three, List.of(), List.of(), List.of(), codes);
        assertThat(facts).extracting(f -> f.net().toPlainString()).containsExactlyInAnyOrder("33.34", "33.33", "33.33");
        assertThat(SalesAnalysis.total(facts).net()).isEqualByComparingTo("100.00");
        assertThat(SalesAnalysis.total(List.of()).getMarginPercent()).isNull();
    }

    private SalesAnalysis.Invoice invoice(String number, LocalDate date, UUID customer, String name, String cashier, String net, boolean pending) {
        return new SalesAnalysis.Invoice(UUID.randomUUID(), number, date, customer, name, null, cashier, "Cashier " + cashier, new BigDecimal(net), pending);
    }

    private SalesAnalysis.Line glassLine(UUID invoice, UUID line, UUID product, String area, String amount) {
        return new SalesAnalysis.Line(invoice, line, SalesAnalysis.glassKey(product), codes.get(product), product, true, new BigDecimal(area), new BigDecimal(amount));
    }
}
