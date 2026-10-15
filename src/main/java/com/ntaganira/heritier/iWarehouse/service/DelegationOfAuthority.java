package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.dto.AuthorityDto;
import com.ntaganira.heritier.iWarehouse.entity.AuthorityLimit;
import com.ntaganira.heritier.iWarehouse.enums.AuthorityDecision;
import com.ntaganira.heritier.iWarehouse.enums.AuthorityLevel;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.repository.AuthorityLimitRepository;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : DelegationOfAuthority.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : The Delegation of Authority (Board Paper 13.1, Res. 16, Annex E). Every approval of a decision it covers
 *               calls {@link #require} after its own checks (the right to approve, never the requester): the approver's
 *               office must reach the one the amount needs (AuthorityTiers), and a decision only the Board may take is
 *               recorded with its resolution in the note. Pages show what a request needs with {@link #check}. The limits
 *               change only on the Settings page (PERM_MANAGE_AUTHORITY), each cell audited.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class DelegationOfAuthority {

    /** The offices whose cells can be changed: the Board's always allows, without limit. */
    public static final List<AuthorityLevel> EDITABLE = List.of(AuthorityLevel.DIRECTOR, AuthorityLevel.COO, AuthorityLevel.MD);

    private final AuthorityLimitRepository repo;

    public DelegationOfAuthority(AuthorityLimitRepository repo) {
        this.repo = repo;
    }

    /** What a request needs and whether the signed-in user may decide it, for its page. */
    public record Check(AuthorityDecision decision, BigDecimal amount, AuthorityLevel required, boolean allowed) {

        public boolean isBoard() {
            return required == AuthorityLevel.BOARD;
        }
    }

    /** The lowest office that may approve this decision for this amount (RWF, or % for a discount). */
    public AuthorityLevel required(AuthorityDecision decision, BigDecimal amount) {
        return AuthorityTiers.required(cells(repo.findByDecision(decision)), amount);
    }

    /** The highest office the signed-in user holds. */
    public Optional<AuthorityLevel> currentLevel() {
        AuthorityLevel best = null;
        for (AuthorityLevel level : AuthorityLevel.values()) {
            if (AppUserPrincipal.currentHas(level.permission())) {
                best = level;
            }
        }
        return Optional.ofNullable(best);
    }

    public Check check(AuthorityDecision decision, BigDecimal amount) {
        AuthorityLevel required = required(decision, amount);
        return new Check(decision, amount, required, AuthorityTiers.mayDecide(currentLevel(), required));
    }

    /**
     * Refuses the approval when the signed-in user's office is below what the amount needs, or when only the Board may
     * take it and the note does not give the resolution.
     */
    public AuthorityLevel require(AuthorityDecision decision, BigDecimal amount, String note) {
        AuthorityLevel required = required(decision, amount);
        if (!AuthorityTiers.mayDecide(currentLevel(), required)) {
            throw BusinessException.of("authority.tooLow", label(required), display(decision, amount));
        }
        if (required == AuthorityLevel.BOARD && !StringUtils.hasText(note)) {
            throw BusinessException.onField("note", "authority.boardResolution", display(decision, amount));
        }
        return required;
    }

    /** An office's name in the reader's language, as a message argument. */
    public static DefaultMessageSourceResolvable label(AuthorityLevel level) {
        return new DefaultMessageSourceResolvable(new String[]{"authority.level." + level.name()}, level.name());
    }

    /** The amount as the message shows it: "12.50 %" for a discount, "1,250,000 RWF" otherwise. */
    static String display(AuthorityDecision decision, BigDecimal amount) {
        BigDecimal value = amount == null ? BigDecimal.ZERO : amount;
        return decision.isPercent()
                ? value.setScale(2, RoundingMode.HALF_UP).toPlainString() + " %"
                : String.format(Locale.ROOT, "%,d RWF", value.setScale(0, RoundingMode.HALF_UP).longValueExact());
    }

    // ---------------------------------------------------------------- the table and its form

    /** Every decision's cells, by decision then office, in the enums' order. */
    public Map<AuthorityDecision, Map<AuthorityLevel, AuthorityLimit>> table() {
        Map<AuthorityDecision, Map<AuthorityLevel, AuthorityLimit>> table = new EnumMap<>(AuthorityDecision.class);
        for (AuthorityLimit l : repo.findAll()) {
            table.computeIfAbsent(l.getDecision(), d -> new EnumMap<>(AuthorityLevel.class)).put(l.getLevel(), l);
        }
        return table;
    }

    /** The form with the cells as they are. */
    public AuthorityDto form() {
        AuthorityDto dto = new AuthorityDto();
        Map<AuthorityDecision, Map<AuthorityLevel, AuthorityLimit>> table = table();
        for (AuthorityDecision decision : AuthorityDecision.values()) {
            AuthorityDto.Row row = new AuthorityDto.Row();
            row.setDecision(decision);
            for (AuthorityLevel level : EDITABLE) {
                AuthorityLimit cell = table.getOrDefault(decision, Map.of()).get(level);
                AuthorityDto.Cell c = new AuthorityDto.Cell();
                c.setLevel(level);
                c.setAllowed(cell != null && cell.isAllowed());
                c.setMax(cell == null || cell.getMaxAmount() == null ? null : plain(cell.getMaxAmount()));
                row.getCells().add(c);
            }
            dto.getRows().add(row);
        }
        return dto;
    }

    /**
     * Saves the cells that changed and returns one line per change for the activity log ("DISCOUNT COO: 7 -> 10").
     * A limit is a positive amount (a percentage up to 100 for a discount); none means no limit; an office not allowed has
     * none. A higher office's limit is never below a lower one's.
     */
    @Transactional
    public List<String> update(AuthorityDto form) {
        Map<AuthorityDecision, Map<AuthorityLevel, AuthorityLimit>> table = table();
        List<String> changes = new ArrayList<>();
        for (int i = 0; i < form.getRows().size(); i++) {
            AuthorityDto.Row row = form.getRows().get(i);
            if (row.getDecision() == null) {
                continue;
            }
            Map<AuthorityLevel, AuthorityLimit> cells = table.get(row.getDecision());
            if (cells == null) {
                continue;
            }
            List<AuthorityTiers.Cell> wanted = new ArrayList<>();
            for (int j = 0; j < row.getCells().size(); j++) {
                AuthorityDto.Cell c = row.getCells().get(j);
                if (c.getLevel() == null || !EDITABLE.contains(c.getLevel())) {
                    continue;
                }
                BigDecimal max = c.isAllowed() ? c.getMax() : null;
                String field = "rows[" + i + "].cells[" + j + "].max";
                if (max != null && max.signum() <= 0) {
                    throw BusinessException.onField(field, "authority.max.positive");
                }
                if (max != null && row.getDecision().isPercent() && max.compareTo(BigDecimal.valueOf(100)) > 0) {
                    throw BusinessException.onField(field, "authority.max.percent");
                }
                if (max != null && max.stripTrailingZeros().scale() > 2) {
                    throw BusinessException.onField(field, "authority.max.decimals");
                }
                wanted.add(new AuthorityTiers.Cell(c.getLevel(), c.isAllowed(), max));
            }
            wanted.add(new AuthorityTiers.Cell(AuthorityLevel.BOARD, true, null));
            Optional<AuthorityLevel> wrong = AuthorityTiers.inconsistent(wanted);
            if (wrong.isPresent()) {
                int j = EDITABLE.indexOf(wrong.get());
                throw BusinessException.onField("rows[" + i + "].cells[" + j + "].max", "authority.inconsistent", label(wrong.get()));
            }
            for (AuthorityTiers.Cell w : wanted) {
                AuthorityLimit cell = cells.get(w.level());
                if (cell == null || w.level() == AuthorityLevel.BOARD) {
                    continue;
                }
                boolean same = cell.isAllowed() == w.allowed() && Objects.equals(norm(cell.getMaxAmount()), norm(w.max()));
                if (!same) {
                    changes.add(row.getDecision() + " " + w.level() + ": " + text(cell.isAllowed(), cell.getMaxAmount())
                            + " -> " + text(w.allowed(), w.max()));
                    cell.setAllowed(w.allowed());
                    cell.setMaxAmount(w.max() == null ? null : w.max().setScale(2, RoundingMode.HALF_UP));
                }
            }
        }
        return changes;
    }

    private static List<AuthorityTiers.Cell> cells(List<AuthorityLimit> limits) {
        return limits.stream().map(l -> new AuthorityTiers.Cell(l.getLevel(), l.isAllowed(), l.getMaxAmount())).toList();
    }

    /** 5000000.00 as 5000000 and 7.50 as 7.5 in the form's inputs. */
    private static BigDecimal plain(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    private static BigDecimal norm(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros();
    }

    private static String text(boolean allowed, BigDecimal max) {
        return !allowed ? "not allowed" : max == null ? "no limit" : max.stripTrailingZeros().toPlainString();
    }
}
