package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.enums.AccountKey;
import com.ntaganira.heritier.iWarehouse.enums.JournalSource;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : Journal.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : A journal being built by a posting rule (ACC-04), before JournalService saves it. Amounts are
 *               RWF with 2 decimals (ACC-01); a positive amount debits, a negative one credits. Stock lines
 *               are the change of each glass's stock value, so the inventory account follows the stock
 *               valuation exactly (AT-10); what is left over (moving average rounding) is put on one account
 *               so the journal balances. Lines on the same account, glass and supplier are added together.
 *               Pure: no Spring, unit-tested.
 * </pre>
 */
public final class Journal {

    public static final int SCALE = 2;

    /** A foreign document's currency, amount and the rate it was posted at (ACC-01). */
    public record Fx(String currencyCode, BigDecimal amount, BigDecimal rate) {
    }

    /** One line: debit or credit (one of them zero), with what it is about. */
    public record Line(AccountKey account, BigDecimal debit, BigDecimal credit, UUID productId, UUID supplierId, String memo,
                       Fx fx) {

        /** Debit less credit. */
        public BigDecimal signed() {
            return debit.subtract(credit);
        }
    }

    private record Key(AccountKey account, UUID productId, UUID supplierId, String memo) {
    }

    private final JournalSource source;
    private final UUID sourceId;
    private final String sourceNumber;
    private final LocalDate date;
    private final String description;
    private final Map<Key, BigDecimal> merged = new LinkedHashMap<>();
    private final List<Line> fxLines = new ArrayList<>();
    private final List<Object> order = new ArrayList<>();

    private Journal(JournalSource source, UUID sourceId, String sourceNumber, LocalDate date, String description) {
        this.source = Objects.requireNonNull(source);
        this.sourceId = sourceId;
        this.sourceNumber = sourceNumber;
        this.date = Objects.requireNonNull(date);
        this.description = Objects.requireNonNull(description);
    }

    public static Journal of(JournalSource source, UUID sourceId, String sourceNumber, LocalDate date, String description) {
        return new Journal(source, sourceId, sourceNumber, date, description);
    }

    /** Debits the amount (a negative amount credits it). Zero or null adds nothing. */
    public Journal debit(AccountKey account, BigDecimal amount) {
        return add(account, amount, null, null, null, null);
    }

    /** Credits the amount (a negative amount debits it). */
    public Journal credit(AccountKey account, BigDecimal amount) {
        return amount == null ? this : debit(account, amount.negate());
    }

    /**
     * Adds a signed amount (positive debits) with what it is about. A line with a foreign amount stays on its
     * own; the others are added to the line of the same account, glass, supplier and memo.
     */
    public Journal add(AccountKey account, BigDecimal signedAmount, UUID productId, UUID supplierId, String memo, Fx fx) {
        Objects.requireNonNull(account);
        if (signedAmount == null || signedAmount.signum() == 0) {
            return this;
        }
        BigDecimal amount = signedAmount.setScale(SCALE, RoundingMode.HALF_UP);
        if (fx != null) {
            Line line = line(account, amount, productId, supplierId, memo, fx);
            fxLines.add(line);
            order.add(line);
            return this;
        }
        Key key = new Key(account, productId, supplierId, memo);
        if (!merged.containsKey(key)) {
            order.add(key);
        }
        merged.merge(key, amount, BigDecimal::add);
        return this;
    }

    /**
     * Stock lines (AT-10): on the inventory account, each glass's value after less its value before. A glass
     * missing from one side counts as 0 there.
     */
    public Journal stockChange(Map<UUID, BigDecimal> before, Map<UUID, BigDecimal> after) {
        Set<UUID> glass = new LinkedHashSet<>(before.keySet());
        glass.addAll(after.keySet());
        for (UUID productId : glass) {
            BigDecimal change = after.getOrDefault(productId, BigDecimal.ZERO).subtract(before.getOrDefault(productId, BigDecimal.ZERO));
            add(AccountKey.INVENTORY, change, productId, null, null, null);
        }
        return this;
    }

    /** Puts what keeps the journal from balancing on one account (rounding of the moving average, revaluation). */
    public Journal balanceOn(AccountKey account) {
        return debit(account, credits().subtract(debits()));
    }

    /** The lines in the order they were added, those adding up to zero left out. */
    public List<Line> lines() {
        List<Line> lines = new ArrayList<>();
        for (Object o : order) {
            if (o instanceof Line line) {
                lines.add(line);
            } else {
                Key key = (Key) o;
                BigDecimal amount = merged.get(key);
                if (amount.signum() != 0) {
                    lines.add(line(key.account(), amount, key.productId(), key.supplierId(), key.memo(), null));
                }
            }
        }
        return lines;
    }

    public BigDecimal debits() {
        return lines().stream().map(Line::debit).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal credits() {
        return lines().stream().map(Line::credit).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public boolean isBalanced() {
        return debits().compareTo(credits()) == 0;
    }

    /** Nothing to post: no line, or every line adds up to zero. */
    public boolean isEmpty() {
        return lines().isEmpty();
    }

    public JournalSource source() {
        return source;
    }

    public UUID sourceId() {
        return sourceId;
    }

    public String sourceNumber() {
        return sourceNumber;
    }

    public LocalDate date() {
        return date;
    }

    public String description() {
        return description;
    }

    /**
     * Rounds amounts to 2 decimals so they add up to {@code total} exactly: each is rounded, and what is left
     * over goes to the largest one (bills whose RWF total was rounded once, CurrencyMath).
     */
    public static List<BigDecimal> roundTo(BigDecimal total, List<BigDecimal> amounts) {
        List<BigDecimal> rounded = new ArrayList<>();
        int largest = -1;
        for (int i = 0; i < amounts.size(); i++) {
            rounded.add(amounts.get(i).setScale(SCALE, RoundingMode.HALF_UP));
            if (largest < 0 || amounts.get(i).abs().compareTo(amounts.get(largest).abs()) > 0) {
                largest = i;
            }
        }
        if (largest >= 0) {
            BigDecimal rest = total.setScale(SCALE, RoundingMode.HALF_UP).subtract(rounded.stream().reduce(BigDecimal.ZERO, BigDecimal::add));
            rounded.set(largest, rounded.get(largest).add(rest));
        }
        return rounded;
    }

    private static Line line(AccountKey account, BigDecimal signed, UUID productId, UUID supplierId, String memo, Fx fx) {
        BigDecimal zero = BigDecimal.ZERO.setScale(SCALE);
        return signed.signum() > 0
                ? new Line(account, signed, zero, productId, supplierId, memo, fx)
                : new Line(account, zero, signed.negate(), productId, supplierId, memo, fx);
    }
}
