package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.Location;
import com.ntaganira.heritier.iWarehouse.entity.Product;
import com.ntaganira.heritier.iWarehouse.enums.MovementType;
import com.ntaganira.heritier.iWarehouse.enums.SettingKey;
import com.ntaganira.heritier.iWarehouse.enums.StockStatus;
import com.ntaganira.heritier.iWarehouse.enums.UnitKind;
import com.ntaganira.heritier.iWarehouse.repository.ProductRepository;
import com.ntaganira.heritier.iWarehouse.repository.StockMovementRepository;
import com.ntaganira.heritier.iWarehouse.repository.StockUnitRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : StockReportService.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : The stock reports beside the summary (RPT-02): off-cut ageing (the off-cuts held, by age band and
 *               oldest first) and slow-moving stock (glass held longer than the Settings' days, with what it sold in
 *               them). Reads the units held in one projection query; the rules are StockAgeing's.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class StockReportService {

    private final StockUnitRepository unitRepo;
    private final StockMovementRepository movementRepo;
    private final ProductRepository productRepo;
    private final StockService stockService;
    private final SettingService settings;
    private final Clock clock;

    public StockReportService(StockUnitRepository unitRepo, StockMovementRepository movementRepo, ProductRepository productRepo,
                              StockService stockService, SettingService settings, Clock clock) {
        this.unitRepo = unitRepo;
        this.movementRepo = movementRepo;
        this.productRepo = productRepo;
        this.stockService = stockService;
        this.settings = settings;
        this.clock = clock;
    }

    /** Off-cuts by age band per glass (the total apart) and each off-cut, oldest first. */
    public record OffcutReport(List<StockAgeing.AgeRow> rows, StockAgeing.AgeRow total, List<StockAgeing.Unit> units) {
    }

    /** Glass held longer than {@code days}: per glass, the total, the share of all stock's value, and each unit, oldest first. */
    public record SlowReport(int days, List<StockAgeing.SlowRow> rows, StockAgeing.SlowRow total, BigDecimal stockValue,
                             List<StockAgeing.Unit> units) {

        /** Slow stock's value as a percentage of all stock's value (1 decimal), null without stock value. */
        public BigDecimal getSharePercent() {
            return stockValue.signum() == 0 ? null
                    : total.oldValue().multiply(BigDecimal.valueOf(100)).divide(stockValue, 1, RoundingMode.HALF_UP);
        }
    }

    public OffcutReport offcuts(UUID productId) {
        List<StockAgeing.Unit> units = held(productId).stream().filter(u -> u.kind() == UnitKind.OFFCUT).toList();
        List<StockAgeing.AgeRow> rows = new ArrayList<>(StockAgeing.offcuts(units, today()));
        StockAgeing.AgeRow total = rows.remove(rows.size() - 1);
        return new OffcutReport(rows, total, StockAgeing.oldestFirst(units));
    }

    public SlowReport slowMoving(int days, UUID productId) {
        LocalDate today = today();
        List<StockAgeing.Unit> units = held(productId);
        Map<UUID, StockAgeing.Sold> sold = new HashMap<>();
        for (Object[] r : movementRepo.movedByProduct(MovementType.SALE, today.minusDays(days).atStartOfDay())) {
            sold.put((UUID) r[0], new StockAgeing.Sold((BigDecimal) r[2], ((LocalDateTime) r[1]).toLocalDate()));
        }
        List<StockAgeing.SlowRow> rows = StockAgeing.slow(units, sold, today, days);
        BigDecimal stockValue = units.stream().collect(Collectors.groupingBy(u -> u.product().getId())).values().stream()
                .map(list -> {
                    Product p = list.get(0).product();
                    BigDecimal area = list.stream().map(StockAgeing.Unit::areaM2).reduce(BigDecimal.ZERO, BigDecimal::add);
                    return p.getMacPerM2() == null ? BigDecimal.ZERO : area.multiply(p.getMacPerM2()).setScale(Costing.MONEY_SCALE, RoundingMode.HALF_UP);
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new SlowReport(days, rows, StockAgeing.total(rows), stockValue, StockAgeing.olderThan(units, today, days));
    }

    /** Days after which glass held is slow-moving (Settings). */
    public int slowMovingDays() {
        return settings.getInt(SettingKey.SLOW_MOVING_DAYS);
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    /** The units held, of one glass or all. */
    private List<StockAgeing.Unit> held(UUID productId) {
        Map<UUID, Product> products = productRepo.findAll().stream().collect(Collectors.toMap(Product::getId, Function.identity()));
        Map<UUID, Location> locations = stockService.locationsById();
        List<StockAgeing.Unit> units = new ArrayList<>();
        for (Object[] r : unitRepo.held(StockStatus.onHand())) {
            UUID product = (UUID) r[2];
            if (productId != null && !productId.equals(product)) {
                continue;
            }
            units.add(new StockAgeing.Unit((UUID) r[0], (String) r[1], products.get(product), (UnitKind) r[3], (Integer) r[4],
                    (Integer) r[5], (BigDecimal) r[6], r[7] == null ? null : locations.get((UUID) r[7]), (StockStatus) r[8],
                    ((LocalDateTime) r[9]).toLocalDate()));
        }
        return units;
    }
}
