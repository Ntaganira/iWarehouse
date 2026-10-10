package com.ntaganira.heritier.iWarehouse.ebm;

import com.ntaganira.heritier.iWarehouse.service.LandedCost;
import com.ntaganira.heritier.iWarehouse.service.Vat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.ebm
 * - File      : EbmRequests.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : Builds the VSDC requests of an invoice or credit note (TAX-02) so the receipt says exactly what the
 *               document does. One item per document line: its amount, VAT included, is the supply and taxable amount
 *               (no discount field: the line's price is what was agreed), the quantity is the m² charged (glass) or the
 *               processing's quantity, at 2 decimals, and the unit price the amount over it. VAT per tax letter is the
 *               document's own (Vat.totals, as the invoice and the ledger have it), split over the letter's lines by
 *               amount so the lines add up to it exactly. A refund repeats the items with positive amounts and names
 *               its sale's invoice number. Only EBM's letters A to D exist. Pure, unit-tested.
 * </pre>
 */
public final class EbmRequests {

    static final List<String> LETTERS = List.of("A", "B", "C", "D");
    private static final BigDecimal SMALLEST_QUANTITY = new BigDecimal("0.01");
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private EbmRequests() {
    }

    /** One line as EBM sees it. {@code quantity} is in {@code quantityUnit}; {@code amount} includes VAT. */
    public record Line(String itemCode, String itemClass, String name, String quantityUnit, BigDecimal quantity, String taxCode,
                       BigDecimal rate, BigDecimal amount) {
    }

    /**
     * A sale (receipt type S) or a refund (R, naming its sale's {@code orgInvcNo}). {@code rates} are the rates of letters
     * A to D (the header carries all four); {@code reportNo} is the day's report number since the device was installed.
     */
    public record Document(long invcNo, long orgInvcNo, String receiptType, String buyerTin, String buyerName, String buyerPhone,
                           String purchaseCode, String paymentType, LocalDateTime confirmedAt, LocalDate salesDate,
                           String refundReason, long reportNo, String user, String tradeName, String address,
                           Map<String, BigDecimal> rates, List<Line> lines) {

        public boolean isRefund() {
            return EbmCodes.REFUND.equals(receiptType);
        }
    }

    /** A glass or processing to register (/items/saveItems). */
    public record Item(String itemCode, String itemClass, String itemType, String name, String origin, String quantityUnit,
                       String taxCode, BigDecimal defaultPrice) {
    }

    public static Vsdc.SaleRequest sale(String tin, String branchId, Document d) {
        if (d.lines().isEmpty()) {
            throw new IllegalArgumentException("A receipt needs at least one item");
        }
        for (Line l : d.lines()) {
            if (!LETTERS.contains(l.taxCode())) {
                throw new IllegalArgumentException("Tax letter " + l.taxCode() + " of " + l.name() + " is not one of EBM's A, B, C or D");
            }
        }
        List<BigDecimal> taxes = lineTaxes(d.lines());
        Map<String, BigDecimal> taxable = new HashMap<>();
        Map<String, BigDecimal> tax = new HashMap<>();
        Map<String, BigDecimal> rates = new HashMap<>();
        LETTERS.forEach(x -> {
            taxable.put(x, ZERO);
            tax.put(x, ZERO);
            rates.put(x, money(d.rates().getOrDefault(x, BigDecimal.ZERO)));
        });
        List<Vsdc.SaleItem> items = new ArrayList<>();
        BigDecimal totalTaxable = ZERO;
        BigDecimal totalTax = ZERO;
        for (int i = 0; i < d.lines().size(); i++) {
            Line l = d.lines().get(i);
            BigDecimal amount = money(l.amount());
            BigDecimal quantity = quantity(l.quantity());
            BigDecimal price = amount.divide(quantity, 2, RoundingMode.HALF_UP);
            BigDecimal lineTax = taxes.get(i);
            items.add(new Vsdc.SaleItem(i + 1, l.itemCode(), l.itemClass(), EbmCodes.cut(l.name(), 200), null, EbmCodes.PACKAGING,
                    BigDecimal.ONE.setScale(2), l.quantityUnit(), quantity, price, amount, ZERO, ZERO, null, null, null, null,
                    l.taxCode(), amount, lineTax, amount));
            taxable.merge(l.taxCode(), amount, BigDecimal::add);
            tax.merge(l.taxCode(), lineTax, BigDecimal::add);
            rates.put(l.taxCode(), money(l.rate()));
            totalTaxable = totalTaxable.add(amount);
            totalTax = totalTax.add(lineTax);
        }
        String confirmed = EbmCodes.dateTime(d.confirmedAt());
        boolean refund = d.isRefund();
        Vsdc.SaleReceipt receipt = new Vsdc.SaleReceipt(d.buyerTin(), EbmCodes.cut(d.buyerPhone(), 20), d.reportNo(),
                EbmCodes.cut(d.tradeName(), 20), EbmCodes.cut(d.address(), 200), null, null, "N");
        String user = EbmCodes.cut(d.user(), 20);
        return new Vsdc.SaleRequest(tin, branchId, d.invcNo(), refund ? d.orgInvcNo() : 0, d.buyerTin(), d.purchaseCode(),
                EbmCodes.cut(d.buyerName(), 60), EbmCodes.NORMAL, d.receiptType(), d.paymentType(),
                refund ? EbmCodes.REFUNDED : EbmCodes.APPROVED, confirmed, EbmCodes.date(d.salesDate()),
                refund ? null : confirmed, null, null, refund ? confirmed : null, refund ? d.refundReason() : null, items.size(),
                taxable.get("A"), taxable.get("B"), taxable.get("C"), taxable.get("D"),
                rates.get("A"), rates.get("B"), rates.get("C"), rates.get("D"),
                tax.get("A"), tax.get("B"), tax.get("C"), tax.get("D"),
                totalTaxable, totalTax, totalTaxable, "N", null, user, user, user, user, receipt, items);
    }

    public static Vsdc.ItemRequest item(String tin, String branchId, Item i, String user) {
        String u = EbmCodes.cut(user, 20);
        return new Vsdc.ItemRequest(tin, branchId, i.itemCode(), i.itemClass(), i.itemType(), EbmCodes.cut(i.name(), 200), null,
                i.origin(), EbmCodes.PACKAGING, i.quantityUnit(), i.taxCode(), null, null, money(i.defaultPrice()),
                null, null, null, null, null, null, null, "N", "Y", u, u, u, u);
    }

    /** The quantity sent: 2 decimals, at least 0.01 (a tiny piece still counts). */
    static BigDecimal quantity(BigDecimal q) {
        BigDecimal rounded = q.setScale(2, RoundingMode.HALF_UP);
        return rounded.compareTo(SMALLEST_QUANTITY) < 0 ? SMALLEST_QUANTITY : rounded;
    }

    /** Each line's VAT: its letter's VAT on the document (Vat.totals) split over the letter's lines by amount. */
    static List<BigDecimal> lineTaxes(List<Line> lines) {
        Vat.Totals totals = Vat.totals(lines.stream().map(l -> new Vat.Line(l.taxCode(), l.rate(), money(l.amount()))).toList());
        BigDecimal[] taxes = new BigDecimal[lines.size()];
        for (Vat.Group g : totals.groups()) {
            List<Integer> of = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).taxCode().equals(g.taxCode())) {
                    of.add(i);
                }
            }
            List<BigDecimal> parts = g.vat().signum() == 0
                    ? Collections.nCopies(of.size(), ZERO)
                    : LandedCost.split(g.vat(), of.stream().map(i -> money(lines.get(i).amount())).toList(), 2);
            for (int k = 0; k < of.size(); k++) {
                taxes[of.get(k)] = parts.get(k);
            }
        }
        return Arrays.asList(taxes);
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
