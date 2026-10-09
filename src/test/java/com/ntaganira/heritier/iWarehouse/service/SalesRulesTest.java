package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.enums.ChargeUnit;
import com.ntaganira.heritier.iWarehouse.enums.PaymentMethod;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** VAT on sales (TAX-01) and splitting a payment (POS-04, AT-08), without the database. */
class SalesRulesTest {

    private static final BigDecimal STANDARD = new BigDecimal("18.00");

    // ---------------------------------------------------------------- VAT

    @Test
    void aLineIsChargedInWholeFrancsWithVatIncluded() {
        // RETAIL prices include VAT: 27,000/m² x 7.2225 m² = 195,007.50 -> 195,008
        assertThat(Vat.lineAmount(new BigDecimal("27000"), new BigDecimal("7.2225"), 1, true, STANDARD, 0))
                .isEqualByComparingTo("195008");
        // CONTRACTOR prices exclude VAT: 22,881.36 x 2 m² x 1.18 = 54,000.01 -> 54,000
        assertThat(Vat.lineAmount(new BigDecimal("22881.36"), new BigDecimal("2.0000"), 1, false, STANDARD, 0))
                .isEqualByComparingTo("54000");
        // exempt glass: nothing added even when the list excludes VAT; quantity counts
        assertThat(Vat.lineAmount(new BigDecimal("1000"), new BigDecimal("0.2500"), 3, false, BigDecimal.ZERO, 0))
                .isEqualByComparingTo("750");
        assertThat(Vat.lineAmount(new BigDecimal("1000.50"), new BigDecimal("1.0000"), 1, true, STANDARD, 2))
                .isEqualByComparingTo("1000.50");                                   // a currency with decimals keeps them
    }

    @Test
    void vatIsWorkedOutPerTaxLetterOnTheInvoiceTotals() {
        Vat.Totals totals = Vat.totals(List.of(
                new Vat.Line("B", STANDARD, new BigDecimal("195008")),
                new Vat.Line("A", BigDecimal.ZERO, new BigDecimal("10000")),
                new Vat.Line("B", STANDARD, new BigDecimal("54000"))));

        assertThat(totals.groups()).extracting(Vat.Group::taxCode).containsExactly("A", "B");
        Vat.Group b = totals.groups().get(1);
        assertThat(b.gross()).isEqualByComparingTo("249008");
        assertThat(b.vat()).isEqualByComparingTo("37984.27");                      // 249,008 x 18 / 118
        assertThat(b.getNet()).isEqualByComparingTo("211023.73");
        assertThat(totals.groups().get(0).vat()).isEqualByComparingTo("0");
        assertThat(totals.gross()).isEqualByComparingTo("259008");
        assertThat(totals.vat()).isEqualByComparingTo("37984.27");
        assertThat(totals.net().add(totals.vat())).isEqualByComparingTo(totals.gross());
        assertThat(Vat.totals(List.of()).gross()).isEqualByComparingTo("0");
    }

    @Test
    void oneTaxLetterHasOneRate() {
        assertThatThrownBy(() -> Vat.totals(List.of(new Vat.Line("B", STANDARD, BigDecimal.TEN),
                new Vat.Line("B", new BigDecimal("16"), BigDecimal.TEN)))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void processingIsChargedOnWhatThePiecesNeed() {   // MD-06, POS-02
        assertThat(Pricing.serviceQuantity(ChargeUnit.M2, 600, 400, 3, null)).isEqualByComparingTo("0.72");     // 0.24 m² x 3
        assertThat(Pricing.serviceQuantity(ChargeUnit.METRE, 600, 400, 3, null)).isEqualByComparingTo("6");     // 2 m of edge x 3
        assertThat(Pricing.serviceQuantity(ChargeUnit.METRE, 1215, 333, 1, null)).isEqualByComparingTo("3.096");
        assertThat(Pricing.serviceQuantity(ChargeUnit.PIECE, 600, 400, 3, null)).isEqualByComparingTo("3");
        assertThat(Pricing.serviceQuantity(ChargeUnit.HOLE, 600, 400, 3, 4)).isEqualByComparingTo("12");
        assertThatThrownBy(() -> Pricing.serviceQuantity(ChargeUnit.HOLE, 600, 400, 3, null)).isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------- payments

    @Test
    void halfCashHalfMobileMoneyMakesTwoPaymentLines() {   // AT-08
        SalePayments.Split split = SalePayments.split(new BigDecimal("100000"),
                entered("50000", "50000", " MP-77812 ", null, null, null, null, null));

        assertThat(split.parts()).extracting(SalePayments.Part::method).containsExactly(PaymentMethod.CASH, PaymentMethod.MOBILE_MONEY);
        assertThat(split.amountOf(PaymentMethod.CASH)).isEqualByComparingTo("50000");
        assertThat(split.parts().get(1).reference()).isEqualTo("MP-77812");
        assertThat(split.change()).isEqualByComparingTo("0");
    }

    @Test
    void cashCoversTheRestAndTheDifferenceIsTheChange() {
        SalePayments.Split split = SalePayments.split(new BigDecimal("195008"),
                entered("100000", "100000", "MP-1", null, null, null, null, null));

        assertThat(split.amountOf(PaymentMethod.CASH)).isEqualByComparingTo("95008");   // what the till keeps
        assertThat(split.cashTendered()).isEqualByComparingTo("100000");
        assertThat(split.change()).isEqualByComparingTo("4992");

        SalePayments.Split onCredit = SalePayments.split(new BigDecimal("54000"), entered(null, null, null, null, null, null, null, "54000"));
        assertThat(onCredit.parts()).singleElement().extracting(SalePayments.Part::method).isEqualTo(PaymentMethod.CREDIT);
        assertThat(onCredit.cashTendered()).isNull();
    }

    @Test
    void wrongPaymentsAreRefusedWithTheFieldToFix() {
        BigDecimal total = new BigDecimal("100000");
        assertThatThrownBy(() -> SalePayments.split(total, entered("40000", null, null, "50000", "VISA-1", null, null, null)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getField()).isEqualTo("cash");
                    assertThat(e.getMessageKey()).isEqualTo("sale.pay.short");
                    assertThat(e.getArgs()).containsExactly(new BigDecimal("10000"));
                });
        assertThatThrownBy(() -> SalePayments.split(total, entered(null, "100000", null, null, null, null, null, null)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getField()).isEqualTo("mobileMoneyRef"));
        assertThatThrownBy(() -> SalePayments.split(total, entered(null, null, null, null, null, "120000", "TRF-9", null)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("sale.pay.tooMuch"));
        assertThatThrownBy(() -> SalePayments.split(total, entered("-5", null, null, null, null, null, null, null)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("sale.pay.amountInvalid"));
    }

    @Test
    void aDepositPaysPartAndLeavesTheBalance() {   // POS-08
        BigDecimal total = new BigDecimal("195008");
        BigDecimal minimum = new BigDecimal("97504");
        SalePayments.Split split = SalePayments.split(total, entered("60000", "40000", "MP-5", null, null, null, null, null), minimum);

        assertThat(split.amountOf(PaymentMethod.CASH)).isEqualByComparingTo("60000");   // all the cash handed over is kept
        assertThat(split.change()).isEqualByComparingTo("0");
        assertThat(split.balance()).isEqualByComparingTo("95008");

        // Enough to pay it all: a full payment with change, nothing left
        SalePayments.Split all = SalePayments.split(total, entered("200000", null, null, null, null, null, null, null), minimum);
        assertThat(all.balance()).isEqualByComparingTo("0");
        assertThat(all.change()).isEqualByComparingTo("4992");
        assertThat(SalePayments.split(total, entered("200000", null, null, null, null, null, null, null)).balance()).isEqualByComparingTo("0");

        assertThatThrownBy(() -> SalePayments.split(total, entered("90000", null, null, null, null, null, null, null), minimum))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getField()).isEqualTo("cash");
                    assertThat(e.getMessageKey()).isEqualTo("sale.pay.deposit.short");
                    assertThat(e.getArgs()).containsExactly(minimum, new BigDecimal("7504"));
                });
    }

    @Test
    void theSmallestDepositCoversTheShareAndTheGlassTakenNow() {   // POS-08
        assertThat(SalePayments.depositMinimum(new BigDecimal("195008"), new BigDecimal("50"), BigDecimal.ZERO)).isEqualByComparingTo("97504");
        assertThat(SalePayments.depositMinimum(new BigDecimal("100001"), new BigDecimal("50"), BigDecimal.ZERO)).isEqualByComparingTo("50001");   // rounded up
        assertThat(SalePayments.depositMinimum(new BigDecimal("100000"), new BigDecimal("30"), new BigDecimal("45000"))).isEqualByComparingTo("45000");
        assertThat(SalePayments.depositMinimum(new BigDecimal("100000"), BigDecimal.ZERO, BigDecimal.ZERO)).isEqualByComparingTo("1");
        assertThat(SalePayments.depositMinimum(new BigDecimal("100000"), new BigDecimal("100"), BigDecimal.ZERO)).isEqualByComparingTo("100000");
    }

    private static SalePayments.Entered entered(String cash, String momo, String momoRef, String card, String cardRef, String bank,
                                                String bankRef, String credit) {
        return new SalePayments.Entered(amount(cash), amount(momo), momoRef, amount(card), cardRef, amount(bank), bankRef, amount(credit));
    }

    private static BigDecimal amount(String value) {
        return value == null ? null : new BigDecimal(value);
    }
}
