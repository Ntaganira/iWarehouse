package com.ntaganira.heritier.iWarehouse.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : SalesControlAnalysisTest.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The discount analysis (SDP 15-16, R.06) and collection performance (BP 5.1, Q.05) on worked examples:
 *               discounts grouped and totalled, credits settling the oldest charges first, collections credited to the
 *               salesperson of the invoice they paid, on time or late, and unapplied credit.
 * </pre>
 */
class SalesControlAnalysisTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static final UUID UMUCYO = UUID.randomUUID();
    private static final UUID KAMANZI = UUID.randomUUID();
    private static final LocalDate D1 = LocalDate.of(2026, 9, 10);

    // ---------------------------------------------------------------- discounts

    private static DiscountAnalysis.Line line(UUID invoice, String number, String seller, UUID customer, String item, String list, String charged) {
        return new DiscountAnalysis.Line(invoice, number, D1, seller, seller == null ? null : seller.toUpperCase(), customer,
                customer == UMUCYO ? "Umucyo" : "Kamanzi", item, null, bd(list), bd(charged));
    }

    @Test
    void discountsBySellerLargestFirst() {
        UUID i1 = UUID.randomUUID(), i2 = UUID.randomUUID(), i3 = UUID.randomUUID();
        List<DiscountAnalysis.Line> lines = List.of(
                line(i1, "INV-1", "cashier1", UMUCYO, "CLR-6", "200000", "180000"),
                line(i1, "INV-1", "cashier1", UMUCYO, "Edging", "10000", "9000"),
                line(i2, "INV-2", "cashier2", KAMANZI, "CLR-6", "100000", "50000"),
                // charged above the list price: not a discount
                line(i3, "INV-3", "cashier1", KAMANZI, "CLR-8", "100000", "110000"));

        List<DiscountAnalysis.Row> bySeller = DiscountAnalysis.group(lines, DiscountAnalysis.GroupBy.SELLER);
        assertThat(bySeller).extracting(DiscountAnalysis.Row::key).containsExactly("cashier2", "cashier1");
        assertThat(bySeller.get(0).getDiscount()).isEqualByComparingTo("50000");
        assertThat(bySeller.get(0).getPercent()).isEqualByComparingTo("50.0");
        DiscountAnalysis.Row cashier1 = bySeller.get(1);
        assertThat(cashier1.label()).isEqualTo("CASHIER1");
        assertThat(cashier1.lines()).isEqualTo(2);
        assertThat(cashier1.invoices()).isEqualTo(1);
        assertThat(cashier1.getDiscount()).isEqualByComparingTo("21000");
        assertThat(cashier1.getPercent()).isEqualByComparingTo("10.0");

        DiscountAnalysis.Row total = DiscountAnalysis.total(lines);
        assertThat(total.lines()).isEqualTo(3);
        assertThat(total.invoices()).isEqualTo(2);
        assertThat(total.listAmount()).isEqualByComparingTo("310000");
        assertThat(total.getDiscount()).isEqualByComparingTo("71000");

        List<DiscountAnalysis.Row> byGlass = DiscountAnalysis.group(lines, DiscountAnalysis.GroupBy.PRODUCT);
        assertThat(byGlass).extracting(DiscountAnalysis.Row::label).containsExactly("CLR-6", "Edging");
        assertThat(DiscountAnalysis.group(lines, DiscountAnalysis.GroupBy.INVOICE).get(0).id()).isEqualTo(i2);
    }

    // ---------------------------------------------------------------- collections

    private static CollectionAnalysis.Charge charge(UUID customer, LocalDate day, int terms, String seller, String amount) {
        return new CollectionAnalysis.Charge(customer, day, day.plusDays(terms), seller, bd(amount));
    }

    private static CollectionAnalysis.Credit paid(UUID customer, LocalDate day, String amount) {
        return new CollectionAnalysis.Credit(customer, day, true, bd(amount));
    }

    @Test
    void creditsSettleTheOldestChargesFirst() {
        LocalDate from = LocalDate.of(2026, 10, 1), to = LocalDate.of(2026, 10, 31);
        List<CollectionAnalysis.Charge> charges = List.of(
                // owed before October: 300,000 sold by alice in August (30 days), 100,000 paid in September
                charge(UMUCYO, LocalDate.of(2026, 8, 20), 30, "alice", "300000"),
                // October: 500,000 sold by bob, due 15/10
                charge(UMUCYO, LocalDate.of(2026, 10, 5), 10, "bob", "500000"));
        List<CollectionAnalysis.Credit> credits = List.of(
                paid(UMUCYO, LocalDate.of(2026, 9, 15), "100000"),
                // 250,000 on 10/10: 200,000 finishes alice's (late: due 19/09), 50,000 goes to bob's (on time)
                paid(UMUCYO, LocalDate.of(2026, 10, 10), "250000"),
                // a credit note of 50,000 on 20/10 settles more of bob's: not a collection
                new CollectionAnalysis.Credit(UMUCYO, LocalDate.of(2026, 10, 20), false, bd("50000")));

        CollectionAnalysis.Result result = CollectionAnalysis.allocate(charges, credits, from, to);
        List<CollectionAnalysis.Row> bySeller = CollectionAnalysis.group(result, p -> p.charge().salesperson(), id -> "");
        CollectionAnalysis.Row alice = bySeller.stream().filter(r -> r.key().equals("alice")).findFirst().orElseThrow();
        assertThat(alice.opening()).isEqualByComparingTo("200000");
        assertThat(alice.charged()).isEqualByComparingTo("0");
        assertThat(alice.collected()).isEqualByComparingTo("200000");
        assertThat(alice.collectedOnTime()).isEqualByComparingTo("0");
        assertThat(alice.getOwed()).isEqualByComparingTo("0");
        assertThat(alice.getRate()).isEqualByComparingTo("100.0");

        CollectionAnalysis.Row bob = bySeller.stream().filter(r -> r.key().equals("bob")).findFirst().orElseThrow();
        assertThat(bob.charged()).isEqualByComparingTo("500000");
        assertThat(bob.collected()).isEqualByComparingTo("50000");
        assertThat(bob.getOnTimePercent()).isEqualByComparingTo("100.0");
        assertThat(bob.otherCredits()).isEqualByComparingTo("50000");
        assertThat(bob.getOwed()).isEqualByComparingTo("400000");
        // due 15/10, still owed at 31/10: past due
        assertThat(bob.overdue()).isEqualByComparingTo("400000");
        assertThat(bob.getRate()).isEqualByComparingTo("10.0");

        CollectionAnalysis.Row total = CollectionAnalysis.total(bySeller);
        assertThat(total.opening().add(total.charged()).subtract(total.collected()).subtract(total.otherCredits()))
                .isEqualByComparingTo(total.getOwed());
    }

    @Test
    void aCreditBeforeAnyChargeWaitsForTheNext() {
        LocalDate from = LocalDate.of(2026, 10, 1), to = LocalDate.of(2026, 10, 31);
        // Kamanzi pays 100,000 ahead on 02/10, is charged 60,000 on 05/10 by carol: 40,000 is left as unapplied credit
        CollectionAnalysis.Result result = CollectionAnalysis.allocate(
                List.of(charge(KAMANZI, LocalDate.of(2026, 10, 5), 0, "carol", "60000")),
                List.of(paid(KAMANZI, LocalDate.of(2026, 10, 2), "100000")), from, to);
        assertThat(result.unapplied()).singleElement().satisfies(u -> assertThat(u.amount()).isEqualByComparingTo("40000"));
        CollectionAnalysis.Part part = result.parts().get(0);
        // settled on the charge's day, so collected in the period and on time
        assertThat(part.collected()).isEqualByComparingTo("60000");
        assertThat(part.collectedOnTime()).isEqualByComparingTo("60000");
        assertThat(part.closing()).isEqualByComparingTo("0");

        List<CollectionAnalysis.Row> byCustomer = CollectionAnalysis.group(result, p -> p.charge().customerId().toString(), UUID::toString);
        assertThat(byCustomer).singleElement().satisfies(r -> {
            assertThat(r.unapplied()).isEqualByComparingTo("40000");
            assertThat(r.getOwed()).isEqualByComparingTo("-40000");
        });
    }

    @Test
    void linesAfterThePeriodAreLeftOut() {
        LocalDate from = LocalDate.of(2026, 10, 1), to = LocalDate.of(2026, 10, 31);
        CollectionAnalysis.Result result = CollectionAnalysis.allocate(
                List.of(charge(UMUCYO, LocalDate.of(2026, 10, 20), 30, "alice", "100000"),
                        charge(UMUCYO, LocalDate.of(2026, 11, 2), 30, "alice", "900000")),
                List.of(paid(UMUCYO, LocalDate.of(2026, 11, 5), "100000")), from, to);
        assertThat(result.parts()).singleElement().satisfies(p -> {
            assertThat(p.charged()).isEqualByComparingTo("100000");
            assertThat(p.collected()).isEqualByComparingTo("0");
            assertThat(p.closing()).isEqualByComparingTo("100000");
            // due 19/11: not past due at 31/10
            assertThat(p.overdue()).isEqualByComparingTo("0");
        });
    }
}
