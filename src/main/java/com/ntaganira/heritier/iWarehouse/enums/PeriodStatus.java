package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : PeriodStatus.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : A closed month (ACC-10): CLOSED, nothing can be posted on it; REOPENED, it was reopened with a reason
 *               and takes postings again until it is closed again. A month never closed has no row.
 * </pre>
 */
public enum PeriodStatus {

    CLOSED,
    REOPENED
}
