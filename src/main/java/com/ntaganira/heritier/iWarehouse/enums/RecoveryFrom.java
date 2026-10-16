package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : RecoveryFrom.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : Who may pay a damage loss back (F-07 C): nobody, the customer, an employee, an insurer, the supplier or a driver.
 * </pre>
 */
public enum RecoveryFrom {

    NONE,
    CUSTOMER,
    EMPLOYEE,
    INSURER,
    SUPPLIER,
    DRIVER
}
