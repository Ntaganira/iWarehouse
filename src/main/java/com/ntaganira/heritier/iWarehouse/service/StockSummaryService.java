package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.Location;
import com.ntaganira.heritier.iWarehouse.entity.Product;
import com.ntaganira.heritier.iWarehouse.enums.StockStatus;
import com.ntaganira.heritier.iWarehouse.repository.ProductRepository;
import com.ntaganira.heritier.iWarehouse.repository.StockUnitRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : StockSummaryService.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Stock summary valued at MAC (INV-09) and reorder alerts (INV-10), from one grouped query
 *               of the units held.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class StockSummaryService {

    private final StockUnitRepository unitRepo;
    private final ProductRepository productRepo;
    private final StockService stockService;

    public StockSummaryService(StockUnitRepository unitRepo, ProductRepository productRepo, StockService stockService) {
        this.unitRepo = unitRepo;
        this.productRepo = productRepo;
        this.stockService = stockService;
    }

    /** The summary page: rows, total and products to reorder. */
    public record View(StockSummary.GroupBy groupBy, List<StockSummary.Row> rows, StockSummary.Row total,
                       List<StockSummary.Reorder> reorder) {
    }

    public View summary(StockSummary.GroupBy groupBy, UUID productId) {
        List<StockSummary.Fact> all = facts();
        List<StockSummary.Fact> facts = all.stream()
                .filter(f -> productId == null || productId.equals(f.productId()))
                .toList();
        List<Product> products = productRepo.findAll();
        Map<UUID, Product> byId = products.stream().collect(Collectors.toMap(Product::getId, Function.identity()));
        Map<UUID, Location> locations = stockService.locationsById();
        List<StockSummary.Row> rows = StockSummary.group(facts, groupBy, byId, locations);
        return new View(groupBy, rows, StockSummary.total(rows), StockSummary.reorder(all, products));
    }

    /** Products below their reorder level (dashboard, INV-10). */
    public List<StockSummary.Reorder> reorder() {
        return StockSummary.reorder(facts(), productRepo.findAll());
    }

    public List<Product> products() {
        return productRepo.findByEnabledTrueOrderByCodeAsc();
    }

    private List<StockSummary.Fact> facts() {
        return unitRepo.summarize(StockStatus.onHand()).stream()
                .map(r -> new StockSummary.Fact((UUID) r[0], (UUID) r[1], (StockStatus) r[2], ((Number) r[3]).longValue(),
                        (BigDecimal) r[4]))
                .toList();
    }
}
