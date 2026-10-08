package com.ntaganira.heritier.iWarehouse.service;

import java.util.Locale;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : PartyRules.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Pure rules shared by customers and suppliers (MD-04, MD-05): tidying typed text and
 *               checking a Rwandan TIN (9 digits, as RRA EBM expects on invoices, TAX-04).
 * </pre>
 */
public final class PartyRules {

    public static final String RWANDA = "RW";

    private PartyRules() {
    }

    /** Trimmed text, or null when blank. */
    public static String clean(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }

    /** "100 200 300" or "100-200-300" -> "100200300"; blank -> null. */
    public static String normalizeTin(String tin) {
        String t = clean(tin);
        return t == null ? null : t.replaceAll("[\\s.\\-]", "").toUpperCase(Locale.ROOT);
    }

    public static boolean isRwandaTin(String tin) {
        return tin != null && tin.matches("\\d{9}");
    }
}
