package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.enums.LocationType;

import java.util.*;
import java.util.function.Function;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : LocationTree.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : Pure helpers for the location tree (MD-02): flattening it into display order with
 *               depths, and suggesting the next free code for a new location (WH-A-R03). No Spring
 *               here, so they are unit-tested.
 * </pre>
 */
public final class LocationTree {

    public static final int MAX_CODE_LENGTH = 30;

    private LocationTree() {
    }

    /** One line of the tree: the item, how deep it sits (roots are 0) and how many children it has. */
    public record Row<T>(T item, int depth, int children) {
    }

    /**
     * Depth-first order: each item followed by its children, siblings sorted by {@code order}.
     * An item whose parent is not in the list (filtered out) is shown as a root.
     */
    public static <T> List<Row<T>> flatten(Collection<T> items, Function<T, UUID> id, Function<T, UUID> parentId,
                                           Comparator<T> order) {
        Map<UUID, T> byId = new HashMap<>();
        items.forEach(item -> byId.put(id.apply(item), item));
        Map<UUID, List<T>> children = new HashMap<>();
        List<T> roots = new ArrayList<>();
        for (T item : items) {
            UUID parent = parentId.apply(item);
            if (parent != null && byId.containsKey(parent)) {
                children.computeIfAbsent(parent, k -> new ArrayList<>()).add(item);
            } else {
                roots.add(item);
            }
        }
        roots.sort(order);
        children.values().forEach(list -> list.sort(order));

        List<Row<T>> rows = new ArrayList<>(items.size());
        Set<UUID> seen = new HashSet<>();
        Deque<Map.Entry<T, Integer>> stack = new ArrayDeque<>();
        for (int i = roots.size() - 1; i >= 0; i--) {
            stack.push(Map.entry(roots.get(i), 0));
        }
        while (!stack.isEmpty()) {
            Map.Entry<T, Integer> next = stack.pop();
            T item = next.getKey();
            if (!seen.add(id.apply(item))) {
                continue; // a cycle would loop forever; the schema never makes one, but stay safe
            }
            List<T> kids = children.getOrDefault(id.apply(item), List.of());
            rows.add(new Row<>(item, next.getValue(), kids.size()));
            for (int i = kids.size() - 1; i >= 0; i--) {
                stack.push(Map.entry(kids.get(i), next.getValue() + 1));
            }
        }
        return rows;
    }

    /**
     * Next free code under a parent: zones take the next letter (WH-A, WH-B), racks R01, R02...,
     * slots S01, S02... Returns null for sites and vehicles, when nothing is free, or when the
     * code would be longer than {@value #MAX_CODE_LENGTH} characters. {@code taken} holds every
     * code in use, in capitals (codes are unique across the whole tree).
     */
    public static String suggestCode(String parentCode, LocationType type, Set<String> taken) {
        if (parentCode == null || type == null || type.isRoot()) {
            return null;
        }
        List<String> candidates = new ArrayList<>();
        switch (type) {
            case ZONE -> {
                for (char c = 'A'; c <= 'Z'; c++) {
                    candidates.add(parentCode + "-" + c);
                }
            }
            case RACK -> numbered(candidates, parentCode + "-R");
            case SLOT -> numbered(candidates, parentCode + "-S");
            default -> {
                return null;
            }
        }
        for (String code : candidates) {
            if (code.length() > MAX_CODE_LENGTH) {
                return null;
            }
            if (!taken.contains(code)) {
                return code;
            }
        }
        return null;
    }

    private static void numbered(List<String> candidates, String prefix) {
        for (int n = 1; n <= 99; n++) {
            candidates.add(prefix + (n < 10 ? "0" : "") + n);
        }
    }
}
