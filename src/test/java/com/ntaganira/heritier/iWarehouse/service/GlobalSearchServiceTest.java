package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.service.GlobalSearchService.Group;
import com.ntaganira.heritier.iWarehouse.service.GlobalSearchService.Hit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The header's search: the text as searched, what looks like a code, and when a code opens its record at once. */
class GlobalSearchServiceTest {

    @Test
    void theTextIsTrimmedAndKeptShort() {
        assertThat(GlobalSearchService.normalize(null)).isEmpty();
        assertThat(GlobalSearchService.normalize("   ")).isEmpty();
        assertThat(GlobalSearchService.normalize("  Jean   Uwase \t")).isEqualTo("Jean Uwase");
        assertThat(GlobalSearchService.normalize("x".repeat(150))).hasSize(100);
    }

    @Test
    void aLabelOrNumberIsOneWord() {
        assertThat(GlobalSearchService.isCode("U-WH-000079")).isTrue();
        assertThat(GlobalSearchService.isCode("inv-wh-2026-000001")).isTrue();
        assertThat(GlobalSearchService.isCode("RAC123A")).isTrue();
        assertThat(GlobalSearchService.isCode("Jean Uwase")).isFalse();
        assertThat(GlobalSearchService.isCode("-WH")).isFalse();
        assertThat(GlobalSearchService.isCode("50%")).isFalse();
    }

    @Test
    void theOneRecordWithExactlyThatCodeOpensWhateverTheCase() {
        List<Group> groups = List.of(
                group("nav.invoices", hit("INV-WH-2026-000001", "/invoices/a"), hit("INV-WH-2026-000010", "/invoices/b")),
                group("nav.customers", hit("C-000001", "/customers/c")));

        assertThat(GlobalSearchService.jumpTo("inv-wh-2026-000001", groups)).isEqualTo("/invoices/a");
        assertThat(GlobalSearchService.jumpTo("INV-WH-2026-00001", groups)).isNull(); // part of two numbers: listed
        assertThat(GlobalSearchService.jumpTo("Jean", groups)).isNull();
    }

    @Test
    void twoRecordsWithTheSameCodeAreListedNotOpened() {
        // A driver's username is their user's: two kinds, two pages
        List<Group> groups = List.of(group("nav.drivers", hit("jean", "/drivers/d")), group("nav.users", hit("jean", "/users/7")));

        assertThat(GlobalSearchService.jumpTo("jean", groups)).isNull();
    }

    private static Group group(String key, Hit... hits) {
        return new Group(key, "/list?search=x", hits.length, List.of(hits));
    }

    private static Hit hit(String code, String url) {
        return new Hit(code, null, null, url);
    }
}
