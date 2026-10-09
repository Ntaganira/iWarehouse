package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : AccountType.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
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
