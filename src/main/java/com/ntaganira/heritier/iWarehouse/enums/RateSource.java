package com.ntaganira.heritier.iWarehouse.enums;

/**
 * Where an exchange rate comes from (ACC-02). Landed cost uses CUSTOMS for duty and the payment
 * rate (BANK) for the supplier invoice; other documents use the default source from Settings.
 */
public enum RateSource {
    /** National Bank of Rwanda reference rate. */
    BNR,
    /** RRA rate used to value imports for duty. */
    CUSTOMS,
    /** Rate of an actual bank deal. */
    BANK,
    /** Entered by hand from another source. */
    MANUAL
}
