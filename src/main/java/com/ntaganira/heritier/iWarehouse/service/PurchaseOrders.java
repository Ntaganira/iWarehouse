package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.PurchaseOrderLine;
import com.ntaganira.heritier.iWarehouse.enums.PurchaseOrderStatus;

import java.math.BigDecimal;
import java.util.List;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : PurchaseOrders.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Pure purchase order rules (PRC-01): line amounts and order totals in the order's
 *               currency (lines unrounded, the total rounded once to the currency's decimals), and the
 *               status an order takes after a receipt.
 * </pre>
 */
public final class PurchaseOrders {

    private PurchaseOrders() {
    }

    /** Sheets, m² and amount of an order; amount rounded to the currency's decimals. */
    public record Totals(int sheets, int received, BigDecimal areaM2, BigDecimal amount) {

        public int outstanding() {
            return Math.max(sheets - received, 0);
        }
    }

    /** m² of a line: sheets x m² of one sheet (rounded to 4 decimals, as stock units are). */
    public static BigDecimal lineArea(int quantity, int widthMm, int heightMm) {
        return Pricing.areaM2(widthMm, heightMm).multiply(BigDecimal.valueOf(quantity));
    }

    /** Amount of a line in the order's currency, unrounded: m² of the line x price per m². */
    public static BigDecimal lineAmount(int quantity, int widthMm, int heightMm, BigDecimal pricePerM2) {
        return lineArea(quantity, widthMm, heightMm).multiply(pricePerM2);
    }

    public static Totals totals(List<PurchaseOrderLine> lines, int currencyDecimals) {
        int sheets = 0;
        int received = 0;
        BigDecimal area = BigDecimal.ZERO;
        BigDecimal amount = BigDecimal.ZERO;
        for (PurchaseOrderLine line : lines) {
            sheets += line.getQuantity();
            received += line.getReceivedQty();
            area = area.add(lineArea(line.getQuantity(), line.getWidthMm(), line.getHeightMm()));
            amount = amount.add(lineAmount(line.getQuantity(), line.getWidthMm(), line.getHeightMm(), line.getPricePerM2()));
        }
        return new Totals(sheets, received, area, CurrencyMath.round(amount, currencyDecimals));
    }

    /** After a receipt: RECEIVED when every line has all its sheets, otherwise PARTIALLY_RECEIVED. */
    public static PurchaseOrderStatus statusAfterReceipt(List<PurchaseOrderLine> lines) {
        boolean complete = lines.stream().allMatch(l -> l.getReceivedQty() >= l.getQuantity());
        return complete ? PurchaseOrderStatus.RECEIVED : PurchaseOrderStatus.PARTIALLY_RECEIVED;
    }
}
