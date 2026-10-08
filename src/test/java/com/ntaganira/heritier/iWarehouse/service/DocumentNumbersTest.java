package com.ntaganira.heritier.iWarehouse.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static com.ntaganira.heritier.iWarehouse.enums.ResetPolicy.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Number format, periods and rollover of DocumentNumbers (MD-07). */
class DocumentNumbersTest {

    private static final LocalDate OCT_7 = LocalDate.of(2026, 10, 7);

    @Test
    void formatsLikeTheSrsExample() {
        assertThat(DocumentNumbers.format("INV", "WH", YEARLY, OCT_7, 123, 6)).isEqualTo("INV-WH-2026-000123");
        assertThat(DocumentNumbers.format("INV", "WH", MONTHLY, OCT_7, 7, 4)).isEqualTo("INV-WH-202610-0007");
        assertThat(DocumentNumbers.format("PO", "V01", NEVER, OCT_7, 42, 5)).isEqualTo("PO-V01-00042");
    }

    @Test
    void numberLongerThanPaddingIsKeptWhole() {
        assertThat(DocumentNumbers.format("JV", "WH", NEVER, OCT_7, 1_234_567, 3)).isEqualTo("JV-WH-1234567");
    }

    @Test
    void periodKeyFollowsThePolicy() {
        assertThat(DocumentNumbers.periodKey(NEVER, OCT_7)).isEmpty();
        assertThat(DocumentNumbers.periodKey(YEARLY, OCT_7)).isEqualTo("2026");
        assertThat(DocumentNumbers.periodKey(MONTHLY, LocalDate.of(2026, 3, 1))).isEqualTo("2026-03");
    }

    @Test
    void counterContinuesWithinThePeriodAndRestartsAfterIt() {
        assertThat(DocumentNumbers.effectiveNext(YEARLY, "2026", 124, OCT_7)).isEqualTo(124);
        assertThat(DocumentNumbers.effectiveNext(YEARLY, "2025", 900, OCT_7)).isEqualTo(1);
        assertThat(DocumentNumbers.effectiveNext(MONTHLY, "2026-09", 55, OCT_7)).isEqualTo(1);
        assertThat(DocumentNumbers.effectiveNext(NEVER, "", 5000, OCT_7)).isEqualTo(5000);
    }

    @Test
    void startingNumberSetBeforeFirstUseIsKept() {
        // Blank period = nothing issued yet: a number carried over from an old system must not reset.
        assertThat(DocumentNumbers.effectiveNext(YEARLY, "", 1250, OCT_7)).isEqualTo(1250);
    }

    @Test
    void clockBehindTheLastPeriodIsDetected() {
        assertThat(DocumentNumbers.isBehind(YEARLY, "2027", OCT_7)).isTrue();
        assertThat(DocumentNumbers.isBehind(MONTHLY, "2026-11", OCT_7)).isTrue();
        assertThat(DocumentNumbers.isBehind(YEARLY, "2026", OCT_7)).isFalse();
        assertThat(DocumentNumbers.isBehind(YEARLY, "", OCT_7)).isFalse();
        assertThat(DocumentNumbers.isBehind(NEVER, "", OCT_7)).isFalse();
    }
}
