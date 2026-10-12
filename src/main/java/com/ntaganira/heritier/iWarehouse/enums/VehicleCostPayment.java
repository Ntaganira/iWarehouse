package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : VehicleCostPayment.java
 * - Date      : 2026. 10. 12.
 * - User      : Hntaganira
 * - Desc      : How a vehicle cost was paid, and the account it leaves: the main cash vault, the bank, mobile money, owed to
 *               the garage (its supplier account, paid later as any supplier) or paid by the driver from a trip's
 *               cash (their float, so the float to hand back is that much less).
 * </pre>
 */
public enum VehicleCostPayment {

    CASH_VAULT(AccountKey.CASH_VAULT),
    BANK(AccountKey.BANK),
    MOBILE_MONEY(AccountKey.MOBILE_MONEY),
    CREDIT(AccountKey.PAYABLE),
    DRIVER_FLOAT(AccountKey.DRIVER_FLOAT);

    private final AccountKey account;

    VehicleCostPayment(AccountKey account) {
        this.account = account;
    }

    /** The account the money leaves (Cr). */
    public AccountKey account() {
        return account;
    }

    /** Paid with a reference that must be given: a bank transfer or a mobile-money transaction. */
    public boolean needsReference() {
        return this == BANK || this == MOBILE_MONEY;
    }
}
