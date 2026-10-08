package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : ClaimStatus.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : The shipment's claim for sheets broken on arrival (PRC-06): none yet, open (sent to the
 *               supplier or insurer), settled with the amount received, or rejected with the reason.
 * </pre>
 */
public enum ClaimStatus {

    NONE,
    OPEN,
    SETTLED,
    REJECTED
}
