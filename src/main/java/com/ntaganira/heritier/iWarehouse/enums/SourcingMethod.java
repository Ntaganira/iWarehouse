package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : SourcingMethod.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : How a purchase is sourced by its value (CMP 06): one quote, three written quotes, a formal
 *               request for quotation, a tender.
 * </pre>
 */
public enum SourcingMethod {
    SINGLE_QUOTE,
    THREE_QUOTES,
    RFQ,
    TENDER;

    /** Quotes it needs: one for a small purchase, three otherwise. */
    public int quotesNeeded() {
        return this == SINGLE_QUOTE ? 1 : 3;
    }

    /** It is run on a reference (the RFQ or the tender) kept with its papers. */
    public boolean needsReference() {
        return this == RFQ || this == TENDER;
    }
}
