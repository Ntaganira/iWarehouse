package com.ntaganira.heritier.iWarehouse.service;

import java.util.*;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : CountPasses.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : A count's passes (IMP 15.2, CSH-012, PF-06; decision D14): the first, blind count; a second, independent
 *               count; the recount of the labels the two disagree on (found by one and not the other, or found on two
 *               places). The count's result is what both counts agree on, and the recount for the rest: a label the
 *               recount does not find is not there. Pure and tested.
 * </pre>
 */
public final class CountPasses {

    private CountPasses() {
    }

    /** A label found on a place in one pass. */
    public record Found(String code, UUID placeId) {
    }

    /** The labels the two counts disagree on, sorted. */
    public static List<String> differences(Collection<Found> first, Collection<Found> second) {
        Map<String, UUID> a = byCode(first);
        Map<String, UUID> b = byCode(second);
        Set<String> codes = new TreeSet<>(a.keySet());
        codes.addAll(b.keySet());
        return codes.stream().filter(c -> !Objects.equals(a.get(c), b.get(c))).toList();
    }

    /**
     * What the count found: the first count alone when there was no second; otherwise the labels both counts found on
     * the same place, and for each label they disagree on, where the recount found it (not found when the recount did
     * not). Sorted by label.
     */
    public static List<Found> resolve(Collection<Found> first, Collection<Found> second, Collection<Found> recount, boolean secondDone) {
        Map<String, UUID> a = byCode(first);
        if (!secondDone) {
            return sorted(a);
        }
        Map<String, UUID> b = byCode(second);
        Map<String, UUID> r = byCode(recount);
        Map<String, UUID> result = new TreeMap<>();
        Set<String> codes = new TreeSet<>(a.keySet());
        codes.addAll(b.keySet());
        for (String code : codes) {
            UUID inFirst = a.get(code);
            if (Objects.equals(inFirst, b.get(code))) {
                result.put(code, inFirst);
            } else if (r.containsKey(code)) {
                result.put(code, r.get(code));
            }
        }
        // A label only the recount found (seen while recounting a place) counts too
        r.forEach(result::putIfAbsent);
        return sorted(result);
    }

    private static Map<String, UUID> byCode(Collection<Found> found) {
        Map<String, UUID> map = new TreeMap<>();
        if (found != null) {
            found.forEach(f -> map.put(f.code(), f.placeId()));
        }
        return map;
    }

    private static List<Found> sorted(Map<String, UUID> map) {
        return new TreeMap<>(map).entrySet().stream().map(e -> new Found(e.getKey(), e.getValue())).toList();
    }
}
