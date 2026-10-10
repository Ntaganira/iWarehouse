package com.ntaganira.heritier.iWarehouse.ebm;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.ebm
 * - File      : EbmRequestsTest.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : The VSDC sale and refund requests say exactly what the document does (TAX-02): amounts VAT included,
 *               VAT per letter as on the invoice, split over the lines to the cent; and the simulator accepts them.
 * </pre>
 */
class EbmRequestsTest {

    private static final LocalDateTime ISSUED = LocalDateTime.of(2026, 10, 10, 14, 5, 9);
    private static final Map<String, BigDecimal> RATES = Map.of("A", BigDecimal.ZERO, "B", new BigDecimal("18.00"), "C", BigDecimal.ZERO);

    private static EbmRequests.Line glass(String tax, String rate, String area, String amount) {
        return new EbmRequests.Line("RW2NTXM2X0000001", "3017170000", "CLR-6 CLEAR 6 mm 3210 x 2250", "M2", new BigDecimal(area), tax,
                new BigDecimal(rate), new BigDecimal(amount));
    }

    private static EbmRequests.Document sale(List<EbmRequests.Line> lines) {
        return new EbmRequests.Document(15, 0, EbmCodes.SALE, "102938475", "Jean Habimana", "+250788000000", "123456", "01", ISSUED,
                ISSUED.toLocalDate(), null, 3, "cashier1", "Glass Rwanda Ltd", "KN 5 Rd, Kigali", RATES, lines);
    }

    @Test
    void aSaleCarriesTheInvoicesAmountsAndItsVatPerLetterSplitOverTheLines() {
        Vsdc.SaleRequest r = EbmRequests.sale("100200300", "00", sale(List.of(
                glass("B", "18", "7.2225", "195008"),
                glass("A", "0", "1.0000", "27000"),
                new EbmRequests.Line("RW3NTXM0000002", "7213150000", "Edging", "M", new BigDecimal("4.0000"), "B",
                        new BigDecimal("18"), new BigDecimal("6000")))));

        assertThat(r.invcNo()).isEqualTo(15);
        assertThat(r.orgInvcNo()).isZero();
        assertThat(r.salesTyCd()).isEqualTo("N");
        assertThat(r.rcptTyCd()).isEqualTo("S");
        assertThat(r.salesSttsCd()).isEqualTo("02");
        assertThat(r.cfmDt()).isEqualTo("20261010140509");
        assertThat(r.salesDt()).isEqualTo("20261010");
        assertThat(r.stockRlsDt()).isEqualTo("20261010140509");
        assertThat(r.rfdDt()).isNull();
        assertThat(r.prcOrdCd()).isEqualTo("123456");
        assertThat(r.totItemCnt()).isEqualTo(3);

        // B: 195,008 + 6,000 = 201,008, VAT 201,008 x 18 / 118 = 30,662.24 (as Vat.totals has it on the invoice)
        assertThat(r.taxblAmtB()).isEqualByComparingTo("201008");
        assertThat(r.taxAmtB()).isEqualByComparingTo("30662.24");
        assertThat(r.taxRtB()).isEqualByComparingTo("18");
        assertThat(r.taxblAmtA()).isEqualByComparingTo("27000");
        assertThat(r.taxAmtA()).isEqualByComparingTo("0");
        assertThat(r.taxblAmtC()).isEqualByComparingTo("0");
        assertThat(r.taxRtD()).isEqualByComparingTo("0");
        assertThat(r.totTaxblAmt()).isEqualByComparingTo("228008");
        assertThat(r.totAmt()).isEqualByComparingTo("228008");
        assertThat(r.totTaxAmt()).isEqualByComparingTo("30662.24");

        Vsdc.SaleItem sheet = r.itemList().get(0);
        assertThat(sheet.itemSeq()).isEqualTo(1);
        assertThat(sheet.qty()).isEqualByComparingTo("7.22");                      // m², 2 decimals
        assertThat(sheet.prc()).isEqualByComparingTo("27009.42");                  // the amount over the quantity
        assertThat(sheet.splyAmt()).isEqualByComparingTo("195008");
        assertThat(sheet.taxblAmt()).isEqualByComparingTo("195008");
        assertThat(sheet.totAmt()).isEqualByComparingTo("195008");
        assertThat(sheet.dcAmt()).isEqualByComparingTo("0");
        assertThat(sheet.taxAmt()).isEqualByComparingTo("29746.99");               // 30,662.24 split by amount
        assertThat(r.itemList().get(2).taxAmt()).isEqualByComparingTo("915.25");
        assertThat(r.itemList().get(2).prc()).isEqualByComparingTo("1500");
        assertThat(r.itemList()).allSatisfy(i -> {
            assertThat(i.pkgUnitCd()).isEqualTo("NT");
            assertThat(i.qty().scale()).isEqualTo(2);
            assertThat(i.taxAmt().scale()).isEqualTo(2);
        });

        assertThat(r.receipt().custTin()).isEqualTo("102938475");
        assertThat(r.receipt().rptNo()).isEqualTo(3);
        assertThat(r.receipt().trdeNm()).isEqualTo("Glass Rwanda Ltd");
        assertThat(SimulatedVsdc.check(r)).isEmpty();                            // RRA's checks pass
    }

    @Test
    void aRefundNamesItsSaleAndItsReasonWithPositiveAmounts() {
        EbmRequests.Document refund = new EbmRequests.Document(16, 15, EbmCodes.REFUND, null, "Walk-in", null, null, "01",
                ISSUED.plusDays(1), ISSUED.toLocalDate().plusDays(1), "03", 4, "cashier1", "Glass Rwanda Ltd", null, RATES,
                List.of(glass("B", "18", "7.2225", "195008")));

        Vsdc.SaleRequest r = EbmRequests.sale("100200300", "00", refund);

        assertThat(r.rcptTyCd()).isEqualTo("R");
        assertThat(r.orgInvcNo()).isEqualTo(15);
        assertThat(r.salesSttsCd()).isEqualTo("05");
        assertThat(r.rfdRsnCd()).isEqualTo("03");
        assertThat(r.rfdDt()).isEqualTo("20261011140509");
        assertThat(r.stockRlsDt()).isNull();
        assertThat(r.totAmt()).isEqualByComparingTo("195008");
        assertThat(r.taxAmtB()).isEqualByComparingTo("29746.98");
        assertThat(SimulatedVsdc.check(r)).isEmpty();
    }

    @Test
    void aTinyQuantityIsSentAsOneHundredthAndOnlyLettersAToDExist() {
        assertThat(EbmRequests.quantity(new BigDecimal("0.0040"))).isEqualByComparingTo("0.01");
        assertThat(EbmRequests.quantity(new BigDecimal("0.0650"))).isEqualByComparingTo("0.07");
        assertThatThrownBy(() -> EbmRequests.sale("100200300", "00", sale(List.of(glass("E", "5", "1", "1000")))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Tax letter E");
        assertThatThrownBy(() -> EbmRequests.sale("100200300", "00", sale(List.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theSimulatorRefusesWhatRraRefuses() {
        Vsdc.SaleRequest good = EbmRequests.sale("100200300", "00", sale(List.of(glass("B", "18", "1", "1000"))));
        Vsdc.SaleRequest badTotals = new Vsdc.SaleRequest(good.tin(), good.bhfId(), good.invcNo(), good.orgInvcNo(), good.custTin(),
                good.prcOrdCd(), good.custNm(), good.salesTyCd(), good.rcptTyCd(), good.pmtTyCd(), good.salesSttsCd(), good.cfmDt(),
                good.salesDt(), good.stockRlsDt(), null, null, null, null, 2, good.taxblAmtA(), good.taxblAmtB(), good.taxblAmtC(),
                good.taxblAmtD(), good.taxRtA(), good.taxRtB(), good.taxRtC(), good.taxRtD(), good.taxAmtA(), new BigDecimal("1.00"),
                good.taxAmtC(), good.taxAmtD(), good.totTaxblAmt(), good.totTaxAmt(), good.totAmt(), "N", null, "u", "u", "u", "u",
                good.receipt(), good.itemList());

        assertThat(SimulatedVsdc.check(badTotals)).containsExactly("totItemCnt : Item Count error");
        Vsdc.SaleRequest badTax = new Vsdc.SaleRequest(good.tin(), good.bhfId(), good.invcNo(), good.orgInvcNo(), good.custTin(),
                good.prcOrdCd(), good.custNm(), good.salesTyCd(), good.rcptTyCd(), good.pmtTyCd(), good.salesSttsCd(), good.cfmDt(),
                good.salesDt(), good.stockRlsDt(), null, null, null, null, 1, good.taxblAmtA(), good.taxblAmtB(), good.taxblAmtC(),
                good.taxblAmtD(), good.taxRtA(), good.taxRtB(), good.taxRtC(), good.taxRtD(), good.taxAmtA(), new BigDecimal("1.00"),
                good.taxAmtC(), good.taxAmtD(), good.totTaxblAmt(), good.totTaxAmt(), good.totAmt(), "N", null, "u", "u", "u", "u",
                good.receipt(), good.itemList());
        assertThat(SimulatedVsdc.check(badTax)).singleElement().satisfies(e -> assertThat(e).startsWith("taxAmtB"));
        Vsdc.SaleRequest badTin = new Vsdc.SaleRequest("10020030", good.bhfId(), good.invcNo(), good.orgInvcNo(), "12345678",
                good.prcOrdCd(), good.custNm(), good.salesTyCd(), good.rcptTyCd(), good.pmtTyCd(), good.salesSttsCd(), good.cfmDt(),
                good.salesDt(), good.stockRlsDt(), null, null, null, null, 1, good.taxblAmtA(), good.taxblAmtB(), good.taxblAmtC(),
                good.taxblAmtD(), good.taxRtA(), good.taxRtB(), good.taxRtC(), good.taxRtD(), good.taxAmtA(), good.taxAmtB(),
                good.taxAmtC(), good.taxAmtD(), good.totTaxblAmt(), good.totTaxAmt(), good.totAmt(), "N", null, "u", "u", "u", "u",
                good.receipt(), good.itemList());
        assertThat(SimulatedVsdc.check(badTin)).containsExactly("tin : 9 digits", "custTin : 9 digits");
    }

    @Test
    void anItemIsRegisteredByCodeClassUnitAndLetter() {
        Vsdc.ItemRequest r = EbmRequests.item("100200300", "00", new EbmRequests.Item("RW2NTXM2X0000001", "3017170000", "2",
                "CLR-6 CLEAR 6 mm", "RW", "M2", "B", new BigDecimal("27000")), "cashier1");

        assertThat(r.itemCd()).isEqualTo("RW2NTXM2X0000001");
        assertThat(r.itemTyCd()).isEqualTo("2");
        assertThat(r.pkgUnitCd()).isEqualTo("NT");
        assertThat(r.qtyUnitCd()).isEqualTo("M2");
        assertThat(r.dftPrc()).isEqualByComparingTo("27000");
        assertThat(r.dftPrc().scale()).isEqualTo(2);
        assertThat(r.isrcAplcbYn()).isEqualTo("N");
        assertThat(r.useYn()).isEqualTo("Y");
        assertThat(r.regrId()).isEqualTo("cashier1");
    }
}
