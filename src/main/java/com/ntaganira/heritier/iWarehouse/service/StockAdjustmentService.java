package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.enums.NotificationKind;
import com.ntaganira.heritier.iWarehouse.dto.StockAdjustmentDto;
import com.ntaganira.heritier.iWarehouse.entity.*;
import com.ntaganira.heritier.iWarehouse.enums.*;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.ProductRepository;
import com.ntaganira.heritier.iWarehouse.repository.StockAdjustmentRepository;
import com.ntaganira.heritier.iWarehouse.repository.StockUnitRepository;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : StockAdjustmentService.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Stock adjustments with a reason and approval (INV-07). Lines write units off (damaged:
 *               BROKEN, missing: LOST), find lost units again, add pieces nobody recorded (at MAC) or
 *               correct a unit's size (replaced at the same cost per m²). The value moved is compared with
 *               the ADJUSTMENT_APPROVAL_LIMIT setting: within it the adjustment posts at once, above it a
 *               second person approves or rejects it; meanwhile its units are held (INV-05). Posting
 *               locks the products, reads the stock held, changes the units through StockService and
 *               moves the MAC. Posted adjustments never change.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class StockAdjustmentService {

    private final StockAdjustmentRepository repo;
    private final StockUnitRepository unitRepo;
    private final ProductRepository productRepo;
    private final StockService stockService;
    private final PostingService postingService;
    private final DocumentNumberService numbers;
    private final SettingService settingService;
    private final Notifier notifier;
    private final Clock clock;

    public StockAdjustmentService(StockAdjustmentRepository repo, StockUnitRepository unitRepo, ProductRepository productRepo,
                                  StockService stockService, PostingService postingService, DocumentNumberService numbers,
                                  SettingService settingService, Notifier notifier, Clock clock) {
        this.notifier = notifier;
        this.repo = repo;
        this.unitRepo = unitRepo;
        this.productRepo = productRepo;
        this.stockService = stockService;
        this.postingService = postingService;
        this.numbers = numbers;
        this.settingService = settingService;
        this.clock = clock;
    }

    /** A form row checked: its unit, product and place, and the value it would move now. */
    record Prepared(StockAdjustmentDto.Line row, StockUnit unit, Product product, Location location, BigDecimal value) {
    }

    // ---------------------------------------------------------------- reading

    /** status: an AdjustmentStatus name or empty for all. */
    public Page<StockAdjustment> findPage(String search, String status, int page, int size) {
        Specification<StockAdjustment> spec = (root, query, cb) -> {
            Predicate p = cb.conjunction();
            if (StringUtils.hasText(search)) {
                String term = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                var sub = query.subquery(UUID.class);
                var line = sub.from(StockAdjustmentLine.class);
                sub.select(line.get("adjustment").get("id")).where(cb.like(cb.lower(cb.coalesce(line.get("unitCode"), "")), term));
                p = cb.and(p, cb.or(
                        cb.like(cb.lower(root.get("number")), term),
                        cb.like(cb.lower(root.get("reason")), term),
                        cb.like(cb.lower(root.get("requestedBy")), term),
                        root.get("id").in(sub)));
            }
            if (StringUtils.hasText(status)) {
                try {
                    p = cb.and(p, cb.equal(root.get("status"), AdjustmentStatus.valueOf(status)));
                } catch (IllegalArgumentException e) {
                    // unknown status: no filter
                }
            }
            return p;
        };
        return repo.findAll(spec, PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "number"))));
    }

    public StockAdjustment findById(UUID id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException("StockAdjustment", id));
    }

    public StockAdjustment findDetailed(UUID id) {
        return repo.findDetailedById(id).orElseThrow(() -> new NotFoundException("StockAdjustment", id));
    }

    /** The units an adjustment names or made, by id, with product and location. */
    public Map<UUID, StockUnit> unitsOf(StockAdjustment adjustment) {
        Set<UUID> ids = new HashSet<>();
        for (StockAdjustmentLine line : adjustment.getLines()) {
            if (line.getStockUnitId() != null) {
                ids.add(line.getStockUnitId());
            }
            if (line.getResultUnitId() != null) {
                ids.add(line.getResultUnitId());
            }
        }
        return ids.isEmpty() ? Map.of() : unitRepo.findByIdIn(ids).stream()
                .collect(Collectors.toMap(StockUnit::getId, Function.identity()));
    }

    public long pendingCount() {
        return repo.countByStatus(AdjustmentStatus.PENDING_APPROVAL);
    }

    /** RWF value an adjustment may move without approval. */
    public BigDecimal approvalLimit() {
        return settingService.getDecimal(SettingKey.ADJUSTMENT_APPROVAL_LIMIT);
    }

    /** A new form, with one row for a unit when started from its page. */
    public StockAdjustmentDto newForm(StockUnit unit, AdjustmentKind kind) {
        StockAdjustmentDto dto = new StockAdjustmentDto();
        StockAdjustmentDto.Line row = new StockAdjustmentDto.Line();
        row.setKind(kind == null ? AdjustmentKind.WRITE_OFF : kind);
        if (unit != null) {
            row.setUnitCode(unit.getCode());
            if (row.getKind() == AdjustmentKind.RESIZE) {
                row.setWidthMm(unit.getWidthMm());
                row.setHeightMm(unit.getHeightMm());
            }
        }
        dto.getLines().add(row);
        return dto;
    }

    public List<Product> products() {
        return productRepo.findByEnabledTrueOrderByCodeAsc();
    }

    public List<Location> places() {
        return stockService.storagePlaces(stockService.locationsById());
    }

    // ---------------------------------------------------------------- creating

    /** Checks the rows; posts within the approval limit, otherwise waits for approval (INV-07). */
    @Transactional
    public StockAdjustment create(StockAdjustmentDto dto) {
        if (dto.getLines().isEmpty()) {
            throw BusinessException.of("adjustment.lines.required");
        }
        List<Prepared> prepared = prepare(dto);
        StockAdjustment adjustment = new StockAdjustment();
        adjustment.setNumber(numbers.next(DocumentType.ADJUSTMENT));
        adjustment.setReason(dto.getReason().trim());
        Optional<AppUserPrincipal> user = AppUserPrincipal.current();
        adjustment.setRequestedBy(user.map(AppUserPrincipal::getUsername).orElse("system"));
        adjustment.setRequestedById(user.map(AppUserPrincipal::getId).orElse(null));
        int no = 0;
        for (Prepared p : prepared) {
            StockAdjustmentLine line = new StockAdjustmentLine();
            line.setAdjustment(adjustment);
            line.setLineNo(++no);
            line.setKind(p.row().getKind());
            line.setCause(p.row().getKind() == AdjustmentKind.WRITE_OFF ? p.row().getCause() : null);
            line.setStockUnitId(p.unit() == null ? null : p.unit().getId());
            line.setUnitCode(p.unit() == null ? null : p.unit().getCode());
            line.setProduct(p.row().getKind() == AdjustmentKind.NEW_UNIT ? p.product() : null);
            line.setUnitKind(p.row().getKind() == AdjustmentKind.NEW_UNIT ? p.row().getUnitKind() : null);
            line.setWidthMm(p.row().getKind().needsSize() ? p.row().getWidthMm() : null);
            line.setHeightMm(p.row().getKind().needsSize() ? p.row().getHeightMm() : null);
            line.setLocation(p.row().getKind().needsLocation() ? p.location() : null);
            line.setValueChange(p.value());
            adjustment.getLines().add(line);
        }
        totals(adjustment);
        // Saved as pending; posting sets POSTED and the time together (chk_stock_adjustments_posted), so a flush
        // during posting never sees a posted adjustment without its time.
        adjustment.setStatus(AdjustmentStatus.PENDING_APPROVAL);
        repo.save(adjustment);
        if (adjustment.getValueMoved().compareTo(approvalLimit()) <= 0) {
            post(adjustment);
        } else {
            notifier.holders("APPROVE_STOCK_ADJUSTMENT", adjustment.getRequestedById(), NotificationKind.APPROVAL, "notify.adjustment.waiting",
                    "notify.adjustment.waitingText", "/stock-adjustments/" + adjustment.getId(), adjustment.getNumber(), adjustment.getRequestedBy(),
                    adjustment.getReason());
        }
        return adjustment;
    }

    /** Checks each row and works out the value it moves now. Errors name the row's field. */
    List<Prepared> prepare(StockAdjustmentDto dto) {
        List<String> codes = dto.getLines().stream().map(StockAdjustmentDto.Line::getUnitCode)
                .filter(StringUtils::hasText).map(c -> c.trim().toUpperCase(Locale.ROOT)).distinct().toList();
        Map<String, StockUnit> units = codes.isEmpty() ? Map.of() : unitRepo.findByCodeIn(codes).stream()
                .collect(Collectors.toMap(StockUnit::getCode, Function.identity()));
        Map<UUID, String> holds = stockService.holds(units.values().stream().map(StockUnit::getId).toList());
        Map<UUID, Location> byId = stockService.locationsById();
        Map<UUID, Location> places = stockService.storagePlaces(byId).stream()
                .collect(Collectors.toMap(Location::getId, Function.identity()));
        Set<String> seen = new HashSet<>();
        List<Prepared> prepared = new ArrayList<>();
        for (int i = 0; i < dto.getLines().size(); i++) {
            StockAdjustmentDto.Line row = dto.getLines().get(i);
            String f = "lines[" + i + "].";
            AdjustmentKind kind = row.getKind();
            StockUnit unit = null;
            if (kind.needsUnit()) {
                if (!StringUtils.hasText(row.getUnitCode())) {
                    throw BusinessException.onField(f + "unitCode", "adjustment.unitCode.required");
                }
                String code = row.getUnitCode().trim().toUpperCase(Locale.ROOT);
                unit = units.get(code);
                if (unit == null) {
                    throw BusinessException.onField(f + "unitCode", "adjustment.unitCode.unknown", code);
                }
                if (!seen.add(code)) {
                    throw BusinessException.onField(f + "unitCode", "adjustment.unitCode.twice", code);
                }
                if (holds.containsKey(unit.getId())) {
                    throw BusinessException.onField(f + "unitCode", "stock.held", code, holds.get(unit.getId()));
                }
                StockAction action = kind == AdjustmentKind.FOUND ? StockAction.FIND : StockAction.ADJUST;
                if (!action.allows(unit.getStatus())) {
                    throw BusinessException.onField(f + "unitCode", "stock.notAllowed." + action.name(), code);
                }
            }
            Location location = null;
            if (kind.needsLocation()) {
                location = row.getLocationId() == null ? null : places.get(row.getLocationId());
                if (location == null) {
                    throw BusinessException.onField(f + "locationId", "adjustment.location.required");
                }
            }
            Product product = null;
            switch (kind) {
                case WRITE_OFF -> {
                    if (row.getCause() == null) {
                        throw BusinessException.onField(f + "cause", "adjustment.cause.required");
                    }
                }
                case FOUND -> refuseSheetOnOffcutRack(unit.getKind(), location, byId, f);
                case NEW_UNIT -> {
                    product = row.getProductId() == null ? null : productRepo.findById(row.getProductId()).orElse(null);
                    if (product == null || !product.isEnabled()) {
                        throw BusinessException.onField(f + "productId", "adjustment.product.required");
                    }
                    if (row.getUnitKind() == null) {
                        throw BusinessException.onField(f + "unitKind", "adjustment.unitKind.required");
                    }
                    requireSize(row, f);
                    if (product.getMacPerM2() == null) {
                        throw BusinessException.onField(f + "productId", "adjustment.noMac", product.getCode());
                    }
                    refuseSheetOnOffcutRack(row.getUnitKind(), location, byId, f);
                }
                case RESIZE -> {
                    requireSize(row, f);
                    if (row.getWidthMm() == unit.getWidthMm() && row.getHeightMm() == unit.getHeightMm()) {
                        throw BusinessException.onField(f + "widthMm", "adjustment.sameSize", unit.getCode());
                    }
                }
            }
            prepared.add(new Prepared(row, unit, product, location, value(kind, unit, product, row.getWidthMm(), row.getHeightMm())));
        }
        return prepared;
    }

    private static void requireSize(StockAdjustmentDto.Line row, String f) {
        if (row.getWidthMm() == null || row.getHeightMm() == null) {
            throw BusinessException.onField(f + "widthMm", "adjustment.size.required");
        }
    }

    private static void refuseSheetOnOffcutRack(UnitKind kind, Location location, Map<UUID, Location> byId, String f) {
        if (sheetOnOffcutRack(kind, location, byId)) {
            throw BusinessException.onField(f + "locationId", "adjustment.sheetToOffcut");
        }
    }

    /** A full sheet would stand on an off-cut rack (refused: off-cut racks hold off-cuts only). */
    public static boolean sheetOnOffcutRack(UnitKind kind, Location location, Map<UUID, Location> byId) {
        Location rack = StockService.rackOf(location, byId);
        return kind == UnitKind.SHEET && rack != null && rack.isOffcut();
    }

    /**
     * RWF a line changes the stock value by: a write-off loses the unit's cost, a unit found brings it back,
     * a new unit is worth MAC x its area, a resize keeps the cost per m² for the new area.
     */
    static BigDecimal value(AdjustmentKind kind, StockUnit unit, Product product, Integer widthMm, Integer heightMm) {
        return switch (kind) {
            case WRITE_OFF -> unit.getUnitCost().negate();
            case FOUND -> unit.getUnitCost();
            case NEW_UNIT -> Costing.unitCost(Pricing.areaM2(widthMm, heightMm), product.getMacPerM2());
            case RESIZE -> resizedCost(unit, widthMm, heightMm).subtract(unit.getUnitCost());
        };
    }

    /** The cost of a unit at a corrected size: the same cost per m², 2 decimals. */
    static BigDecimal resizedCost(StockUnit unit, int widthMm, int heightMm) {
        return unit.getUnitCost().multiply(Pricing.areaM2(widthMm, heightMm))
                .divide(unit.getAreaM2(), Costing.MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static void totals(StockAdjustment adjustment) {
        BigDecimal change = BigDecimal.ZERO;
        BigDecimal moved = BigDecimal.ZERO;
        for (StockAdjustmentLine line : adjustment.getLines()) {
            change = change.add(line.getValueChange());
            moved = moved.add(line.getValueChange().abs());
        }
        adjustment.setValueChange(change);
        adjustment.setValueMoved(moved);
    }

    // ---------------------------------------------------------------- deciding

    /** Another person approves: the adjustment posts (INV-07). */
    @Transactional
    public StockAdjustment approve(UUID id, String note) {
        repo.lockById(id).orElseThrow(() -> new NotFoundException("StockAdjustment", id));
        StockAdjustment adjustment = findDetailed(id);
        requirePending(adjustment);
        if (isRequester(adjustment)) {
            throw BusinessException.of("adjustment.ownApproval", adjustment.getNumber());
        }
        decide(adjustment, AdjustmentStatus.PENDING_APPROVAL, PartyRules.clean(note));
        post(adjustment);
        notifier.user(adjustment.getRequestedById(), NotificationKind.DECISION, "notify.adjustment.approved", "notify.decidedBy",
                "/stock-adjustments/" + adjustment.getId(), adjustment.getNumber(), adjustment.getDecidedBy());
        return adjustment;
    }

    /** Another person rejects it, with a reason: nothing changes in stock. */
    @Transactional
    public StockAdjustment reject(UUID id, String reason) {
        repo.lockById(id).orElseThrow(() -> new NotFoundException("StockAdjustment", id));
        StockAdjustment adjustment = findDetailed(id);
        requirePending(adjustment);
        if (isRequester(adjustment)) {
            throw BusinessException.of("adjustment.ownReject", adjustment.getNumber());
        }
        decide(adjustment, AdjustmentStatus.REJECTED, reason.trim());
        notifier.user(adjustment.getRequestedById(), NotificationKind.DECISION, "notify.adjustment.rejected", "notify.rejectedBy",
                "/stock-adjustments/" + adjustment.getId(), adjustment.getNumber(), adjustment.getDecidedBy(), adjustment.getDecisionNote());
        return adjustment;
    }

    /** The requester withdraws it, with a reason. */
    @Transactional
    public StockAdjustment cancel(UUID id, String reason) {
        repo.lockById(id).orElseThrow(() -> new NotFoundException("StockAdjustment", id));
        StockAdjustment adjustment = findDetailed(id);
        requirePending(adjustment);
        if (!isRequester(adjustment)) {
            throw BusinessException.of("adjustment.notYours", adjustment.getNumber(), adjustment.getRequestedBy());
        }
        decide(adjustment, AdjustmentStatus.CANCELLED, reason.trim());
        return adjustment;
    }

    private static void requirePending(StockAdjustment adjustment) {
        if (!adjustment.isPending()) {
            throw BusinessException.of("adjustment.notPending", adjustment.getNumber());
        }
    }

    private static boolean isRequester(StockAdjustment adjustment) {
        Optional<AppUserPrincipal> user = AppUserPrincipal.current();
        if (user.isPresent() && adjustment.getRequestedById() != null) {
            return adjustment.getRequestedById().equals(user.get().getId());
        }
        return adjustment.getRequestedBy().equals(user.map(AppUserPrincipal::getUsername).orElse("system"));
    }

    private void decide(StockAdjustment adjustment, AdjustmentStatus status, String note) {
        adjustment.setStatus(status);
        adjustment.setDecidedBy(AppUserPrincipal.current().map(AppUserPrincipal::getUsername).orElse("system"));
        adjustment.setDecidedAt(LocalDateTime.now(clock));
        adjustment.setDecisionNote(note);
    }

    // ---------------------------------------------------------------- posting

    /**
     * Changes the stock: locks the products, reads the m² held, applies each line through StockService
     * (checked again: a unit may have moved while the adjustment waited), fixes each line's value and moves
     * each product's MAC with the area and value that came in or left.
     */
    private void post(StockAdjustment adjustment) {
        Map<UUID, StockUnit> units = new HashMap<>();
        List<UUID> unitIds = adjustment.getLines().stream().map(StockAdjustmentLine::getStockUnitId).filter(Objects::nonNull).toList();
        if (!unitIds.isEmpty()) {
            unitRepo.findAllById(unitIds).forEach(u -> units.put(u.getId(), u));
        }
        Set<UUID> productIds = new TreeSet<>();
        units.values().forEach(u -> productIds.add(u.getProduct().getId()));
        adjustment.getLines().stream().filter(l -> l.getProduct() != null).forEach(l -> productIds.add(l.getProduct().getId()));
        Map<UUID, Product> products = productRepo.lockAllById(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        Map<UUID, BigDecimal> heldBefore = new HashMap<>();
        for (UUID productId : productIds) {
            heldBefore.put(productId, stockService.heldArea(productId));
        }
        PostingService.StockValues valueBefore = postingService.stockValues(products.values());
        stockService.requireNotHeld(units.values(), adjustment.getNumber());
        checkRacks(adjustment, units);

        BigDecimal density = settingService.getDecimal(SettingKey.GLASS_DENSITY);
        Map<UUID, BigDecimal> areaChange = new HashMap<>();
        Map<UUID, BigDecimal> valueChange = new HashMap<>();
        String reason = adjustment.getReason();
        for (StockAdjustmentLine line : adjustment.getLines()) {
            StockUnit unit = line.getStockUnitId() == null ? null : units.get(line.getStockUnitId());
            Product product = products.get(unit != null ? unit.getProduct().getId() : line.getProduct().getId());
            BigDecimal area;
            BigDecimal value;
            switch (line.getKind()) {
                case WRITE_OFF -> {
                    area = unit.getAreaM2().negate();
                    value = unit.getUnitCost().negate();
                    stockService.writeOff(unit, line.getCause(), reason, adjustment.getId(), adjustment.getNumber());
                }
                case FOUND -> {
                    area = unit.getAreaM2();
                    value = unit.getUnitCost();
                    stockService.find(unit, line.getLocation(), reason, adjustment.getId(), adjustment.getNumber());
                }
                case NEW_UNIT -> {
                    if (product.getMacPerM2() == null) {
                        throw BusinessException.of("adjustment.noMac", product.getCode());
                    }
                    area = Pricing.areaM2(line.getWidthMm(), line.getHeightMm());
                    value = Costing.unitCost(area, product.getMacPerM2());
                    StockUnit created = stockService.createAdjusted(new StockService.NewUnit(product, line.getUnitKind(), null,
                            null, line.getWidthMm(), line.getHeightMm(), weight(area, product, density), line.getLocation(), value,
                            StockStatus.AVAILABLE, null, null), reason, adjustment.getId(), adjustment.getNumber());
                    line.setResultUnitId(created.getId());
                }
                case RESIZE -> {
                    BigDecimal newArea = Pricing.areaM2(line.getWidthMm(), line.getHeightMm());
                    BigDecimal newCost = resizedCost(unit, line.getWidthMm(), line.getHeightMm());
                    area = newArea.subtract(unit.getAreaM2());
                    value = newCost.subtract(unit.getUnitCost());
                    Location place = unit.getLocation();
                    StockStatus status = unit.getStatus() == StockStatus.RESERVED ? StockStatus.RESERVED : StockStatus.AVAILABLE;
                    Customer customer = unit.getReservedCustomer();
                    String note = unit.getReservedNote();
                    stockService.replace(unit, reason, adjustment.getId(), adjustment.getNumber());
                    StockUnit created = stockService.createAdjusted(new StockService.NewUnit(product, unit.getKind(),
                            unit.getCrateBatch(), unit.getId(), line.getWidthMm(), line.getHeightMm(), weight(newArea, product, density),
                            place, newCost, status, customer, note), reason, adjustment.getId(), adjustment.getNumber());
                    line.setResultUnitId(created.getId());
                }
                default -> throw new IllegalStateException(line.getKind().name());
            }
            line.setValueChange(value);
            areaChange.merge(product.getId(), area, BigDecimal::add);
            valueChange.merge(product.getId(), value, BigDecimal::add);
        }
        for (UUID productId : productIds) {
            Product product = products.get(productId);
            BigDecimal mac = Costing.afterStockChange(heldBefore.get(productId), product.getMacPerM2(),
                    areaChange.getOrDefault(productId, BigDecimal.ZERO), valueChange.getOrDefault(productId, BigDecimal.ZERO));
            if (mac != null && mac.signum() < 0) {
                throw BusinessException.of("adjustment.negativeMac", product.getCode());
            }
            product.setMacPerM2(mac);
        }
        totals(adjustment);
        adjustment.setStatus(AdjustmentStatus.POSTED);
        adjustment.setPostedAt(LocalDateTime.now(clock));
        postingService.adjustment(adjustment, valueBefore);
    }

    private static BigDecimal weight(BigDecimal area, Product product, BigDecimal density) {
        return GlassProducts.weightKg(area, GlassProducts.weightPerM2(product.getThicknessMm(), density));
    }

    /** Units found or added must fit their racks' piece and weight limits (MD-03). */
    private void checkRacks(StockAdjustment adjustment, Map<UUID, StockUnit> units) {
        Map<UUID, Location> byId = stockService.locationsById();
        Map<UUID, RackLoad> loads = stockService.rackLoads(byId);
        Map<UUID, RackLoad> after = new LinkedHashMap<>();
        BigDecimal density = settingService.getDecimal(SettingKey.GLASS_DENSITY);
        for (StockAdjustmentLine line : adjustment.getLines()) {
            if (!line.getKind().needsLocation()) {
                continue;
            }
            Location rack = StockService.rackOf(byId.get(line.getLocation().getId()), byId);
            BigDecimal kg = line.getKind() == AdjustmentKind.FOUND ? units.get(line.getStockUnitId()).getWeightKg()
                    : weight(Pricing.areaM2(line.getWidthMm(), line.getHeightMm()), line.getProduct(), density);
            RackLoad base = after.getOrDefault(rack.getId(), loads.getOrDefault(rack.getId(), RackLoad.EMPTY));
            after.put(rack.getId(), base.plus(1, kg));
        }
        for (Map.Entry<UUID, RackLoad> e : after.entrySet()) {
            Location rack = byId.get(e.getKey());
            RackLoad now = loads.getOrDefault(rack.getId(), RackLoad.EMPTY);
            if (e.getValue().exceedsPieces(rack.getMaxPieces())) {
                throw BusinessException.of("receipt.rack.pieces", rack.getCode(), now.pieces(), rack.getMaxPieces(),
                        e.getValue().pieces() - now.pieces());
            }
            if (e.getValue().exceedsKg(rack.getMaxWeightKg())) {
                throw BusinessException.of("receipt.rack.weight", rack.getCode(), now.kg(), rack.getMaxWeightKg(),
                        e.getValue().kg().subtract(now.kg()));
            }
        }
    }
}
