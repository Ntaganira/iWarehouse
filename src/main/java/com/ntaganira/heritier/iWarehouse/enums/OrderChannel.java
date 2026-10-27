package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : OrderChannel.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : How an order came in (F-01 A, F-12 B, BP 6): at the counter of Head Office, a branch or point of sale
 *               (Nyabugogo), through an agent, by telephone or WhatsApp, from the field, for export, or other.
 * </pre>
 */
public enum OrderChannel {
    COUNTER,
    BRANCH,
    AGENT,
    TELEPHONE,
    WHATSAPP,
    FIELD,
    EXPORT,
    OTHER;
}
