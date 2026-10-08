package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.enums.GlassType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : GlassProducts.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : Pure rules for glass products (MD-01, INV-02): thickness labels, colour/finish
 *               clean-up, suggested codes and weight per m². No Spring here, so they are unit-tested;
 *               static/js/product-form.js mirrors suggestCode for the live preview.
 * </pre>
 */
public final class GlassProducts {

    /** Longest colour/finish part of a suggested code, so codes stay within 20 characters. */
    static final int VARIANT_CODE_LENGTH = 8;

    private GlassProducts() {
    }

    /** 6.00 -> "6", 6.38 -> "6.38", 10.00 -> "10". */
    public static String thicknessLabel(BigDecimal mm) {
        return mm == null ? "" : mm.stripTrailingZeros().toPlainString();
    }

    /** Trims and collapses spaces, capitalises the first letter; blank becomes null ("bronze " -> "Bronze"). */
    public static String normalizeVariant(String variant) {
        if (variant == null || variant.isBlank()) {
            return null;
        }
        String v = variant.trim().replaceAll("\\s+", " ");
        return v.substring(0, 1).toUpperCase(Locale.ROOT) + v.substring(1);
    }

    /**
     * Type prefix, colour/finish in capitals without spaces or signs (at most 8 characters), thickness:
     * CLEAR 6 -> CLR-6, TINTED "Bronze" 5 -> TNT-BRONZE-5, LAMINATED 6.38 -> LAM-6.38.
     */
    public static String suggestCode(GlassType type, String variant, BigDecimal thicknessMm) {
        StringBuilder code = new StringBuilder(type.getCodePrefix());
        if (variant != null) {
            String part = variant.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
            if (!part.isEmpty()) {
                code.append('-').append(part, 0, Math.min(part.length(), VARIANT_CODE_LENGTH));
            }
        }
        if (thicknessMm != null) {
            code.append('-').append(thicknessLabel(thicknessMm));
        }
        return code.toString();
    }

    /** kg per m² = thickness (mm) x density (kg per m² per mm, setting GLASS_DENSITY), to 2 decimals (INV-02). */
    public static BigDecimal weightPerM2(BigDecimal thicknessMm, BigDecimal density) {
        return thicknessMm.multiply(density).setScale(2, RoundingMode.HALF_UP);
    }

    /** kg of one piece: area (m²) x kg per m², to 2 decimals (INV-02). */
    public static BigDecimal weightKg(BigDecimal areaM2, BigDecimal weightPerM2) {
        return areaM2.multiply(weightPerM2).setScale(2, RoundingMode.HALF_UP);
    }
}
