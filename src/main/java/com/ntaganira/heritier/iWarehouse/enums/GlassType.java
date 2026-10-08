package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : GlassType.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : Glass types a product is made of (MD-01). The prefix starts suggested product codes
 *               (CLR-6, LAM-6.38). Tempered glass shatters if cut: it is made to size, so cutting jobs
 *               must refuse it as a source (PRD-02).
 * </pre>
 */
public enum GlassType {

    CLEAR("CLR", true),
    TINTED("TNT", true),
    REFLECTIVE("RFL", true),
    FROSTED("FRS", true),
    TEMPERED("TMP", false),
    LAMINATED("LAM", true),
    MIRROR("MIR", true);

    private final String codePrefix;
    private final boolean cuttable;

    GlassType(String codePrefix, boolean cuttable) {
        this.codePrefix = codePrefix;
        this.cuttable = cuttable;
    }

    public String getCodePrefix() {
        return codePrefix;
    }

    public boolean isCuttable() {
        return cuttable;
    }
}
