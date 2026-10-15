package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : DeliveryNoteStatus.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : A delivery note (F-04): prepared by Dispatch, released by the warehouse (the goods leave), or cancelled
 *               before release.
 * </pre>
 */
public enum DeliveryNoteStatus {
    DRAFT,
    RELEASED,
    CANCELLED;
}
