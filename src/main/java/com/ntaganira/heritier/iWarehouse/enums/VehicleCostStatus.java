package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : VehicleCostStatus.java
 * - Date      : 2026. 10. 12.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Life of a vehicle cost. Within the approval limit it posts when recorded; above it, it waits for another
 *               person, who approves it (it posts) or rejects it with a reason; whoever recorded it can withdraw it.
 *               A posted cost is never edited: it is reversed by the opposite journal, with a reason.
 * </pre>
 */
public enum VehicleCostStatus {

    PENDING_APPROVAL,
    POSTED,
    REJECTED,
    CANCELLED,
    REVERSED
}
