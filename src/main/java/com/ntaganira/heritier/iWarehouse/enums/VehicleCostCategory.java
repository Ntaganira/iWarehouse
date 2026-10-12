package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : VehicleCostCategory.java
 * - Date      : 2026. 10. 12.
 * - User      : Hntaganira
 * - Desc      : What a vehicle cost was for, and the expense account it goes to: fuel; services and oil changes, repairs,
 *               spare parts (bought when needed) and tyres; washing, parking and tolls, and the rest.
 * </pre>
 */
public enum VehicleCostCategory {

    FUEL(AccountKey.VEHICLE_FUEL),
    SERVICE(AccountKey.VEHICLE_MAINTENANCE),
    REPAIR(AccountKey.VEHICLE_MAINTENANCE),
    PARTS(AccountKey.VEHICLE_MAINTENANCE),
    TYRES(AccountKey.VEHICLE_MAINTENANCE),
    WASHING(AccountKey.VEHICLE_OTHER),
    PARKING(AccountKey.VEHICLE_OTHER),
    OTHER(AccountKey.VEHICLE_OTHER);

    private final AccountKey account;

    VehicleCostCategory(AccountKey account) {
        this.account = account;
    }

    /** The expense account its costs are posted to. */
    public AccountKey account() {
        return account;
    }

    /** Work a service plan may be done by (a service, a repair, parts or tyres), not fuel or parking. */
    public boolean canDoService() {
        return account == AccountKey.VEHICLE_MAINTENANCE;
    }

    /** The report's three groups: fuel, maintenance, other. */
    public String group() {
        return account.name().substring("VEHICLE_".length());
    }
}
