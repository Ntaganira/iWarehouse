package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : EbmReceiptStatus.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : An EBM receipt (TAX-02, TAX-03): queued until the VSDC signs it (retried while it cannot be reached),
 *               signed (never changes again), or failed when the VSDC refused it, waiting for a person to retry.
 * </pre>
 */
public enum EbmReceiptStatus {

    QUEUED,
    SIGNED,
    FAILED
}
