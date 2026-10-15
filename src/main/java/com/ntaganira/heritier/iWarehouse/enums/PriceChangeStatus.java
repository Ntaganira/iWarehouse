package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : PriceChangeStatus.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : Life of a price change (SDP 5): waiting, then approved (the prices apply), rejected, or withdrawn by whoever asked.
 * </pre>
 */
public enum PriceChangeStatus {

    PENDING,
    APPROVED,
    REJECTED,
    WITHDRAWN
}
