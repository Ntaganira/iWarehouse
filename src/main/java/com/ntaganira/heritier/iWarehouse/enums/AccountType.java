package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : AccountType.java
 * - Date      : 2026. 10. 09.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The five kinds of account (ACC-03). Assets and expenses carry a debit balance; liabilities,
 *               equity and revenue a credit balance. Fixed once an account has lines.
 * </pre>
 */
public enum AccountType {

    ASSET,
    LIABILITY,
    EQUITY,
    REVENUE,
    EXPENSE;

    /** Debits raise the balance (assets, expenses); credits raise the others. */
    public boolean isDebitNormal() {
        return this == ASSET || this == EXPENSE;
    }
}
