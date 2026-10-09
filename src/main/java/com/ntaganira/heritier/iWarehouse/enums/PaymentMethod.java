package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : PaymentMethod.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : How a sale is paid (POS-04), split over several if needed, and the account each one debits
 *               (SRS 4.9.1). Mobile money, card and bank transfer carry their reference; customer credit is
 *               a receivable within the customer's limit (POS-05).
 * </pre>
 */
public enum PaymentMethod {

    CASH(AccountKey.CASH, false),
    MOBILE_MONEY(AccountKey.MOBILE_MONEY, true),
    CARD(AccountKey.BANK, true),
    BANK_TRANSFER(AccountKey.BANK, true),
    CREDIT(AccountKey.RECEIVABLE, false);

    private final AccountKey account;
    private final boolean needsReference;

    PaymentMethod(AccountKey account, boolean needsReference) {
        this.account = account;
        this.needsReference = needsReference;
    }

    public AccountKey account() {
        return account;
    }

    public boolean needsReference() {
        return needsReference;
    }
}
