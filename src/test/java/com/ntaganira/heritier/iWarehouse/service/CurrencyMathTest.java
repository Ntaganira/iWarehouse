package com.ntaganira.heritier.iWarehouse.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** Conversions, rounding and the large-change rule of CurrencyMath (ACC-02). */
class CurrencyMathTest {

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    @Test
    void convertsToRwfWithoutRoundingUntilTheTotal() {
        BigDecimal line1 = CurrencyMath.toBase(d("10.33"), d("1450.25"));   // 14981.0825
        BigDecimal line2 = CurrencyMath.toBase(d("20.17"), d("1450.25"));   // 29251.5425
        assertThat(line1).isEqualByComparingTo("14981.0825");
        // Rounded once at the document total, half up, to whole francs.
        assertThat(CurrencyMath.round(line1.add(line2), 0)).isEqualByComparingTo("44233");
    }

    @Test
    void roundsHalfUpToTheCurrencyDecimals() {
        assertThat(CurrencyMath.round(d("1450.5"), 0)).isEqualByComparingTo("1451");
        assertThat(CurrencyMath.round(d("1450.4999"), 0)).isEqualByComparingTo("1450");
        assertThat(CurrencyMath.round(d("12.345"), 2)).isEqualByComparingTo("12.35");
    }

    @Test
    void convertsBackAndCrossesRates() {
        assertThat(CurrencyMath.round(CurrencyMath.fromBase(d("145025"), d("1450.25")), 2)).isEqualByComparingTo("100.00");
        // 1 EUR = 1689.40 RWF and 1 USD = 1450.25 RWF, so 1 EUR = 1.164903 USD.
        assertThat(CurrencyMath.cross(d("1689.40"), d("1450.25"))).isEqualByComparingTo("1.164903");
    }

    @Test
    void largeChangeIsMoreThanTheThreshold() {
        assertThat(CurrencyMath.changePercent(d("1450"), d("1595"))).isEqualByComparingTo("10.00");
        assertThat(CurrencyMath.isLargeChange(d("1450"), d("1595"), BigDecimal.TEN)).isFalse(); // exactly 10%
        assertThat(CurrencyMath.isLargeChange(d("1450"), d("1595.20"), BigDecimal.TEN)).isTrue();
        assertThat(CurrencyMath.isLargeChange(d("1450.20"), d("14502"), BigDecimal.TEN)).isTrue(); // a slipped decimal point
        assertThat(CurrencyMath.changePercent(d("1450"), d("1305"))).isEqualByComparingTo("-10.00");
    }
}
