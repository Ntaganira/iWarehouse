package com.ntaganira.heritier.iWarehouse.service;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : WhatsAppNumbers.java
 * - Date      : 2026. 10. 31.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The number sent to a WhatsApp gateway, in international form without the plus
 *               (250788533669). Accepts the stored +250 number, a local 078... number or a
 *               number that already carries the country code. Pure.
 * </pre>
 */
public final class WhatsAppNumbers {

    private WhatsAppNumbers() {
    }

    public static String international(String raw) {
        if (raw == null) {
            return null;
        }
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.startsWith("00")) {
            digits = digits.substring(2);
        }
        if (digits.startsWith("0") && digits.length() == 10) {
            return "250" + digits.substring(1);
        }
        if (digits.startsWith("250") && digits.length() == 12) {
            return digits;
        }
        if (digits.length() == 9 && digits.charAt(0) == '7') {
            return "250" + digits;
        }
        return digits.length() >= 9 ? digits : null;
    }
}
