package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : AuthorityLevel.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The offices of the Delegation of Authority (Board Paper Annex E), lowest first: a Director, the Chief
 *               Operating Officer, the Managing Director, the Board. A user holds the highest office among their rights
 *               (PERM_AUTHORITY_x). A higher office may decide whatever a lower one may; the Board decides what no office
 *               below it may, recorded by a holder of its authority with the resolution.
 * </pre>
 */
public enum AuthorityLevel {

    DIRECTOR,
    COO,
    MD,
    BOARD;

    /** The right a user holds this office by. */
    public String permission() {
        return "PERM_AUTHORITY_" + name();
    }

    public boolean atLeast(AuthorityLevel other) {
        return compareTo(other) >= 0;
    }
}
