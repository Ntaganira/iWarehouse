package com.ntaganira.heritier.iWarehouse.service;

import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.decoder.Decoder;
import com.ntaganira.heritier.iWarehouse.entity.PurchaseOrderLine;
import com.ntaganira.heritier.iWarehouse.enums.PurchaseOrderStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure rules of purchasing and stock: costing and MAC (PRC-05), order totals (PRC-01), rack limits (MD-03), labels (INV-03). */
class PurchasingRulesTest {

    // ---------------------------------------------------------------- costing

    @Test
    void costPerM2IsThePriceTimesTheRateTo4Decimals() {
        assertThat(Costing.costPerM2(new BigDecimal("4.35"), new BigDecimal("1450.255"))).isEqualByComparingTo("6308.6093");
        assertThat(Costing.costPerM2(new BigDecimal("27000"), BigDecimal.ONE)).isEqualByComparingTo("27000.0000");
    }

    @Test
    void unitCostIsAreaTimesCostPerM2To2Decimals() {
        // 3210 x 2250 = 7.2225 m2 at 6,308.6093 RWF/m2 = 45,563.93...
        assertThat(Costing.unitCost(new BigDecimal("7.2225"), new BigDecimal("6308.6093"))).isEqualByComparingTo("45563.93");
    }

    @Test
    void movingAverageWeighsStockHeldAndStockAdded() {
        // 100 m2 held at 6,000 + 50 m2 worth 330,000 (6,600/m2) -> 990,000 / 150 = 6,200
        assertThat(Costing.movingAverage(new BigDecimal("100"), new BigDecimal("6000"), new BigDecimal("50"), new BigDecimal("330000")))
                .isEqualByComparingTo("6200.0000");
    }

    @Test
    void movingAverageOfAFirstReceiptIsItsOwnCost() {
        assertThat(Costing.movingAverage(BigDecimal.ZERO, null, new BigDecimal("144.45"), new BigDecimal("911278")))
                .isEqualByComparingTo("6308.6051");
        // MAC known but nothing held any more: the new stock sets it
        assertThat(Costing.movingAverage(BigDecimal.ZERO, new BigDecimal("5000"), new BigDecimal("10"), new BigDecimal("70000")))
                .isEqualByComparingTo("7000.0000");
    }

    @Test
    void movingAverageIsUnchangedWhenNothingIsAdded() {
        assertThat(Costing.movingAverage(new BigDecimal("100"), new BigDecimal("6000"), BigDecimal.ZERO, BigDecimal.ZERO))
                .isEqualByComparingTo("6000");
        assertThat(Costing.movingAverage(BigDecimal.ZERO, null, BigDecimal.ZERO, BigDecimal.ZERO)).isNull();
    }

    @Test
    void unitWeightIsAreaTimesKgPerM2() {
        // 6 mm x 2.5 = 15 kg/m2; 7.2225 m2 -> 108.34 kg
        assertThat(GlassProducts.weightKg(new BigDecimal("7.2225"), GlassProducts.weightPerM2(new BigDecimal("6"), new BigDecimal("2.5"))))
                .isEqualByComparingTo("108.34");
    }

    // ---------------------------------------------------------------- order totals

    @Test
    void orderTotalIsRoundedOnceToTheCurrencyDecimals() {
        // 20 x 7.2225 m2 x 4.35 = 628.3575 ; 20 x 5.1840 m2 (2400 x 2160) x 3.125 = 324.00
        List<PurchaseOrderLine> lines = List.of(line(3210, 2250, 20, "4.35", 0), line(2400, 2160, 20, "3.125", 5));
        PurchaseOrders.Totals usd = PurchaseOrders.totals(lines, 2);
        assertThat(usd.sheets()).isEqualTo(40);
        assertThat(usd.received()).isEqualTo(5);
        assertThat(usd.outstanding()).isEqualTo(35);
        assertThat(usd.areaM2()).isEqualByComparingTo("248.13");
        assertThat(usd.amount()).isEqualByComparingTo("952.36");
        assertThat(PurchaseOrders.totals(lines, 0).amount()).isEqualByComparingTo("952");
    }

    @Test
    void lineAreaUsesTheRoundedSheetArea() {
        // 1001 x 1001 = 1.002001 m2 -> 1.0020 per sheet, like a stock unit's area
        assertThat(PurchaseOrders.lineArea(3, 1001, 1001)).isEqualByComparingTo("3.0060");
    }

    @Test
    void orderIsReceivedWhenEveryLineHasAllItsSheets() {
        assertThat(PurchaseOrders.statusAfterReceipt(List.of(line(3210, 2250, 20, "4", 20), line(3210, 2250, 10, "4", 10))))
                .isEqualTo(PurchaseOrderStatus.RECEIVED);
        assertThat(PurchaseOrders.statusAfterReceipt(List.of(line(3210, 2250, 20, "4", 20), line(3210, 2250, 10, "4", 9))))
                .isEqualTo(PurchaseOrderStatus.PARTIALLY_RECEIVED);
    }

    // ---------------------------------------------------------------- rack limits

    @Test
    void rackLoadChecksPiecesAndKgAgainstLimits() {
        RackLoad load = new RackLoad(28, new BigDecimal("2900.50")).plus(2, new BigDecimal("99.50"));
        assertThat(load.pieces()).isEqualTo(30);
        assertThat(load.kg()).isEqualByComparingTo("3000.00");
        assertThat(load.exceedsPieces(30)).isFalse();
        assertThat(load.exceedsKg(3000)).isFalse();
        assertThat(load.plus(1, BigDecimal.ONE).exceedsPieces(30)).isTrue();
        assertThat(load.plus(0, new BigDecimal("0.01")).exceedsKg(3000)).isTrue();
        assertThat(load.exceedsPieces(null)).isFalse();
        assertThat(load.exceedsKg(null)).isFalse();
    }

    // ---------------------------------------------------------------- labels

    @Test
    void qrCodeDecodesToTheLabelCode() throws Exception {
        BitMatrix matrix = Labels.qrMatrix("U-WH-000123");
        assertThat(new Decoder().decode(matrix).getText()).isEqualTo("U-WH-000123");
        String svg = Labels.qrSvg("U-WH-000123");
        assertThat(svg).startsWith("<svg").contains("viewBox=\"0 0 " + matrix.getWidth() + " ").contains("aria-label=\"U-WH-000123\"");
    }

    @Test
    void zplHasOneLabelPerUnitAndNoCommandCharactersInData() {
        String zpl = Labels.zpl(List.of(
                new Labels.Label("U-WH-000001", "CLR-6", "6", 3210, 2250, "C^01"),
                new Labels.Label("U-WH-000002", "LAM-6.38", "6.38", 2440, 1830, null)));
        assertThat(zpl.split("\\^XA", -1)).hasSize(3);
        assertThat(zpl.split("\\^XZ", -1)).hasSize(3);
        assertThat(zpl).contains("^PW400").contains("^LL240")
                .contains("^FDMA,U-WH-000001^FS").contains("3210 x 2250 mm").contains("LAM-6.38  6.38 mm")
                .contains("^FDC-01^FS");
        assertThat(zpl).doesNotContain("C^01");
    }

    private static PurchaseOrderLine line(int w, int h, int qty, String price, int received) {
        PurchaseOrderLine line = new PurchaseOrderLine();
        line.setWidthMm(w);
        line.setHeightMm(h);
        line.setQuantity(qty);
        line.setPricePerM2(new BigDecimal(price));
        line.setReceivedQty(received);
        return line;
    }
}
