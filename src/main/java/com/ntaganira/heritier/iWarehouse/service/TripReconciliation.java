package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.enums.StockStatus;
import com.ntaganira.heritier.iWarehouse.enums.TripLineOutcome;

import java.util.*;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : TripReconciliation.java
 * - Date      : 2026. 10. 11.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The end of day of a trip (FLT-09, FLT-10), pure: expected closing stock = loaded - sold - breakage
 *               accepted, compared with the units scanned back. Each unit loaded is sold (sold from the vehicle), broken
 *               (its breakage accepted), returned (scanned back) or missing; a unit sold and scanned back anyway, and every
 *               label scanned that is no unit of the trip, is unexpected. A unit neither on the vehicle nor sold (moved by
 *               another document) cannot be reconciled: the trip waits.
 * </pre>
 */
public final class TripReconciliation {

    private TripReconciliation() {
    }

    /** A unit of the manifest: its status now, whether it was scanned back, whether its breakage was accepted. */
    public record Line(UUID unitId, String code, StockStatus status, boolean scanned, boolean breakageAccepted) {
    }

    /** A label scanned at the return that is no unit of the manifest (the unit it is, when it is one). */
    public record Extra(String code, UUID unitId) {
    }

    /** A unit scanned back that was not expected: sold yet back on board, or not on the manifest. */
    public record Unexpected(String code, UUID unitId, boolean sold) {
    }

    public record Result(Map<UUID, TripLineOutcome> outcomes, List<Line> missing, List<Unexpected> unexpected, List<String> elsewhere) {

        public long count(TripLineOutcome outcome) {
            return outcomes.values().stream().filter(o -> o == outcome).count();
        }

        public long countSold() {
            return count(TripLineOutcome.SOLD);
        }

        public long countBroken() {
            return count(TripLineOutcome.BROKEN);
        }

        public long countReturned() {
            return count(TripLineOutcome.RETURNED);
        }

        public long countMissing() {
            return count(TripLineOutcome.MISSING);
        }

        /** Expected closing stock (FLT-09): loaded less sold less broken. */
        public long expectedBack() {
            return outcomes.size() - count(TripLineOutcome.SOLD) - count(TripLineOutcome.BROKEN);
        }

        /** Any difference opens an audit case (FLT-10). */
        public boolean hasDifference() {
            return !missing.isEmpty() || !unexpected.isEmpty();
        }

        public boolean canReconcile() {
            return elsewhere.isEmpty();
        }
    }

    public static Result reconcile(List<Line> lines, List<Extra> extras) {
        Map<UUID, TripLineOutcome> outcomes = new LinkedHashMap<>();
        List<Line> missing = new ArrayList<>();
        List<Unexpected> unexpected = new ArrayList<>();
        List<String> elsewhere = new ArrayList<>();
        for (Line line : lines) {
            if (line.status() == StockStatus.SOLD) {
                outcomes.put(line.unitId(), TripLineOutcome.SOLD);
                if (line.scanned()) {
                    unexpected.add(new Unexpected(line.code(), line.unitId(), true));
                }
            } else if (line.status() != StockStatus.ON_VEHICLE) {
                elsewhere.add(line.code());
            } else if (line.breakageAccepted()) {
                outcomes.put(line.unitId(), TripLineOutcome.BROKEN);
            } else if (line.scanned()) {
                outcomes.put(line.unitId(), TripLineOutcome.RETURNED);
            } else {
                outcomes.put(line.unitId(), TripLineOutcome.MISSING);
                missing.add(line);
            }
        }
        for (Extra extra : extras) {
            unexpected.add(new Unexpected(extra.code(), extra.unitId(), false));
        }
        return new Result(outcomes, missing, unexpected, elsewhere);
    }
}
