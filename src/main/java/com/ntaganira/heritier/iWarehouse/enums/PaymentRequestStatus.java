package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : PaymentRequestStatus.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Life of a supplier payment prepared (CMP 07): authorised once, or twice above the Settings amount
 *               (AUTHORISED: the first of two), then paid; or rejected by an authoriser, or cancelled by whoever prepared it.
 * </pre>
 */
public enum PaymentRequestStatus {

    PREPARED,
    AUTHORISED,
    PAID,
    REJECTED,
    CANCELLED;

    public boolean isWaiting() {
        return this == PREPARED || this == AUTHORISED;
    }
}
