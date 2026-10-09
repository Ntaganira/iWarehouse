package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : QuoteLineKind.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : What a quotation line offers (POS-03): whole sheets of a glass and size from stock (the till
 *               picks the units when it is rung up), a size to cut (POS-02), or processing on a size.
 * </pre>
 */
public enum QuoteLineKind {

    SHEET,
    CUSTOM_PIECE,
    SERVICE
}
