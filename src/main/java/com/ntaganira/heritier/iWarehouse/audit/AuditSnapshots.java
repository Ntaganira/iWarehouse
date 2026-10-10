package com.ntaganira.heritier.iWarehouse.audit;

import java.math.BigDecimal;
import java.time.temporal.TemporalAccessor;
import java.util.*;

/**
 * Pure helpers that turn entity state into JSON-friendly snapshots and compare them.
 * No Hibernate or Spring here, so the rules are unit-tested in isolation.
 */
public final class AuditSnapshots {

    public static final String MASK = "***";

    private AuditSnapshots() {
    }

    /**
     * Converts one property value to a value safe for a JSONB snapshot:
     * numbers keep their exact decimal form (BigDecimal as plain string, never a float),
     * dates and enums become text, booleans and integers stay as they are.
     */
    public static Object toAuditValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal bd) {
            return bd.stripTrailingZeros().toPlainString();
        }
        if (value instanceof Boolean || value instanceof Integer || value instanceof Long
                || value instanceof Short || value instanceof Byte) {
            return value;
        }
        if (value instanceof Number n) {
            return new BigDecimal(n.toString()).stripTrailingZeros().toPlainString();
        }
        if (value instanceof Enum<?> e) {
            return e.name();
        }
        if (value instanceof TemporalAccessor || value instanceof UUID || value instanceof CharSequence) {
            return value.toString();
        }
        if (value instanceof byte[] bytes) {
            return "<" + bytes.length + " bytes>";
        }
        return value.toString();
    }

    /** Names of fields whose values differ between two snapshots (either may be null), in a stable order. */
    public static List<String> changedFields(Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> b = before == null ? Map.of() : before;
        Map<String, Object> a = after == null ? Map.of() : after;
        Set<String> keys = new TreeSet<>(b.keySet());
        keys.addAll(a.keySet());
        List<String> changed = new ArrayList<>();
        for (String key : keys) {
            if (!Objects.equals(b.get(key), a.get(key))) {
                changed.add(key);
            }
        }
        return changed;
    }

    /** The audit stamps BaseEntity sets on every update: on their own they are no change. */
    private static final Set<String> STAMPS = Set.of("updatedAt", "updatedBy");

    /**
     * True when nothing worth a change row changed: no field, or only the update stamps (an update of ignored fields
     * still moves updatedAt, e.g. an EBM receipt's next attempt).
     */
    public static boolean nothingChanged(List<String> changed) {
        return STAMPS.containsAll(changed);
    }

    /** True if the field name is in the masked set (case-insensitive). */
    public static boolean isMaskedName(String field, Set<String> maskedLowerCase) {
        return field != null && maskedLowerCase.contains(field.toLowerCase(Locale.ROOT));
    }
}
