package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.enums.PaymentMethod;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : MobileSalesTest.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : The rules of a sale from a vehicle (MPOS-04, MPOS-05): the customer's frozen list then the default list,
 *               line amounts exactly as the counter's (chargeable area, VAT added when the list excludes it, whole RWF),
 *               the driver's discount limit, cash and mobile money adding up to the total. The PWA does the same sums;
 *               these cases are the ones its integers must agree with.
 * </pre>
 */
class MobileSalesTest {

    private final UUID retail = UUID.randomUUID();
    private final UUID contractor = UUID.randomUUID();
    private final UUID clear6 = UUID.randomUUID();
    private final UUID mirror4 = UUID.randomUUID();
    private final MobileSales.ListTerms retailTerms = new MobileSales.ListTerms(retail, true, new BigDecimal("0.2500"));
    private final MobileSales.ListTerms contractorTerms = new MobileSales.ListTerms(contractor, false, new BigDecimal("0.5000"));
    private final MobileSales.Snapshot snapshot = new MobileSales.Snapshot(retail,
            Map.of(retail, retailTerms, contractor, contractorTerms),
            Map.of(retail, Map.of(clear6, new BigDecimal("27000.00"), mirror4, new BigDecimal("31000.00")),
                    contractor, Map.of(clear6, new BigDecimal("21500.00"))));

    @Test
    void theCustomersListThenTheDefaultList() {
        assertThat(snapshot.resolve(contractor, clear6)).get().satisfies(r -> {
            assertThat(r.list()).isEqualTo(contractorTerms);
            assertThat(r.pricePerM2()).isEqualByComparingTo("21500");
        });
        // The contractor list has no mirror: the default list's price
        assertThat(snapshot.resolve(contractor, mirror4)).get().satisfies(r -> {
            assertThat(r.list()).isEqualTo(retailTerms);
            assertThat(r.pricePerM2()).isEqualByComparingTo("31000");
        });
        // A walk-in, or a list the trip did not freeze, pays the default prices
        assertThat(snapshot.resolve(null, clear6)).get().satisfies(r -> assertThat(r.list()).isEqualTo(retailTerms));
        assertThat(snapshot.resolve(UUID.randomUUID(), clear6)).get().satisfies(r -> assertThat(r.pricePerM2()).isEqualByComparingTo("27000"));
        assertThat(snapshot.resolve(null, UUID.randomUUID())).isEmpty();
    }

    @Test
    void lineAmountsAreTheCountersAmounts() {
        // 1200 x 800 = 0.96 m² at 27,000 VAT included: 25,920
        assertThat(MobileSales.lineAmount(new BigDecimal("27000"), 1200, 800, retailTerms, new BigDecimal("18"), 0))
                .isEqualByComparingTo("25920");
        // VAT added (contractor list): 0.96 x 21,500 x 1.18 = 24,355.2 -> 24,355
        assertThat(MobileSales.lineAmount(new BigDecimal("21500"), 1200, 800, contractorTerms, new BigDecimal("18"), 0))
                .isEqualByComparingTo("24355");
        // A small piece is charged its list's minimum area: 300 x 300 = 0.09 m², charged 0.5 m²
        assertThat(MobileSales.lineAmount(new BigDecimal("21500"), 300, 300, contractorTerms, new BigDecimal("18"), 0))
                .isEqualByComparingTo("12685");
        // Half a franc rounds up, as the counter does
        assertThat(MobileSales.lineAmount(new BigDecimal("10001"), 500, 1000, retailTerms, new BigDecimal("18"), 0))
                .isEqualByComparingTo("5001");                                       // 5,000.5
        assertThat(MobileSales.lineAmount(new BigDecimal("27000"), 1200, 800, retailTerms, new BigDecimal("18"), 0))
                .isEqualByComparingTo(Vat.lineAmount(new BigDecimal("27000"), Pricing.chargeableArea(1200, 800, new BigDecimal("0.25")), 1,
                        true, new BigDecimal("18"), 0));
    }

    @Test
    void aLowerPriceStaysWithinTheDriversLimit() {
        BigDecimal list = new BigDecimal("27000");
        assertThat(MobileSales.withinLimit(list, list, BigDecimal.ZERO)).isTrue();
        assertThat(MobileSales.withinLimit(list, new BigDecimal("28000"), BigDecimal.ZERO)).isTrue();   // more is always allowed
        assertThat(MobileSales.withinLimit(list, new BigDecimal("26999"), BigDecimal.ZERO)).isTrue();    // 0.0037% rounds to 0.00%, as at the counter
        assertThat(MobileSales.withinLimit(list, new BigDecimal("26990"), BigDecimal.ZERO)).isFalse();   // 0.04%
        assertThat(MobileSales.withinLimit(list, new BigDecimal("24300"), new BigDecimal("10"))).isTrue(); // exactly 10%
        assertThat(MobileSales.withinLimit(list, new BigDecimal("24290"), new BigDecimal("10"))).isFalse();   // 10.04%
        assertThat(MobileSales.withinLimit(list, new BigDecimal("-1"), new BigDecimal("100"))).isFalse();
    }

    @Test
    void cashAndMobileMoneyAddUpToTheTotal() {
        BigDecimal total = new BigDecimal("25920");
        assertThat(MobileSales.paymentProblem(List.of(cash("25920")), total)).isEmpty();
        assertThat(MobileSales.paymentProblem(List.of(cash("20000"), momo("5920", "MP-77")), total)).isEmpty();
        assertThat(MobileSales.paymentProblem(List.of(cash("20000")), total)).get().asString().contains("paid 20000");
        assertThat(MobileSales.paymentProblem(List.of(cash("20000"), momo("5920", " ")), total)).get().asString().contains("reference");
        assertThat(MobileSales.paymentProblem(List.of(new MobileSales.Payment(PaymentMethod.CREDIT, total, null)), total)).isPresent();
        assertThat(MobileSales.paymentProblem(List.of(cash("0"), momo("25920", "MP-1")), total)).isPresent();
        assertThat(MobileSales.paymentProblem(List.of(), total)).isPresent();
    }

    @Test
    void theFirstAmountThatDiffers() {
        List<BigDecimal> server = List.of(new BigDecimal("25920.00"), new BigDecimal("12685.00"));
        assertThat(MobileSales.firstDifference(List.of(new BigDecimal("25920"), new BigDecimal("12685")), server)).isEqualTo(-1);
        assertThat(MobileSales.firstDifference(List.of(new BigDecimal("25920"), new BigDecimal("12684")), server)).isEqualTo(1);
        assertThat(MobileSales.firstDifference(java.util.Arrays.asList(null, new BigDecimal("12685")), server)).isZero();
    }

    private static MobileSales.Payment cash(String amount) {
        return new MobileSales.Payment(PaymentMethod.CASH, new BigDecimal(amount), null);
    }

    private static MobileSales.Payment momo(String amount, String reference) {
        return new MobileSales.Payment(PaymentMethod.MOBILE_MONEY, new BigDecimal(amount), reference);
    }
}
