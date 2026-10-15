package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.exception.BusinessException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : CashCount.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : A till's cash counted note by note (F-13): the denominations of the Settings, how many of each, the
 *               total, and the count kept on the till as "5000:12,1000:3" (largest first, none left out but zeros).
 *               Pure and tested.
 * </pre>
 */
public final class CashCount {

    private CashCount() {
    }

    /** The denominations of the Settings ("5000,2000,...,1"), largest first, each once; a value that is not a whole positive number is skipped. */
    public static List<Integer> denominations(String setting) {
        List<Integer> values = new ArrayList<>();
        if (setting != null) {
            for (String part : setting.split(",")) {
                try {
                    int v = Integer.parseInt(part.trim());
                    if (v > 0 && !values.contains(v)) {
                        values.add(v);
                    }
                } catch (NumberFormatException e) {
                    // not a denomination
                }
            }
        }
        values.sort(Collections.reverseOrder());
        return values;
    }

    /**
     * The count entered: how many of each denomination (blank = none). Refused when a count is negative or names a note
     * the Settings do not list.
     */
    public static Map<Integer, Integer> of(Map<Integer, Integer> entered, List<Integer> denominations) {
        Map<Integer, Integer> counts = new TreeMap<>(Collections.reverseOrder());
        if (entered == null) {
            return counts;
        }
        for (Map.Entry<Integer, Integer> e : entered.entrySet()) {
            if (e.getValue() == null || e.getValue() == 0) {
                continue;
            }
            if (!denominations.contains(e.getKey())) {
                throw BusinessException.onField("countedCash", "till.count.unknown", e.getKey());
            }
            if (e.getValue() < 0) {
                throw BusinessException.onField("count" + e.getKey(), "till.count.negative", e.getKey());
            }
            counts.put(e.getKey(), e.getValue());
        }
        return counts;
    }

    public static BigDecimal total(Map<Integer, Integer> counts) {
        long sum = 0;
        for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
            sum += (long) e.getKey() * e.getValue();
        }
        return BigDecimal.valueOf(sum).setScale(2);
    }

    /** "5000:12,1000:3", largest first; null for no count. */
    public static String format(Map<Integer, Integer> counts) {
        Map<Integer, Integer> sorted = new TreeMap<>(Collections.reverseOrder());
        sorted.putAll(counts);
        StringBuilder sb = new StringBuilder();
        sorted.forEach((d, n) -> {
            if (n != null && n > 0) {
                sb.append(sb.isEmpty() ? "" : ",").append(d).append(':').append(n);
            }
        });
        return sb.isEmpty() ? null : sb.toString();
    }

    /** A count kept on a till, largest first (an unreadable part is skipped). */
    public static Map<Integer, Integer> parse(String stored) {
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        if (stored == null || stored.isBlank()) {
            return counts;
        }
        for (String part : stored.split(",")) {
            String[] dn = part.split(":");
            if (dn.length == 2) {
                try {
                    counts.put(Integer.parseInt(dn[0].trim()), Integer.parseInt(dn[1].trim()));
                } catch (NumberFormatException e) {
                    // skipped
                }
            }
        }
        return counts;
    }
}
