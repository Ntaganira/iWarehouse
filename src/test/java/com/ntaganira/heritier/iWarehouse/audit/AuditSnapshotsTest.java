package com.ntaganira.heritier.iWarehouse.audit;

import com.ntaganira.heritier.iWarehouse.enums.ChangeOperation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Rules for audit snapshots (SRS 4.13). Runs without a database. */
class AuditSnapshotsTest {

    @Test
    void moneyAndDimensionsKeepExactDecimals() {
        assertThat(AuditSnapshots.toAuditValue(new BigDecimal("1500000.00"))).isEqualTo("1500000");
        assertThat(AuditSnapshots.toAuditValue(new BigDecimal("7.2225"))).isEqualTo("7.2225");
        assertThat(AuditSnapshots.toAuditValue(0.1d)).isEqualTo("0.1");
    }

    @Test
    void simpleTypesBecomeJsonFriendly() {
        UUID id = UUID.fromString("3f2a9c1e-5b7d-4e8a-9c21-7d4e5f6a8b90");
        assertThat(AuditSnapshots.toAuditValue(ChangeOperation.UPDATE)).isEqualTo("UPDATE");
        assertThat(AuditSnapshots.toAuditValue(LocalDate.of(2026, 10, 7))).isEqualTo("2026-10-07");
        assertThat(AuditSnapshots.toAuditValue(id)).isEqualTo(id.toString());
        assertThat(AuditSnapshots.toAuditValue(42L)).isEqualTo(42L);
        assertThat(AuditSnapshots.toAuditValue(true)).isEqualTo(true);
        assertThat(AuditSnapshots.toAuditValue(null)).isNull();
    }

    @Test
    void changedFieldsListsOnlyDifferences() {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("creditLimit", "500000");
        before.put("paymentTermsDays", 15);
        before.put("name", "Kigali Glass Ltd");
        Map<String, Object> after = new LinkedHashMap<>(before);
        after.put("creditLimit", "1500000");
        after.put("paymentTermsDays", 30);

        assertThat(AuditSnapshots.changedFields(before, after))
                .containsExactly("creditLimit", "paymentTermsDays");
    }

    @Test
    void createAndDeleteReportEveryNonNullField() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("width", 1200);
        state.put("height", 800);
        state.put("note", null);

        List<String> created = AuditSnapshots.changedFields(null, state);
        List<String> deleted = AuditSnapshots.changedFields(state, null);

        assertThat(created).containsExactly("height", "width");
        assertThat(deleted).containsExactly("height", "width");
    }

    @Test
    void maskedNamesAreCaseInsensitive() {
        Set<String> masked = Set.of("password", "token");
        assertThat(AuditSnapshots.isMaskedName("Password", masked)).isTrue();
        assertThat(AuditSnapshots.isMaskedName("TOKEN", masked)).isTrue();
        assertThat(AuditSnapshots.isMaskedName("username", masked)).isFalse();
    }
}
