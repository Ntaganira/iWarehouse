package com.ntaganira.heritier.iWarehouse.dto;

import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.dto
 * - File      : SetChange.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : What was added to and removed from a set of codes (a user's roles, a role's
 *               permissions or pages). Collection changes are not captured by the data change log,
 *               so services write this to the activity log instead (CLAUDE.md audit rule 3, AUD-03).
 * </pre>
 */
public record SetChange(List<String> added, List<String> removed) {

    /** Both lists sorted, so the same change always reads the same way. */
    public static SetChange of(Collection<String> before, Collection<String> after) {
        TreeSet<String> added = new TreeSet<>(after);
        added.removeAll(before);
        TreeSet<String> removed = new TreeSet<>(before);
        removed.removeAll(after);
        return new SetChange(List.copyOf(added), List.copyOf(removed));
    }

    public boolean isEmpty() {
        return added.isEmpty() && removed.isEmpty();
    }

    /** "permissions added [A, B], removed [C]"; empty text when nothing changed. */
    public String describe(String label) {
        if (isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder(label);
        if (!added.isEmpty()) {
            text.append(" added ").append(added);
        }
        if (!removed.isEmpty()) {
            text.append(added.isEmpty() ? " removed " : ", removed ").append(removed);
        }
        return text.toString();
    }
}
