package com.ntaganira.heritier.iWarehouse.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Reading exchange rate files as typed or exported from Excel (ACC-02). */
class RateCsvTest {

    @Test
    void readsCommaFileWithHeader() {
        RateCsv.Parsed parsed = RateCsv.parse("date,currency,rate\n2026-10-06,USD,1448.10\n2026-10-07,usd,\"1,450.25\"\n", 100);

        assertThat(parsed.problems()).isEmpty();
        assertThat(parsed.lines()).extracting(RateCsv.Line::number).containsExactly(2, 3);
        RateCsv.Line second = parsed.lines().get(1);
        assertThat(second.date()).isEqualTo(LocalDate.of(2026, 10, 7));
        assertThat(second.currencyCode()).isEqualTo("USD");
        assertThat(second.rate()).isEqualByComparingTo("1450.25");
    }

    @Test
    void readsFrenchExcelSemicolonFile() {
        RateCsv.Parsed parsed = RateCsv.parse("﻿Date;Devise;Taux\r\n07/10/2026;EUR;1 689,40\r\n07.10.2026;CNY;196,123456\r\n", 100);

        assertThat(parsed.problems()).isEmpty();
        assertThat(parsed.lines()).extracting(RateCsv.Line::rate)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("1689.40"), new BigDecimal("196.123456"));
    }

    @Test
    void reportsEachBadLineWithItsNumber() {
        String file = String.join("\n",
                "2026-10-07,USD",                // 1: two columns
                "2026-13-01,USD,1450",           // 2: no month 13
                "2026-10-07,US1,1450",           // 3: bad code
                "2026-10-07,EUR,0",              // 4: not positive
                "2026-10-07,EUR,1689.1234567",   // 5: 7 decimals
                "# a comment",
                "",
                "2026-10-07,CNY,196.12");         // 8: fine
        RateCsv.Parsed parsed = RateCsv.parse(file, 100);

        assertThat(parsed.problems()).extracting(RateCsv.Problem::line).containsExactly(1, 2, 3, 4, 5);
        assertThat(parsed.problems()).extracting(RateCsv.Problem::messageKey).containsExactly(
                "rate.import.columns", "rate.import.date", "rate.import.currency", "rate.import.rate", "rate.import.rate");
        assertThat(parsed.lines()).extracting(RateCsv.Line::number).containsExactly(8);
    }

    @Test
    void emptyAndOversizedFilesAreRefused() {
        assertThat(RateCsv.parse("date,currency,rate\n\n", 100).problems())
                .extracting(RateCsv.Problem::messageKey).containsExactly("rate.import.empty");
        assertThat(RateCsv.parse("2026-10-06,USD,1\n2026-10-07,USD,1\n2026-10-08,USD,1\n", 2).problems())
                .extracting(RateCsv.Problem::messageKey).containsExactly("rate.import.tooMany");
    }

    @Test
    void decimalMarkRules() {
        assertThat(RateCsv.parseRate("1.450,25", true)).isEqualByComparingTo("1450.25");
        assertThat(RateCsv.parseRate("1,450.25", false)).isEqualByComparingTo("1450.25");
        assertThat(RateCsv.parseRate("1450,25", true)).isEqualByComparingTo("1450.25");
        assertThat(RateCsv.parseRate("-5", false)).isNull();
        assertThat(RateCsv.parseRate("abc", false)).isNull();
        assertThat(RateCsv.parseRate("1234567890123", false)).isNull(); // 13 integer digits
    }
}
