package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : QuotationStatus.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : Life of a quotation (POS-03): a draft is edited freely; sent, it is fixed and printed for the
 *               customer; rung up at a till and paid, it is converted (its invoice kept); cancelled with a reason.
 *               A sent quotation past its validity date is expired (shown, not stored): it can no longer be rung up.
 * </pre>
 */
public enum QuotationStatus {

    DRAFT,
    SENT,
    CONVERTED,
    CANCELLED
}
