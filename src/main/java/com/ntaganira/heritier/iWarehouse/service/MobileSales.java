package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.enums.PaymentMethod;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : MobileSales.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : The rules a sale from a vehicle is checked with (MPOS-04, MPOS-05), the same on the phone and the server.
 *               Prices come from the trip's frozen lists: the customer's list, then the default list (as
 *               PriceListService.priceFor); a line is the price per m² x the piece's chargeable area, VAT added when the
 *               list excludes it, whole RWF (Vat.lineAmount); a lower price stays within the driver's discount limit
 *               (Discounts); cash and mobile money only, mobile money with its reference, adding up to the total. Pure,
 *               tested; the PWA's app.js does the same sums with integers.
 * </pre>
 */
public final class MobileSales {

    private MobileSales() {
    }

    /** A frozen list's terms: VAT included or added, the smallest area a piece is charged. */
    public record ListTerms(UUID listId, boolean pricesIncludeVat, BigDecimal minChargeableM2) {
    }

    /** A glass's price for a customer and the list it comes from. */
    public record Resolved(ListTerms list, BigDecimal pricePerM2) {
    }

    /** The trip's frozen prices: per list, per glass. */
    public record Snapshot(UUID defaultListId, Map<UUID, ListTerms> lists, Map<UUID, Map<UUID, BigDecimal>> prices) {

        /** The customer's list first (when the trip has it), then the default list; empty when neither prices the glass. */
        public Optional<Resolved> resolve(UUID customerListId, UUID productId) {
            UUID own = customerListId != null && lists.containsKey(customerListId) ? customerListId : defaultListId;
            BigDecimal ownPrice = prices.getOrDefault(own, Map.of()).get(productId);
            if (ownPrice != null) {
                return Optional.of(new Resolved(lists.get(own), ownPrice));
            }
            BigDecimal fallback = own.equals(defaultListId) ? null : prices.getOrDefault(defaultListId, Map.of()).get(productId);
            return fallback == null ? Optional.empty() : Optional.of(new Resolved(lists.get(defaultListId), fallback));
        }
    }

    /** A unit's amount at a price: price x chargeable area, VAT added when the list excludes it, rounded to the currency. */
    public static BigDecimal lineAmount(BigDecimal pricePerM2, int widthMm, int heightMm, ListTerms list, BigDecimal vatRate, int decimals) {
        BigDecimal area = Pricing.chargeableArea(widthMm, heightMm, list.minChargeableM2());
        return Vat.lineAmount(pricePerM2, area, 1, list.pricesIncludeVat(), vatRate, decimals);
    }

    /** A price the driver may charge: the list price or more, or less within their discount limit (MPOS-04). */
    public static boolean withinLimit(BigDecimal listPrice, BigDecimal price, BigDecimal limitPercent) {
        return price.signum() >= 0 && !Discounts.needsApproval(Discounts.percent(listPrice, price), limitPercent);
    }

    /** A payment as the phone took it. */
    public record Payment(PaymentMethod method, BigDecimal amount, String reference) {
    }

    /** What is wrong with the payments of a sale, or empty: cash and mobile money only, each above 0, references, the total. */
    public static Optional<String> paymentProblem(Collection<Payment> payments, BigDecimal total) {
        if (payments.isEmpty()) {
            return Optional.of("no payment");
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (Payment p : payments) {
            if (p.method() != PaymentMethod.CASH && p.method() != PaymentMethod.MOBILE_MONEY) {
                return Optional.of(p.method() + " is not taken on the road");
            }
            if (p.amount() == null || p.amount().signum() <= 0) {
                return Optional.of("an amount of 0");
            }
            if (p.method() == PaymentMethod.MOBILE_MONEY && (p.reference() == null || p.reference().isBlank())) {
                return Optional.of("mobile money without its reference");
            }
            sum = sum.add(p.amount());
        }
        if (sum.compareTo(total) != 0) {
            return Optional.of("paid " + sum.toPlainString() + ", total " + total.toPlainString());
        }
        return Optional.empty();
    }

    /** Line amounts the phone charged against the server's, in order: the first that differs, or -1. */
    public static int firstDifference(List<BigDecimal> phone, List<BigDecimal> server) {
        for (int i = 0; i < Math.min(phone.size(), server.size()); i++) {
            if (phone.get(i) == null || phone.get(i).compareTo(server.get(i)) != 0) {
                return i;
            }
        }
        return phone.size() == server.size() ? -1 : Math.min(phone.size(), server.size());
    }
}
