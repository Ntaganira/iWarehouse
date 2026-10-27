package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : SalaryPayMethod.java
 * - Date      : 2026. 10. 11.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : How an employee is paid, and the account their net pay leaves from when the salaries are paid. Bank
 *               and mobile money need the employee's account number.
 * </pre>
 */
public enum SalaryPayMethod {

    BANK(AccountKey.BANK),
    MOBILE_MONEY(AccountKey.MOBILE_MONEY),
    CASH(AccountKey.CASH_VAULT);

    private final AccountKey account;

    SalaryPayMethod(AccountKey account) {
        this.account = account;
    }

    public AccountKey account() {
        return account;
    }

    public boolean needsAccount() {
        return this != CASH;
    }
}
