package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.enums.PaymentMethod;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : SalePayments.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : Splitting a sale's total over the ways it is paid (POS-04). Mobile money, card, bank transfer
 *               and customer credit settle what they say (with a reference where one is needed) and never more
 *               than the total; cash settles the rest: what the customer hands over must cover it, and the
 *               difference is the change. An order may be paid by a deposit (POS-08): at least the minimum, the
 *               rest is the balance due on collection. Pure, unit-tested; the customer's credit limit is checked
 *               by the caller.
 * </pre>
 */
public final class SalePayments {

    private SalePayments() {
    }

    /** What the cashier entered: cash handed over, and the amount and reference of each other method. */
    public record Entered(BigDecimal cash, BigDecimal mobileMoney, String mobileMoneyRef, BigDecimal card, String cardRef,
                          BigDecimal bankTransfer, String bankRef, BigDecimal credit) {
    }

    /** One part of the payment: its method, the amount it settles and its reference. */
    public record Part(PaymentMethod method, BigDecimal amount, String reference) {
    }

    /**
     * The parts (cash first, as kept: handed over less change), the cash handed over, the change and the balance left
     * to pay (zero unless a deposit).
     */
    public record Split(List<Part> parts, BigDecimal cashTendered, BigDecimal change, BigDecimal balance) {

        public BigDecimal amountOf(PaymentMethod method) {
            return parts.stream().filter(p -> p.method() == method).map(Part::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    public static Split split(BigDecimal total, Entered e) {
        return split(total, e, null);
    }

    /**
     * Splits a payment. With a deposit minimum (POS-08) the customer may pay less than the total, at least that
     * minimum: the cash handed over is all kept up to the total, and what is left is the balance.
     */
    public static Split split(BigDecimal total, Entered e, BigDecimal depositMinimum) {
        BigDecimal cash = amount(e.cash(), "cash");
        List<Part> others = new ArrayList<>();
        other(others, PaymentMethod.MOBILE_MONEY, amount(e.mobileMoney(), "mobileMoney"), e.mobileMoneyRef(), "mobileMoneyRef");
        other(others, PaymentMethod.CARD, amount(e.card(), "card"), e.cardRef(), "cardRef");
        other(others, PaymentMethod.BANK_TRANSFER, amount(e.bankTransfer(), "bankTransfer"), e.bankRef(), "bankRef");
        other(others, PaymentMethod.CREDIT, amount(e.credit(), "credit"), null, null);

        BigDecimal paidOtherwise = others.stream().map(Part::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (paidOtherwise.compareTo(total) > 0) {
            throw BusinessException.of("sale.pay.tooMuch", paidOtherwise, total);
        }
        BigDecimal rest = total.subtract(paidOtherwise);
        BigDecimal cashKept;
        if (depositMinimum == null) {
            if (cash.compareTo(rest) < 0) {
                throw BusinessException.onField("cash", "sale.pay.short", rest.subtract(cash));
            }
            cashKept = rest;
        } else {
            cashKept = cash.min(rest);
            BigDecimal paid = paidOtherwise.add(cashKept);
            if (paid.compareTo(depositMinimum) < 0) {
                throw BusinessException.onField("cash", "sale.pay.deposit.short", depositMinimum, depositMinimum.subtract(paid));
            }
        }
        List<Part> parts = new ArrayList<>();
        if (cashKept.signum() > 0) {
            parts.add(new Part(PaymentMethod.CASH, cashKept, null));
        }
        parts.addAll(others);
        return new Split(parts, cash.signum() > 0 ? cash : null, cash.subtract(cashKept), rest.subtract(cashKept));
    }

    /**
     * The smallest deposit on an order (POS-08): the percentage of the total (whole RWF, rounded up), and never less
     * than the glass taken from stock at once (it leaves paid) nor than 1 RWF; at most the total.
     */
    public static BigDecimal depositMinimum(BigDecimal total, BigDecimal percent, BigDecimal takenNow) {
        BigDecimal share = total.multiply(percent).divide(new BigDecimal("100"), 0, RoundingMode.CEILING);
        return share.max(takenNow).max(BigDecimal.ONE).min(total);
    }

    private static void other(List<Part> parts, PaymentMethod method, BigDecimal amount, String reference, String refField) {
        if (amount.signum() == 0) {
            return;
        }
        String ref = StringUtils.hasText(reference) ? reference.trim() : null;
        if (method.needsReference() && ref == null) {
            throw BusinessException.onField(refField, "sale.pay.refRequired");
        }
        if (ref != null && ref.length() > 60) {
            throw BusinessException.onField(refField, "sale.pay.refSize");
        }
        parts.add(new Part(method, amount, ref));
    }

    private static BigDecimal amount(BigDecimal value, String field) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value.signum() < 0 || value.stripTrailingZeros().scale() > 2) {
            throw BusinessException.onField(field, "sale.pay.amountInvalid");
        }
        return value;
    }
}
