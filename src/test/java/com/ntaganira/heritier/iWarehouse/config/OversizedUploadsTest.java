package com.ntaganira.heritier.iWarehouse.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** A file over the upload limit is told apart from a forged request, and the user goes back to their page on this server. */
class OversizedUploadsTest {

    private static final long LIMIT = 5L * 1024 * 1024;

    @Test
    void onlyAMultipartBodyOverTheLimitIsAnOversizedUpload() {
        assertThat(OversizedUploads.tooLarge("multipart/form-data; boundary=x", LIMIT + 1, LIMIT)).isTrue();
        assertThat(OversizedUploads.tooLarge("Multipart/Form-Data; boundary=x", 7_340_032, LIMIT)).isTrue();
        assertThat(OversizedUploads.tooLarge("multipart/form-data; boundary=x", LIMIT, LIMIT)).isFalse();
        assertThat(OversizedUploads.tooLarge("application/x-www-form-urlencoded", LIMIT * 2, LIMIT)).isFalse(); // a forged form
        assertThat(OversizedUploads.tooLarge(null, LIMIT * 2, LIMIT)).isFalse();
        assertThat(OversizedUploads.tooLarge("multipart/form-data", -1, LIMIT)).isFalse();                // length unknown
    }

    @Test
    void theUserGoesBackToTheirPageOnThisServerOnly() {
        assertThat(OversizedUploads.backTo("http://localhost:8080/settings?tab=general", "localhost")).isEqualTo("/settings?tab=general");
        assertThat(OversizedUploads.backTo("http://LOCALHOST:8080/stock-adjustments/5e1?tab=documents", "localhost"))
                .isEqualTo("/stock-adjustments/5e1?tab=documents");
        assertThat(OversizedUploads.backTo("/profile", "localhost")).isEqualTo("/profile");
        assertThat(OversizedUploads.backTo("https://evil.example/settings", "localhost")).isEqualTo("/dashboard");
        assertThat(OversizedUploads.backTo("//evil.example/x", "localhost")).isEqualTo("/dashboard");
        assertThat(OversizedUploads.backTo("javascript:alert(1)", "localhost")).isEqualTo("/dashboard");
        assertThat(OversizedUploads.backTo("not a url", "localhost")).isEqualTo("/dashboard");
        assertThat(OversizedUploads.backTo(null, "localhost")).isEqualTo("/dashboard");
    }

    @Test
    void sizesReadAsMegabytes() {
        assertThat(OversizedUploads.megabytes(7_654_321)).isEqualTo("7.3 MB");
        assertThat(OversizedUploads.megabytes(LIMIT)).isEqualTo("5.0 MB");
    }
}
