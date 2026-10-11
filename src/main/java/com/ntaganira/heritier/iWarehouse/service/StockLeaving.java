package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.Product;
import com.ntaganira.heritier.iWarehouse.entity.StockUnit;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : StockLeaving.java
 * - Date      : 2026. 10. 11.
 * - User      : Hntaganira
 * - Desc      : Units leaving stock at their own cost (broken on the road, lost from a vehicle), as a write-off does
 *               (StockAdjustmentService): the m² and value each glass loses, their cost for the journal, then each glass's
 *               MAC moves with Costing.afterStockChange. The caller locked the products and took the m² held before.
 * </pre>
 */
final class StockLeaving {

    private final Map<UUID, BigDecimal> area = new HashMap<>();
    private final Map<UUID, BigDecimal> value = new HashMap<>();
    private BigDecimal cost = BigDecimal.ZERO;

    void add(StockUnit unit) {
        UUID productId = unit.getProduct().getId();
        BigDecimal unitCost = unit.getUnitCost() == null ? BigDecimal.ZERO : unit.getUnitCost();
        area.merge(productId, unit.getAreaM2().negate(), BigDecimal::add);
        value.merge(productId, unitCost.negate(), BigDecimal::add);
        cost = cost.add(unitCost);
    }

    boolean isEmpty() {
        return area.isEmpty();
    }

    /** What the units cost: the amount the journal expenses or charges. */
    BigDecimal cost() {
        return cost;
    }

    void moveMacs(Map<UUID, Product> products, Map<UUID, BigDecimal> heldBefore) {
        for (Map.Entry<UUID, BigDecimal> e : area.entrySet()) {
            Product product = products.get(e.getKey());
            BigDecimal mac = Costing.afterStockChange(heldBefore.get(e.getKey()), product.getMacPerM2(), e.getValue(), value.get(e.getKey()));
            if (mac != null && mac.signum() < 0) {
                throw BusinessException.of("adjustment.negativeMac", product.getCode());
            }
            product.setMacPerM2(mac);
        }
    }
}
