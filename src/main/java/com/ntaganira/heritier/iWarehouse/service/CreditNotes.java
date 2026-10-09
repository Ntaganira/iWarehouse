package com.ntaganira.heritier.iWarehouse.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : CreditNotes.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : What a return credits (POS-09). An invoice line is credited for the pieces brought back as the
 *               difference of its amount's running share (whole RWF, half up), so the parts of a line returned on
 *               several credit notes always add up to the line, never more. The credit first reduces the invoice's
 *               balance due; the rest is refunded. Pure, unit-tested.
 * </pre>
 */
public final class CreditNotes {

    private CreditNotes() {
    }

    /** The credit for {@code now} more of a line's {@code quantity} pieces when {@code before} came back already. */
    public static BigDecimal share(BigDecimal lineAmount, int quantity, int before, int now) {
        if (quantity < 1 || before < 0 || now < 1 || before + now > quantity) {
            throw new IllegalArgumentException("pieces " + before + " + " + now + " of " + quantity);
        }
        return runningShare(lineAmount, quantity, before + now).subtract(runningShare(lineAmount, quantity, before));
    }

    private static BigDecimal runningShare(BigDecimal lineAmount, int quantity, int pieces) {
        return lineAmount.multiply(BigDecimal.valueOf(pieces)).divide(BigDecimal.valueOf(quantity), 0, RoundingMode.HALF_UP)
                .setScale(2);
    }

    /** The credit split: what reduces the invoice's balance due, and what is refunded. */
    public record Refund(BigDecimal balanceReduced, BigDecimal refunded) {
    }

    public static Refund refund(BigDecimal total, BigDecimal balanceDue) {
        BigDecimal reduced = balanceDue == null ? BigDecimal.ZERO : balanceDue.max(BigDecimal.ZERO).min(total);
        return new Refund(reduced.setScale(2), total.subtract(reduced).setScale(2));
    }
}
