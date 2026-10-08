package com.ntaganira.heritier.iWarehouse.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** A refused stale save sends the user back to the page they came from, never off the site (NFR-06). */
class StaleDataAdviceTest {

    @Test
    void goesBackToThePathAndQueryOfTheReferringPage() {
        assertThat(StaleDataAdvice.backPath("http://localhost:8080/stock-adjustments/1b2c?tab=lines")).isEqualTo("/stock-adjustments/1b2c?tab=lines");
        assertThat(StaleDataAdvice.backPath("https://wms.example.rw/stock")).isEqualTo("/stock");
    }

    @Test
    void withoutAUsablePathItGoesToTheDashboard() {
        assertThat(StaleDataAdvice.backPath(null)).isEqualTo("/dashboard");
        assertThat(StaleDataAdvice.backPath("")).isEqualTo("/dashboard");
        assertThat(StaleDataAdvice.backPath("not a uri")).isEqualTo("/dashboard");
        assertThat(StaleDataAdvice.backPath("http://evil.example")).isEqualTo("/dashboard");
    }
}
