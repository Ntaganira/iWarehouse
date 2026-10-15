package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : NotificationKind.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : What a notification tells (RPT-06): glass below its reorder level, a request waiting for the reader's
 *               approval, a decision on the reader's own request, or a message from the system. The end of day and
 *               float alerts add their kind with their milestone (and chk_notifications_kind).
 * </pre>
 */
public enum NotificationKind {

    LOW_STOCK,
    APPROVAL,
    DECISION,
    SYSTEM,
    /** EBM refused a receipt, or receipts wait too long for their signature (TAX-03). */
    EBM,
    /** A driving licence, a vehicle's insurance or inspection about to expire, or expired (FLT-04). */
    FLEET,
    /** A mobile sale the server could not take as it was made, waiting for the supervisor (SYNC-05). */
    SYNC,
    /** The end of the day: a trip back with units missing or unexpected, a float not cleared in time (FLT-10, RPT-06). */
    EOD,
    /** A customer's credit limit due for its review: yearly, or an invoice long overdue (F-02 C). */
    CREDIT,
    /** A transfer between sites waiting, or glass that did not arrive (IMP 11.2). */
    TRANSFER
}
