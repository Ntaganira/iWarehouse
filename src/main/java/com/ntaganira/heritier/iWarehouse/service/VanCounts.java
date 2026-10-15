package com.ntaganira.heritier.iWarehouse.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : VanCounts.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : A count of the glass on a vehicle on the road (SDP 6, BP 7.3 rule 10): the labels scanned against those
 *               expected on board: found, missing, and labels not expected (each once, upper case, blanks skipped).
 *               Pure and tested.
 * </pre>
 */
public final class VanCounts {

    private VanCounts() {
    }

    public record Result(List<String> found, List<String> missing, List<String> extra) {

        public boolean isClean() {
            return missing.isEmpty() && extra.isEmpty();
        }
    }

    public static Result compare(Collection<String> expected, Collection<String> scanned) {
        Set<String> onBoard = new TreeSet<>();
        expected.forEach(c -> onBoard.add(c.trim().toUpperCase(Locale.ROOT)));
        Set<String> seen = new LinkedHashSet<>();
        if (scanned != null) {
            for (String code : scanned) {
                if (code != null && !code.isBlank()) {
                    seen.add(code.trim().toUpperCase(Locale.ROOT));
                }
            }
        }
        List<String> found = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String code : onBoard) {
            (seen.contains(code) ? found : missing).add(code);
        }
        List<String> extra = seen.stream().filter(c -> !onBoard.contains(c)).sorted().toList();
        return new Result(found, missing, extra);
    }

    /** The labels in a text as typed or scanned: one per line, or separated by commas or spaces. */
    public static List<String> codes(String text) {
        if (text == null) {
            return List.of();
        }
        List<String> codes = new ArrayList<>();
        for (String part : text.split("[\\s,;]+")) {
            if (!part.isBlank()) {
                codes.add(part.trim().toUpperCase(Locale.ROOT));
            }
        }
        return codes;
    }
}
