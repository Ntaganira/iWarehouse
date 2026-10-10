package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : NotificationKind.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : What a notification tells (RPT-06): glass below its reorder level, a request waiting for the reader's
 *               approval, a decision on the reader's own request, or a message from the system. The end of day, licence
 *               and insurance, float and EBM alerts add their kind with their milestone (and chk_notifications_kind).
 * </pre>
 */
public enum NotificationKind {

    LOW_STOCK,
    APPROVAL,
    DECISION,
    SYSTEM,
    /** EBM refused a receipt, or receipts wait too long for their signature (TAX-03). */
    EBM
}
