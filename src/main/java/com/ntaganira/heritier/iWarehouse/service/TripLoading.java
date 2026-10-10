package com.ntaganira.heritier.iWarehouse.service;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : TripLoading.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : Loading a vehicle (FLT-06): what a scanned label does to a trip's manifest (loads a planned unit; a unit
 *               not on the manifest is refused) and whether a load is within the vehicle's limits, pieces and kg. A load
 *               over either limit refuses the departure (AT-03: 40 units on a 35-unit vehicle). Pure, tested.
 * </pre>
 */
public final class TripLoading {

    private TripLoading() {
    }

    /** What scanning a label onto a manifest does. */
    public enum ScanOutcome {
        /** A planned unit, now confirmed on the vehicle. */
        LOADED,
        /** Scanned before: nothing changes. */
        ALREADY_LOADED,
        /** Not planned for this trip: refused (FLT-06). */
        NOT_ON_MANIFEST
    }

    /** Pieces and kg of units. */
    public record Load(int pieces, BigDecimal kg) {

        public static final Load EMPTY = new Load(0, BigDecimal.ZERO);

        public static Load of(Collection<BigDecimal> weightsKg) {
            BigDecimal kg = weightsKg.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            return new Load(weightsKg.size(), kg);
        }
    }

    /** A load against a vehicle's limits. */
    public record Check(Load load, int maxPieces, int maxKg) {

        public boolean overPieces() {
            return load.pieces() > maxPieces;
        }

        public boolean overKg() {
            return load.kg().compareTo(BigDecimal.valueOf(maxKg)) > 0;
        }

        public boolean within() {
            return !overPieces() && !overKg();
        }

        /** Pieces to take off to be within the limit (0 when within). */
        public int piecesOver() {
            return Math.max(0, load.pieces() - maxPieces);
        }

        /** Kg to take off to be within the limit (0 when within). */
        public BigDecimal kgOver() {
            return load.kg().subtract(BigDecimal.valueOf(maxKg)).max(BigDecimal.ZERO);
        }
    }

    public static Check check(Load load, int maxPieces, int maxKg) {
        return new Check(load, maxPieces, maxKg);
    }

    /** What a scanned code does to a manifest: code to "already loaded". */
    public static ScanOutcome scan(Map<String, Boolean> manifest, String code) {
        Boolean loaded = manifest.get(code);
        if (loaded == null) {
            return ScanOutcome.NOT_ON_MANIFEST;
        }
        return loaded ? ScanOutcome.ALREADY_LOADED : ScanOutcome.LOADED;
    }
}
