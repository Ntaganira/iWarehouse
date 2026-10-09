package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : ManualJournalStatus.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : Life of a manual journal (ACC-05). Asked for, it waits for another person, who approves it (it posts)
 *               or rejects it with a reason; the requester can withdraw it (cancelled, with a reason). A posted
 *               journal is never deleted: it is reversed by a new journal, with a reason.
 * </pre>
 */
public enum ManualJournalStatus {

    PENDING_APPROVAL,
    POSTED,
    REJECTED,
    CANCELLED,
    REVERSED
}
