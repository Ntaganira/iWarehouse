package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : AuthorityDecision.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : What the Delegation of Authority sets limits for (Board Paper Annex E, Cost Management Policy 05): each
 *               decision that waits for another person's approval. Its amount is RWF, but for a discount, which is the
 *               percentage off the list price. A new kind of approval = a constant here, its rows in authority_limits and
 *               chk_authority_limits_decision, and its texts (authority.decision.*).
 * </pre>
 */
public enum AuthorityDecision {

    /** A price cut beyond the seller's own limit (SDP 8, F-03): the % off the list price. */
    DISCOUNT(true),
    /** A price below what the glass cost (SDP 8): the sale's amount at stake. */
    BELOW_COST(false),
    /** Credit beyond what a customer has left, or for a customer with overdue invoices, or longer terms (F-03). */
    CREDIT_EXCEPTION(false),
    /** A credit note to a customer (F-14). */
    CREDIT_NOTE(false),
    /** A receivable written off (F-14). */
    RECEIVABLE_WRITE_OFF(false),
    /** A stock write-off or adjustment (F-07, F-08, INV-07). */
    STOCK_ADJUSTMENT(false),
    /** A purchase order to a supplier, in RWF at today's rate (CMP 06). */
    PURCHASE_ORDER(false),
    /** A single payment to a supplier, in RWF (CMP 07). */
    SUPPLIER_PAYMENT(false),
    /** A manual journal (F-15). */
    MANUAL_JOURNAL(false),
    /** Operating expenditure on a vehicle. */
    VEHICLE_COST(false),
    /** A customer's credit limit, by the limit asked (F-02 D). */
    CREDIT_LIMIT(false),
    /** Prices of a price list changed (SDP 5): no amount, so the lowest office allowed decides. */
    PRICE_CHANGE(false),
    /** Operating expenditure on an expense bill (CMP 05): Finance up to 500K, the MD up to 2M, the Board above. */
    EXPENSE_OPEX(false),
    /** Capital expenditure (CMP 05): the MD up to 5M, the Board above. */
    EXPENSE_CAPEX(false),
    /** Spending outside the budget (CMP 05): the MD up to 500K, the Board above. */
    EXPENSE_UNBUDGETED(false),
    /** An emergency (CMP 05): the MD approves, the Board ratifies. */
    EXPENSE_EMERGENCY(false),
    /** A staff advance (CMP 08): approved by the Managing Director. */
    STAFF_ADVANCE(false),
    /** The year's budget (CMP 04): the Board approves it. */
    BUDGET(false),
    /** A line of the approved budget revised (CMP 04): by the share it changes, the MD up to 15 %, the Board above. */
    BUDGET_REVISION(true);

    private final boolean percent;

    AuthorityDecision(boolean percent) {
        this.percent = percent;
    }

    /** Its limits are percentages, not RWF. */
    public boolean isPercent() {
        return percent;
    }
}
