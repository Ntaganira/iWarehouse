package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.enums.AuthorityLevel;

import java.math.BigDecimal;
import java.util.*;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : AuthorityTiers.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The rules of the Delegation of Authority (Board Paper Annex E), pure and tested. A request needs the
 *               lowest office that may approve its decision and whose limit covers its amount (a limit includes its
 *               amount; none means no limit); the Board when no office below it may. A user holds the highest office
 *               among their rights and may decide what needs that office or a lower one. A higher office's limit is
 *               never below a lower one's: it may decide whatever the lower one may anyway.
 * </pre>
 */
public final class AuthorityTiers {

    private AuthorityTiers() {
    }

    /** One office's cell of a decision: may it approve, and up to what (null: no limit). */
    public record Cell(AuthorityLevel level, boolean allowed, BigDecimal max) {
    }

    /** The lowest office allowed whose limit covers the amount; the Board when none below it does. */
    public static AuthorityLevel required(Collection<Cell> cells, BigDecimal amount) {
        BigDecimal value = amount == null ? BigDecimal.ZERO : amount;
        Map<AuthorityLevel, Cell> byLevel = byLevel(cells);
        for (AuthorityLevel level : AuthorityLevel.values()) {
            if (level == AuthorityLevel.BOARD) {
                break;
            }
            Cell cell = byLevel.get(level);
            if (cell != null && cell.allowed() && (cell.max() == null || value.compareTo(cell.max()) <= 0)) {
                return level;
            }
        }
        return AuthorityLevel.BOARD;
    }

    /** The highest office a user holds by their rights ("PERM_AUTHORITY_COO"...), none when they hold no office. */
    public static Optional<AuthorityLevel> highest(Collection<String> authorities) {
        AuthorityLevel best = null;
        for (AuthorityLevel level : AuthorityLevel.values()) {
            if (authorities.contains(level.permission())) {
                best = level;
            }
        }
        return Optional.ofNullable(best);
    }

    /** Whether someone holding {@code held} may decide what needs {@code required}. */
    public static boolean mayDecide(Optional<AuthorityLevel> held, AuthorityLevel required) {
        return held.map(h -> h.atLeast(required)).orElse(false);
    }

    /**
     * The first office whose limit is below the limit of an office under it (both allowed), or that has a limit while an
     * office under it has none: such a cell means nothing. Empty when the cells hold together.
     */
    public static Optional<AuthorityLevel> inconsistent(Collection<Cell> cells) {
        Map<AuthorityLevel, Cell> byLevel = byLevel(cells);
        BigDecimal highestBelow = null;
        boolean unlimitedBelow = false;
        for (AuthorityLevel level : AuthorityLevel.values()) {
            if (level == AuthorityLevel.BOARD) {
                break;
            }
            Cell cell = byLevel.get(level);
            if (cell == null || !cell.allowed()) {
                continue;
            }
            if (cell.max() != null && (unlimitedBelow || (highestBelow != null && cell.max().compareTo(highestBelow) < 0))) {
                return Optional.of(level);
            }
            if (cell.max() == null) {
                unlimitedBelow = true;
            } else {
                highestBelow = cell.max();
            }
        }
        return Optional.empty();
    }

    private static Map<AuthorityLevel, Cell> byLevel(Collection<Cell> cells) {
        Map<AuthorityLevel, Cell> byLevel = new EnumMap<>(AuthorityLevel.class);
        cells.forEach(c -> byLevel.put(c.level(), c));
        return byLevel;
    }
}
