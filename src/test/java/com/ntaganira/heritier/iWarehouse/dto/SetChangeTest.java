package com.ntaganira.heritier.iWarehouse.dto;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Added/removed codes written to the activity log for collection changes (AUD-03). */
class SetChangeTest {

    @Test
    void listsAddedAndRemovedSorted() {
        SetChange change = SetChange.of(Set.of("VIEW_USER", "EDIT_USER"), Set.of("VIEW_USER", "CREATE_USER", "ASSIGN_ROLE"));

        assertThat(change.added()).containsExactly("ASSIGN_ROLE", "CREATE_USER");
        assertThat(change.removed()).containsExactly("EDIT_USER");
        assertThat(change.describe("permissions"))
                .isEqualTo("permissions added [ASSIGN_ROLE, CREATE_USER], removed [EDIT_USER]");
    }

    @Test
    void onlyRemovals() {
        assertThat(SetChange.of(Set.of("CASHIER"), Set.of()).describe("roles")).isEqualTo("roles removed [CASHIER]");
    }

    @Test
    void sameSetsMeanNoChange() {
        SetChange change = SetChange.of(Set.of("A", "B"), Set.of("B", "A"));
        assertThat(change.isEmpty()).isTrue();
        assertThat(change.describe("pages")).isEmpty();
    }
}
