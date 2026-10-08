package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.enums.CountOutcome;
import com.ntaganira.heritier.iWarehouse.enums.StockStatus;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : StockCounting.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : The comparison behind a stock count (INV-08), kept free of the database so it can be
 *               tested on its own. The units expected on the counted places are compared with the labels
 *               scanned there: matched, misplaced (scanned on another counted place), missing (not
 *               scanned), and the extras (a lost unit found, a unit recorded as being cut, on a vehicle or
 *               gone, an unknown label).
 * </pre>
 */
public final class StockCounting {

    /** States in which a unit stands on a rack or slot: what a count expects to find there. */
    public static final Set<StockStatus> ON_RACK = EnumSet.of(StockStatus.RECEIVED, StockStatus.AVAILABLE, StockStatus.RESERVED);

    private StockCounting() {
    }

    /** A unit as the records have it. */
    public record Unit(UUID id, String code, StockStatus status, UUID locationId) {
    }

    /** A label scanned: its code, the unit that has it (none when unknown) and where it was found. */
    public record Scan(String code, Unit unit, UUID foundAt) {
    }

    /** One line of the result: expectedAt is where the records place the unit, foundAt where it was scanned. */
    public record Line(CountOutcome outcome, String code, Unit unit, UUID expectedAt, UUID foundAt) {
    }

    /** The totals of a count: SRS INV-08 reports missing, extra and misplaced units. */
    public record Totals(int expected, int counted, int matched, int missing, int misplaced, int extra) {
    }

    /** What one scan shows, before the count closes (the message after each scan). */
    public static CountOutcome outcomeOf(Unit unit, UUID foundAt) {
        if (unit == null) {
            return CountOutcome.UNKNOWN;
        }
        if (ON_RACK.contains(unit.status())) {
            return Objects.equals(unit.locationId(), foundAt) ? CountOutcome.MATCHED : CountOutcome.MISPLACED;
        }
        return switch (unit.status()) {
            case LOST -> CountOutcome.FOUND_LOST;
            case IN_CUTTING, ON_VEHICLE -> CountOutcome.ELSEWHERE;
            default -> CountOutcome.NOT_IN_STOCK;
        };
    }

    /**
     * Compares the units expected on the counted places with the labels scanned. Every scan gives a line,
     * and every expected unit nobody scanned gives a MISSING line. Lines come in the order of
     * {@link CountOutcome} (matched first), then by code.
     */
    public static List<Line> compare(Collection<Unit> expected, Collection<Scan> scans) {
        List<Line> lines = new ArrayList<>();
        Set<UUID> scanned = new HashSet<>();
        for (Scan scan : scans) {
            Unit unit = scan.unit();
            if (unit != null) {
                scanned.add(unit.id());
            }
            CountOutcome outcome = outcomeOf(unit, scan.foundAt());
            UUID expectedAt = unit != null && ON_RACK.contains(unit.status()) ? unit.locationId() : null;
            lines.add(new Line(outcome, scan.code(), unit, expectedAt, scan.foundAt()));
        }
        for (Unit unit : expected) {
            if (!scanned.contains(unit.id())) {
                lines.add(new Line(CountOutcome.MISSING, unit.code(), unit, unit.locationId(), null));
            }
        }
        lines.sort(Comparator.comparing(Line::outcome).thenComparing(Line::code));
        return lines;
    }

    /** Totals of a comparison: expected = units the records place on the counted places, counted = labels scanned. */
    public static Totals totals(int expected, int counted, List<Line> lines) {
        int matched = 0;
        int missing = 0;
        int misplaced = 0;
        int extra = 0;
        for (Line line : lines) {
            switch (line.outcome()) {
                case MATCHED -> matched++;
                case MISSING -> missing++;
                case MISPLACED -> misplaced++;
                default -> extra++;
            }
        }
        return new Totals(expected, counted, matched, missing, misplaced, extra);
    }
}
