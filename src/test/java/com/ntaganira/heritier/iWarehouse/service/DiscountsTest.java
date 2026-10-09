package com.ntaganira.heritier.iWarehouse.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Discount rules at the counter (POS-06): the discount of a price, a user's limit from their roles, when to ask. */
class DiscountsTest {

    @Test
    void aPriceIsADiscountOnTheListPriceInPercent() {
        assertThat(Discounts.percent(new BigDecimal("27000"), new BigDecimal("24000"))).isEqualByComparingTo("11.11");
        assertThat(Discounts.percent(new BigDecimal("27000"), new BigDecimal("27000"))).isEqualByComparingTo("0");
        assertThat(Discounts.percent(new BigDecimal("25000"), new BigDecimal("26000"))).isEqualByComparingTo("-4.00");
        assertThat(Discounts.percent(BigDecimal.ZERO, new BigDecimal("100"))).isEqualByComparingTo("0");
        assertThat(Discounts.priceAt(new BigDecimal("27000"), new BigDecimal("10"))).isEqualByComparingTo("24300");
        assertThat(Discounts.priceAt(new BigDecimal("22881.36"), new BigDecimal("7.5"))).isEqualByComparingTo("21165.26");
    }

    @Test
    void aUsersLimitIsTheLargestOfTheirRolesTheSettingsValueStandingInForRolesWithoutOne() {
        BigDecimal settings = new BigDecimal("5");
        assertThat(Discounts.limitOf(Arrays.asList(null, new BigDecimal("2")), settings)).isEqualByComparingTo("5");
        assertThat(Discounts.limitOf(Arrays.asList(null, new BigDecimal("100")), settings)).isEqualByComparingTo("100");
        assertThat(Discounts.limitOf(List.of(new BigDecimal("0")), settings)).isEqualByComparingTo("0");
        assertThat(Discounts.limitOf(List.of(), settings)).isEqualByComparingTo("0");
    }

    @Test
    void onlyADiscountAboveTheLimitNeedsApproval() {
        BigDecimal limit = new BigDecimal("5");
        assertThat(Discounts.needsApproval(new BigDecimal("5.00"), limit)).isFalse();
        assertThat(Discounts.needsApproval(new BigDecimal("5.01"), limit)).isTrue();
        assertThat(Discounts.needsApproval(new BigDecimal("-10"), limit)).isFalse();     // a higher price
        assertThat(Discounts.needsApproval(new BigDecimal("0.01"), BigDecimal.ZERO)).isTrue();
        assertThat(Discounts.needsApproval(BigDecimal.ZERO, BigDecimal.ZERO)).isFalse();
    }
}
