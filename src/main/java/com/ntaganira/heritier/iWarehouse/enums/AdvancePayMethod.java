package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : AdvancePayMethod.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : How a staff advance is paid, or its rest settled when it is retired: the vault, the bank, mobile money
 *               or a petty cash fund.
 * </pre>
 */
public enum AdvancePayMethod {
    CASH_VAULT(AccountKey.CASH_VAULT),
    BANK(AccountKey.BANK),
    MOBILE_MONEY(AccountKey.MOBILE_MONEY),
    PETTY_CASH(AccountKey.PETTY_CASH);

    private final AccountKey account;

    AdvancePayMethod(AccountKey account) {
        this.account = account;
    }

    public AccountKey account() {
        return account;
    }

    public boolean needsReference() {
        return this == BANK || this == MOBILE_MONEY;
    }
}
