package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.enums.AuthorityDecision;
import com.ntaganira.heritier.iWarehouse.enums.AuthorityLevel;
import com.ntaganira.heritier.iWarehouse.enums.ExpenseNature;
import com.ntaganira.heritier.iWarehouse.enums.SourcingMethod;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : Expenditure.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : The rules of the Cost Management & Expenditure Control Policy that need no database:
 *               - how a purchase is sourced by its value (CMP 06): one quote, three written quotes, a formal RFQ, a tender;
 *               - which row of the expenditure matrix approves a bill (CMP 05): an emergency, else unbudgeted, else capital,
 *                 else operating expenditure;
 *               - the most VAT a line may carry (18 % of its amount before VAT, rounded up to the franc);
 *               - whether an order or a bill stays within its requisition (the chosen quote plus the Settings' tolerance);
 *               - the cheapest quote, which a choice of another must explain.
 *               Pure and tested.
 * </pre>
 */
public final class Expenditure {

    private Expenditure() {
    }

    public static final BigDecimal VAT_RATE = new BigDecimal("18");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** The policy's value tiers (Settings): three quotes, an RFQ and a tender from these amounts. */
    public record Tiers(BigDecimal threeFrom, BigDecimal rfqFrom, BigDecimal tenderFrom) {
    }

    /** How a purchase of this value is sourced (CMP 06). */
    public static SourcingMethod sourcing(BigDecimal amount, Tiers tiers) {
        if (amount.compareTo(tiers.tenderFrom()) >= 0) {
            return SourcingMethod.TENDER;
        }
        if (amount.compareTo(tiers.rfqFrom()) >= 0) {
            return SourcingMethod.RFQ;
        }
        if (amount.compareTo(tiers.threeFrom()) >= 0) {
            return SourcingMethod.THREE_QUOTES;
        }
        return SourcingMethod.SINGLE_QUOTE;
    }

    /** The row of the expenditure matrix (CMP 05) that approves a bill. */
    public static AuthorityDecision decision(boolean emergency, boolean budgeted, ExpenseNature nature) {
        if (emergency) {
            return AuthorityDecision.EXPENSE_EMERGENCY;
        }
        if (!budgeted) {
            return AuthorityDecision.EXPENSE_UNBUDGETED;
        }
        return nature == ExpenseNature.CAPEX ? AuthorityDecision.EXPENSE_CAPEX : AuthorityDecision.EXPENSE_OPEX;
    }

    /** The most VAT a line of this amount before VAT may carry: 18 %, rounded up to the franc. */
    public static BigDecimal maxVat(BigDecimal net) {
        return net.multiply(VAT_RATE).divide(HUNDRED, 0, RoundingMode.CEILING).setScale(2);
    }

    /** An order or a bill of {@code amount} stays within a requisition whose chosen quote is {@code reference}. */
    public static boolean withinRequisition(BigDecimal amount, BigDecimal reference, BigDecimal tolerancePercent) {
        BigDecimal ceiling = reference.multiply(HUNDRED.add(tolerancePercent)).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        return amount.compareTo(ceiling) <= 0;
    }

    /** The lowest of some amounts (the cheapest quote), if any. */
    public static <T> Optional<T> cheapest(Collection<T> quotes, java.util.function.Function<T, BigDecimal> amount) {
        return quotes.stream().filter(Objects::nonNull).min(Comparator.comparing(amount));
    }

    /**
     * Whether the signed-in user may sign as the head of department (CMP 05, 06): when the departments name no manager, any holder
     * of the right; otherwise one of their managers, or an office from the COO up (who stand above the heads of department).
     */
    public static boolean mayActAsHead(Collection<Long> managers, Long me, AuthorityLevel office) {
        if (managers.isEmpty()) {
            return true;
        }
        if (me != null && managers.contains(me)) {
            return true;
        }
        return office != null && office.compareTo(AuthorityLevel.COO) >= 0;
    }

    /** Whether a review by Finance is due for a bill of this amount (CMP 05: from the Settings' amount; 0 = never). */
    public static boolean needsReview(BigDecimal amount, BigDecimal from) {
        return from != null && from.signum() > 0 && amount.compareTo(from) >= 0;
    }
}
