package com.ntaganira.heritier.iWarehouse.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : JournalRegister.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : The register of journal numbers (F-16): the numbers missing between a month's first and last journal of
 *               each series (JV-WH-2026-), which no journal of any month holds. Journals are numbered in the order they
 *               are posted, so a journal dated in another month may sit between two of this month's: it is not a gap.
 *               Pure and tested.
 * </pre>
 */
public final class JournalRegister {

    private JournalRegister() {
    }

    /** A number split into its series and its sequence, keeping the width of the digits. */
    record Number(String series, long seq, int width) {

        String format(long n) {
            return series + String.format("%0" + width + "d", n);
        }
    }

    static Optional<Number> parse(String number) {
        if (number == null) {
            return Optional.empty();
        }
        int i = number.length();
        while (i > 0 && Character.isDigit(number.charAt(i - 1))) {
            i--;
        }
        if (i == number.length()) {
            return Optional.empty();
        }
        String digits = number.substring(i);
        return Optional.of(new Number(number.substring(0, i), Long.parseLong(digits), digits.length()));
    }

    /**
     * The numbers missing between the month's first and last journal of each series, as ranges ("JV-WH-2026-000140" or
     * "JV-WH-2026-000140 to 000142").
     */
    public static List<String> gaps(Collection<String> monthNumbers, Collection<String> allNumbers) {
        Map<String, long[]> bounds = new TreeMap<>();
        Map<String, Integer> widths = new HashMap<>();
        for (String n : monthNumbers) {
            parse(n).ifPresent(p -> {
                bounds.merge(p.series(), new long[]{p.seq(), p.seq()}, (a, b) -> new long[]{Math.min(a[0], b[0]), Math.max(a[1], b[1])});
                widths.putIfAbsent(p.series(), p.width());
            });
        }
        Set<String> present = new HashSet<>(allNumbers);
        present.addAll(monthNumbers);
        List<String> gaps = new ArrayList<>();
        for (Map.Entry<String, long[]> e : bounds.entrySet()) {
            Number fmt = new Number(e.getKey(), 0, widths.get(e.getKey()));
            Long start = null;
            for (long s = e.getValue()[0]; s <= e.getValue()[1] + 1; s++) {
                boolean missing = s <= e.getValue()[1] && !present.contains(fmt.format(s));
                if (missing && start == null) {
                    start = s;
                } else if (!missing && start != null) {
                    long end = s - 1;
                    gaps.add(end == start ? fmt.format(start)
                            : fmt.format(start) + " to " + String.format("%0" + fmt.width() + "d", end));
                    start = null;
                }
            }
        }
        return gaps;
    }

    /** The gaps in one line of at most so many characters ("…" when cut); null without gaps. */
    public static String summary(List<String> gaps, int max) {
        if (gaps.isEmpty()) {
            return null;
        }
        String all = String.join(", ", gaps);
        return all.length() <= max ? all : all.substring(0, max - 1) + "…";
    }
}
