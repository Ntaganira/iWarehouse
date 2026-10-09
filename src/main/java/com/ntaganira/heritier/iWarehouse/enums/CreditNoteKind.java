package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : CreditNoteKind.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : Why a credit note is issued: glass the customer brought back (RETURN, POS-09), or pieces of an order
 *               given up before they were handed over (CANCEL: not cut yet, or cut and released to stock).
 * </pre>
 */
public enum CreditNoteKind {

    RETURN,
    CANCEL
}
