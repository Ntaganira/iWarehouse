package com.ntaganira.heritier.iWarehouse.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Chargeable area, list fallback, typed prices (MD-06) and TIN rules (TAX-04). */
class PricingTest {

    private static final BigDecimal MIN = new BigDecimal("0.25");

    @Test
    void areaIsWidthTimesHeightInSquareMetres() {
        assertThat(Pricing.areaM2(1200, 800)).isEqualByComparingTo("0.96");
        assertThat(Pricing.areaM2(3210, 2250)).isEqualByComparingTo("7.2225");
        assertThat(Pricing.areaM2(333, 333)).isEqualByComparingTo("0.1109"); // 0.110889 rounded half up
    }

    @Test
    void smallPiecesAreChargedTheMinimumArea() {
        assertThat(Pricing.chargeableArea(300, 400, MIN)).isEqualByComparingTo("0.25");  // 0.12 m²
        assertThat(Pricing.chargeableArea(500, 500, MIN)).isEqualByComparingTo("0.25");  // exactly the minimum
        assertThat(Pricing.chargeableArea(1200, 800, MIN)).isEqualByComparingTo("0.96");
        assertThat(Pricing.chargeableArea(300, 400, null)).isEqualByComparingTo("0.12");
    }

    @Test
    void theCustomersListComesFirstThenTheDefaultList() {
        assertThat(Pricing.resolve(new BigDecimal("22000"), new BigDecimal("25000")))
                .hasValueSatisfying(r -> {
                    assertThat(r.price()).isEqualByComparingTo("22000");
                    assertThat(r.fromDefaultList()).isFalse();
                });
        assertThat(Pricing.resolve(null, new BigDecimal("25000")))
                .hasValueSatisfying(r -> assertThat(r.fromDefaultList()).isTrue());
        assertThat(Pricing.resolve(null, null)).isEmpty();
    }

    @Test
    void typedPricesAcceptSpacesThousandsCommasAndADecimalComma() {
        assertThat(Pricing.parsePrice("25000")).isEqualByComparingTo("25000");
        assertThat(Pricing.parsePrice(" 25 000 ")).isEqualByComparingTo("25000");
        assertThat(Pricing.parsePrice("1,250,000")).isEqualByComparingTo("1250000");
        assertThat(Pricing.parsePrice("1,250.50")).isEqualByComparingTo("1250.50");
        assertThat(Pricing.parsePrice("12,5")).isEqualByComparingTo("12.5");
        assertThat(Pricing.parsePrice("25000.00").scale()).isEqualTo(2);
        assertThat(Pricing.parsePrice("")).isNull();
        assertThat(Pricing.parsePrice(null)).isNull();
    }

    @Test
    void wrongPricesAreRefusedWithAReason() {
        assertThatThrownBy(() -> Pricing.parsePrice("abc")).hasMessage("price.invalid");
        assertThatThrownBy(() -> Pricing.parsePrice("0")).hasMessage("price.positive");
        assertThatThrownBy(() -> Pricing.parsePrice("-5")).hasMessage("price.positive");
        assertThatThrownBy(() -> Pricing.parsePrice("10.555")).hasMessage("price.decimals");
        assertThatThrownBy(() -> Pricing.parsePrice("12345678901234567")).hasMessage("price.tooLarge");
    }

    @Test
    void tinIsTidiedAndARwandanOneHasNineDigits() {
        assertThat(PartyRules.normalizeTin(" 100 200-300 ")).isEqualTo("100200300");
        assertThat(PartyRules.normalizeTin("  ")).isNull();
        assertThat(PartyRules.isRwandaTin("100200300")).isTrue();
        assertThat(PartyRules.isRwandaTin("10020030")).isFalse();
        assertThat(PartyRules.isRwandaTin("10020030A")).isFalse();
    }
}
