package com.ntaganira.heritier.iWarehouse.service;

import java.math.BigDecimal;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : RackLoad.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Pieces and kg on a rack, its slots included, checked against the rack's limits (MD-03)
 *               before more glass is put on it. A missing limit means no limit.
 * </pre>
 */
public record RackLoad(long pieces, BigDecimal kg) {

    public static final RackLoad EMPTY = new RackLoad(0, BigDecimal.ZERO);

    public RackLoad plus(long morePieces, BigDecimal moreKg) {
        return new RackLoad(pieces + morePieces, kg.add(moreKg));
    }

    public boolean exceedsPieces(Integer maxPieces) {
        return maxPieces != null && pieces > maxPieces;
    }

    public boolean exceedsKg(Integer maxKg) {
        return maxKg != null && kg.compareTo(BigDecimal.valueOf(maxKg)) > 0;
    }
}
