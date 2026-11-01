package com.ntaganira.heritier.iWarehouse.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : ServicePlansTest.java
 * - Date      : 2026. 10. 31.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : A vehicle's monthly distance target: how much of it the km driven this month are.
 * </pre>
 */
class ServicePlansTest {

    @Test
    void theMonthProgressIsTheShareOfTheTargetAndNullWithoutOne() {
        assertThat(ServicePlans.monthProgress(2_000, 6_000)).isEqualTo(33);
        assertThat(ServicePlans.monthProgress(3_000, 6_000)).isEqualTo(50);
        assertThat(ServicePlans.monthProgress(9_000, 6_000)).isEqualTo(150);
        assertThat(ServicePlans.monthProgress(100_000, 1)).isEqualTo(999);
        assertThat(ServicePlans.monthProgress(0, 6_000)).isZero();
        assertThat(ServicePlans.monthProgress(2_000, null)).isNull();
        assertThat(ServicePlans.monthProgress(2_000, 0)).isNull();
    }
}
