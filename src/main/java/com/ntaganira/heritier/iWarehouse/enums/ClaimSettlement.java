package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : ClaimSettlement.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : How the money of a settled claim came in (PRC-06): it decides the account debited. A
 *               credit from the supplier lowers what is owed to them.
 * </pre>
 */
public enum ClaimSettlement {

    BANK(AccountKey.BANK),
    CASH(AccountKey.CASH),
    MOBILE_MONEY(AccountKey.MOBILE_MONEY),
    PAYABLE(AccountKey.PAYABLE);

    private final AccountKey account;

    ClaimSettlement(AccountKey account) {
        this.account = account;
    }

    public AccountKey account() {
        return account;
    }
}
