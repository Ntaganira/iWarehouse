package com.ntaganira.heritier.iWarehouse.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** KPI card trend: percentage, direction and label. Runs without a database. */
class TrendTest {

    @Test
    void percentageOfThePreviousValue() {
        assertThat(new Trend(112, 100).label()).isEqualTo("+12%");
        assertThat(new Trend(95, 100).label()).isEqualTo("-5%");
        assertThat(new Trend(10, 10).label()).isEqualTo("0%");
        assertThat(new Trend(2, 3).percent()).isEqualTo(-33);
    }

    @Test
    void absoluteChangeWhenNothingToCompareWith() {
        Trend fromZero = new Trend(3, 0);
        assertThat(fromZero.percent()).isNull();
        assertThat(fromZero.label()).isEqualTo("+3");
        assertThat(fromZero.isUp()).isTrue();

        Trend bothZero = new Trend(0, 0);
        assertThat(bothZero.label()).isEqualTo("0");
        assertThat(bothZero.isFlat()).isTrue();
    }

    @Test
    void directionFollowsTheRoundedPercentage() {
        Trend tiny = new Trend(1001, 1000);
        assertThat(tiny.label()).isEqualTo("0%");
        assertThat(tiny.isFlat()).isTrue();
        assertThat(new Trend(0, 4).isDown()).isTrue();
    }
}
