package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : CustomerType.java
 * - Date      : 2026. 10. 08.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Kinds of customer (MD-04). Walk-in customers pay at the counter and never get credit;
 *               account customers and contractors may have a credit limit and payment terms (POS-05).
 * </pre>
 */
public enum CustomerType {

    WALK_IN,
    ACCOUNT,
    CONTRACTOR;

    public boolean isCreditAllowed() {
        return this != WALK_IN;
    }
}
