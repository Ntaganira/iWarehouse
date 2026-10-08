package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.enums.GlassType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** Thickness labels, colour/finish clean-up, suggested codes and weight per m² (MD-01, INV-02). */
class GlassProductsTest {

    @Test
    void thicknessLabelDropsTrailingZeros() {
        assertThat(GlassProducts.thicknessLabel(new BigDecimal("6.00"))).isEqualTo("6");
        assertThat(GlassProducts.thicknessLabel(new BigDecimal("10.00"))).isEqualTo("10");
        assertThat(GlassProducts.thicknessLabel(new BigDecimal("6.38"))).isEqualTo("6.38");
        assertThat(GlassProducts.thicknessLabel(new BigDecimal("6.50"))).isEqualTo("6.5");
    }

    @Test
    void variantIsTrimmedAndCapitalised() {
        assertThat(GlassProducts.normalizeVariant("  bronze ")).isEqualTo("Bronze");
        assertThat(GlassProducts.normalizeVariant("dark   grey")).isEqualTo("Dark grey");
        assertThat(GlassProducts.normalizeVariant("   ")).isNull();
        assertThat(GlassProducts.normalizeVariant(null)).isNull();
    }

    @Test
    void suggestedCodeIsPrefixColourAndThickness() {
        assertThat(GlassProducts.suggestCode(GlassType.CLEAR, null, new BigDecimal("6.00"))).isEqualTo("CLR-6");
        assertThat(GlassProducts.suggestCode(GlassType.TINTED, "Bronze", new BigDecimal("5"))).isEqualTo("TNT-BRONZE-5");
        assertThat(GlassProducts.suggestCode(GlassType.LAMINATED, null, new BigDecimal("6.38"))).isEqualTo("LAM-6.38");
    }

    @Test
    void suggestedCodeKeepsOnlyLettersAndDigitsOfTheColourAndStaysShort() {
        assertThat(GlassProducts.suggestCode(GlassType.CLEAR, "Low-iron", new BigDecimal("10"))).isEqualTo("CLR-LOWIRON-10");
        assertThat(GlassProducts.suggestCode(GlassType.FROSTED, "Acid-etched satin", new BigDecimal("12.38")))
                .isEqualTo("FRS-ACIDETCH-12.38")
                .hasSizeLessThanOrEqualTo(20);
        assertThat(GlassProducts.suggestCode(GlassType.MIRROR, "---", new BigDecimal("4"))).isEqualTo("MIR-4");
    }

    @Test
    void weightIsThicknessTimesDensity() {
        BigDecimal density = new BigDecimal("2.5");
        assertThat(GlassProducts.weightPerM2(new BigDecimal("6"), density)).isEqualByComparingTo("15.00");
        assertThat(GlassProducts.weightPerM2(new BigDecimal("6.38"), density)).isEqualByComparingTo("15.95");
        // Rounded half up to 2 decimals: 6.33 x 2.5 = 15.825
        assertThat(GlassProducts.weightPerM2(new BigDecimal("6.33"), density)).isEqualByComparingTo("15.83");
    }

    @Test
    void onlyTemperedGlassCannotBeCut() {
        for (GlassType type : GlassType.values()) {
            assertThat(type.isCuttable()).as(type.name()).isEqualTo(type != GlassType.TEMPERED);
        }
    }
}
