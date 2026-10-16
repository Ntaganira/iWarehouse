package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : PettyCashSource.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : Where a petty cash top-up comes from, or its returned cash goes: the main vault or the bank.
 * </pre>
 */
public enum PettyCashSource {
    CASH_VAULT(AccountKey.CASH_VAULT),
    BANK(AccountKey.BANK);

    private final AccountKey account;

    PettyCashSource(AccountKey account) {
        this.account = account;
    }

    public AccountKey account() {
        return account;
    }

    /** A bank movement names its reference. */
    public boolean needsReference() {
        return this == BANK;
    }
}
