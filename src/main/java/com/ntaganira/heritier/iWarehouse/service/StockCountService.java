package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.dto.StockAdjustmentDto;
import com.ntaganira.heritier.iWarehouse.dto.StockCountDto;
import com.ntaganira.heritier.iWarehouse.entity.Location;
import com.ntaganira.heritier.iWarehouse.entity.Product;
import com.ntaganira.heritier.iWarehouse.entity.StockAdjustment;
import com.ntaganira.heritier.iWarehouse.entity.StockCount;
import com.ntaganira.heritier.iWarehouse.entity.StockCountLine;
import com.ntaganira.heritier.iWarehouse.entity.StockCountScan;
import com.ntaganira.heritier.iWarehouse.entity.StockUnit;
import com.ntaganira.heritier.iWarehouse.enums.AdjustmentKind;
import com.ntaganira.heritier.iWarehouse.enums.CountAction;
import com.ntaganira.heritier.iWarehouse.enums.CountOutcome;
import com.ntaganira.heritier.iWarehouse.enums.DocumentType;
import com.ntaganira.heritier.iWarehouse.enums.LocationType;
import com.ntaganira.heritier.iWarehouse.enums.StockCountStatus;
import com.ntaganira.heritier.iWarehouse.enums.WriteOffCause;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.ProductRepository;
import com.ntaganira.heritier.iWarehouse.repository.StockCountLineRepository;
import com.ntaganira.heritier.iWarehouse.repository.StockCountRepository;
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

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : StockCountService.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Stock counts by scanning (INV-08). A count covers a place and every place under it,
 *               optionally one glass; while open, the units there are held (INV-05, StockService.holds).
 *               Labels are scanned where they are found. Closing compares the scans with the records
 *               (StockCounting), moves misplaced units to where they were found (COUNT movements), puts
 *               missing units (written off as LOST) and lost units found on one adjustment, approved as
 *               any other (INV-07), and keeps one result line per unit.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class StockCountService {

    /** Places a count can cover: the site (a full count), a zone, a rack or a slot (a cycle count). */
    private static final Set<LocationType> COUNTABLE = EnumSet.of(LocationType.SITE, LocationType.ZONE, LocationType.RACK, LocationType.SLOT);

    /** Longest label code kept (stock_count_scans.code). */
    private static final int CODE_MAX = 30;

    private final StockCountRepository repo;
    private final StockCountLineRepository lineRepo;
    private final StockUnitRepository unitRepo;
    private final ProductRepository productRepo;
    private final StockService stockService;
    private final StockAdjustmentService adjustmentService;
    private final DocumentNumberService numbers;
    private final Clock clock;

    public StockCountService(StockCountRepository repo, StockCountLineRepository lineRepo, StockUnitRepository unitRepo,
                             ProductRepository productRepo, StockService stockService, StockAdjustmentService adjustmentService,
                             DocumentNumberService numbers, Clock clock) {
        this.repo = repo;
        this.lineRepo = lineRepo;
        this.unitRepo = unitRepo;
        this.productRepo = productRepo;
        this.stockService = stockService;
        this.adjustmentService = adjustmentService;
        this.numbers = numbers;
        this.clock = clock;
    }

    /** What a scan showed: the outcome so far, the unit (none when unknown) and whether it was scanned before. */
    public record ScanResult(String code, CountOutcome outcome, StockUnit unit, boolean again, int scanned, Location place) {
    }

    /** A closed count and the racks its moves left over their piece or weight limit. */
    public record CloseResult(StockCount count, List<String> racksOverLimit) {
    }

    // ---------------------------------------------------------------- reading

    /** status: a StockCountStatus name or empty for all. */
    public Page<StockCount> findPage(String search, String status, int page, int size) {
        Specification<StockCount> spec = (root, query, cb) -> {
            Predicate p = cb.conjunction();
            if (StringUtils.hasText(search)) {
                String term = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                p = cb.and(p, cb.or(
                        cb.like(cb.lower(root.get("number")), term),
                        cb.like(cb.lower(cb.coalesce(root.get("note"), "")), term),
                        cb.like(cb.lower(cb.coalesce(root.get("startedBy"), "")), term),
                        cb.like(cb.lower(root.get("location").get("code")), term)));
            }
            if (StringUtils.hasText(status)) {
                try {
                    p = cb.and(p, cb.equal(root.get("status"), StockCountStatus.valueOf(status)));
                } catch (IllegalArgumentException e) {
                    // unknown status: no filter
                }
            }
            return p;
        };
        return repo.findAll(spec, PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "startedAt").and(Sort.by(Sort.Direction.DESC, "number"))));
    }

    public StockCount findDetailed(UUID id) {
        return repo.findDetailedById(id).orElseThrow(() -> new NotFoundException("StockCount", id));
    }

    public long openCount() {
        return repo.countByStatus(StockCountStatus.OPEN);
    }

    /** The result lines of a closed count, in order. */
    public List<StockCountLine> lines(UUID countId) {
        return lineRepo.findByCountIdOrderByLineNo(countId);
    }

    /** Places a count can cover: active sites, zones, racks and slots, by code. */
    public List<Location> countablePlaces(Map<UUID, Location> byId) {
        return byId.values().stream()
                .filter(Location::isEnabled)
                .filter(l -> COUNTABLE.contains(l.getType()))
                .sorted(Comparator.comparing(Location::getCode))
                .toList();
    }

    /** Glass a count can be limited to. */
    public List<Product> products() {
        return productRepo.findByEnabledTrueOrderByCodeAsc();
    }

    /** Racks and slots of a count where labels are scanned: its places that hold glass. */
    public List<Location> scanPlaces(StockCount count, Map<UUID, Location> byId) {
        return stockService.storagePlaces(byId).stream().filter(l -> count.getPlaces().contains(l.getId())).toList();
    }

    /** Units the count expects now: on its places, standing on a rack or slot, of its glass if it has one. */
    public List<StockUnit> expectedUnits(StockCount count) {
        if (count.getPlaces().isEmpty()) {
            return List.of();
        }
        UUID productId = count.getProduct() == null ? null : count.getProduct().getId();
        return unitRepo.findByLocation_IdInAndStatusIn(count.getPlaces(), StockCounting.ON_RACK).stream()
                .filter(u -> productId == null || productId.equals(u.getProduct().getId()))
                .sorted(Comparator.comparing(StockUnit::getCode))
                .toList();
    }

    /** The units of the scans, by code. */
    public Map<String, StockUnit> scannedUnits(StockCount count) {
        List<String> codes = count.getScans().stream().map(StockCountScan::getCode).toList();
        return codes.isEmpty() ? Map.of() : unitRepo.findByCodeIn(codes).stream()
                .collect(Collectors.toMap(StockUnit::getCode, Function.identity()));
    }

    /** The comparison as it stands (an open count's progress, or the result about to be recorded). */
    public List<StockCounting.Line> compare(StockCount count, List<StockUnit> expected, Map<String, StockUnit> scanned) {
        List<StockCounting.Scan> scans = count.getScans().stream()
                .map(s -> new StockCounting.Scan(s.getCode(), unit(scanned.get(s.getCode())), s.getLocationId()))
                .toList();
        return StockCounting.compare(expected.stream().map(StockCountService::unit).toList(), scans);
    }

    /** Units by id, with product and location (to show a closed count's lines). */
    public Map<UUID, StockUnit> unitsById(Collection<UUID> ids) {
        return ids.isEmpty() ? Map.of() : unitRepo.findByIdIn(ids).stream()
                .collect(Collectors.toMap(StockUnit::getId, Function.identity()));
    }

    private static StockCounting.Unit unit(StockUnit u) {
        return u == null ? null : new StockCounting.Unit(u.getId(), u.getCode(), u.getStatus(),
                u.getLocation() == null ? null : u.getLocation().getId());
    }

    // ---------------------------------------------------------------- starting

    /** Starts a count of a place and every place under it; refused where another open count already counts. */
    @Transactional
    public StockCount start(StockCountDto dto) {
        Map<UUID, Location> byId = stockService.locationsById();
        Location place = dto.getLocationId() == null ? null : byId.get(dto.getLocationId());
        if (place == null || !place.isEnabled() || !COUNTABLE.contains(place.getType())) {
            throw BusinessException.onField("locationId", "count.location.required");
        }
        Product product = null;
        if (dto.getProductId() != null) {
            product = productRepo.findById(dto.getProductId()).filter(Product::isEnabled)
                    .orElseThrow(() -> BusinessException.onField("productId", "count.product.unknown"));
        }
        Set<UUID> places = subtree(place.getId(), byId);
        List<String> overlapping = repo.findOpenCovering(places);
        if (!overlapping.isEmpty()) {
            throw BusinessException.onField("locationId", "count.overlap", String.join(", ", overlapping));
        }
        StockCount count = new StockCount();
        count.setNumber(numbers.next(DocumentType.STOCK_COUNT));
        count.setLocation(place);
        count.setProduct(product);
        count.setNote(StringUtils.hasText(dto.getNote()) ? dto.getNote().trim() : null);
        count.setStartedAt(LocalDateTime.now(clock));
        count.setStartedBy(username());
        count.setPlaces(places);
        count.setStatus(StockCountStatus.OPEN);
        return repo.save(count);
    }

    /** A place and every place under it (the site, its zones, racks and slots). */
    static Set<UUID> subtree(UUID root, Map<UUID, Location> byId) {
        Map<UUID, List<UUID>> children = new HashMap<>();
        for (Location l : byId.values()) {
            if (l.getParentId() != null) {
                children.computeIfAbsent(l.getParentId(), k -> new ArrayList<>()).add(l.getId());
            }
        }
        Set<UUID> places = new LinkedHashSet<>();
        Deque<UUID> todo = new ArrayDeque<>(List.of(root));
        while (!todo.isEmpty()) {
            UUID id = todo.pop();
            if (places.add(id)) {
                todo.addAll(children.getOrDefault(id, List.of()));
            }
        }
        return places;
    }

    // ---------------------------------------------------------------- scanning

    /**
     * Records labels found on a rack or slot of the count (a scanner sends one code, a paste several).
     * A rack or slot label (MD-02) says where the labels after it were found, so "WH-A-R02, U-WH-000041,
     * U-WH-000042" counts both units on WH-A-R02. A code scanned again on another place moves its scan
     * there; glass other than the count's, and places outside the count, are refused.
     */
    @Transactional
    public ScanResult scan(UUID countId, UUID locationId, String codesText) {
        StockCount count = lockOpen(countId);
        Map<UUID, Location> byId = stockService.locationsById();
        List<Location> places = scanPlaces(count, byId);
        Location place = locationId == null ? null : places.stream()
                .filter(l -> l.getId().equals(locationId)).findFirst().orElse(null);
        List<String> codes = StockTransferService.parseCodes(codesText);
        if (codes.isEmpty()) {
            throw place == null ? BusinessException.onField("locationId", "count.where.required")
                    : BusinessException.onField("codes", "count.codes.required");
        }
        for (String code : codes) {
            if (code.length() > CODE_MAX) {
                throw BusinessException.onField("codes", "count.code.tooLong", code.substring(0, CODE_MAX) + "…");
            }
        }
        Map<String, StockUnit> units = unitRepo.findByCodeIn(codes).stream()
                .collect(Collectors.toMap(StockUnit::getCode, Function.identity()));
        // Codes that are no unit's but a location's are place labels
        Map<String, Location> placeByCode = places.stream().collect(Collectors.toMap(Location::getCode, Function.identity()));
        Set<String> locationCodes = byId.values().stream().map(Location::getCode).collect(Collectors.toSet());
        for (String code : codes) {
            if (!units.containsKey(code) && locationCodes.contains(code) && !placeByCode.containsKey(code)) {
                throw BusinessException.onField("codes", "count.placeOutside", code, count.getLocation().getCode());
            }
        }
        Product glass = count.getProduct();
        for (String code : codes) {
            StockUnit unit = units.get(code);
            if (glass != null && unit != null && !glass.getId().equals(unit.getProduct().getId())) {
                throw BusinessException.onField("codes", "count.otherGlass", code, unit.getProduct().getCode(), glass.getCode());
            }
        }
        Map<String, StockCountScan> existing = count.getScans().stream()
                .collect(Collectors.toMap(StockCountScan::getCode, Function.identity()));
        LocalDateTime now = LocalDateTime.now(clock);
        boolean again = false;
        int scanned = 0;
        String last = null;
        for (String code : codes) {
            if (!units.containsKey(code) && placeByCode.containsKey(code)) {
                place = placeByCode.get(code);
                continue;
            }
            if (place == null) {
                throw BusinessException.onField("locationId", "count.where.required");
            }
            scanned++;
            last = code;
            StockCountScan scan = existing.get(code);
            if (scan != null) {
                again = true;
                if (!scan.getLocationId().equals(place.getId())) {
                    scan.setLocationId(place.getId());
                    scan.setScannedAt(now);
                    scan.setScannedBy(username());
                }
                continue;
            }
            scan = new StockCountScan();
            scan.setCount(count);
            scan.setCode(code);
            StockUnit unit = units.get(code);
            scan.setStockUnitId(unit == null ? null : unit.getId());
            scan.setLocationId(place.getId());
            scan.setScannedAt(now);
            scan.setScannedBy(username());
            count.getScans().add(scan);
            existing.put(code, scan);
        }
        if (last == null) {
            return new ScanResult(null, null, null, false, 0, place); // only a place label: counting there now
        }
        StockUnit lastUnit = units.get(last);
        return new ScanResult(last, StockCounting.outcomeOf(unit(lastUnit), place.getId()), lastUnit,
                again && scanned == 1, scanned, place);
    }

    /** Removes a scan made by mistake, while the count is open. */
    @Transactional
    public StockCountScan removeScan(UUID countId, UUID scanId) {
        StockCount count = lockOpen(countId);
        StockCountScan scan = count.getScans().stream().filter(s -> s.getId().equals(scanId)).findFirst()
                .orElseThrow(() -> new NotFoundException("StockCountScan", scanId));
        count.getScans().remove(scan);
        return scan;
    }

    // ---------------------------------------------------------------- closing

    /**
     * Records the result (INV-08). Misplaced units move to where they were found; missing units (written off as
     * LOST) and lost units found go on one adjustment with {@code adjustmentReason}, approved as any other;
     * units another document holds, and lost sheets found on an off-cut rack, are only reported.
     */
    @Transactional
    public CloseResult close(UUID countId, String adjustmentReason) {
        StockCount count = lockOpen(countId);
        if (count.getScans().isEmpty()) {
            throw BusinessException.of("count.close.noScans", count.getNumber());
        }
        Map<UUID, Location> byId = stockService.locationsById();
        List<StockUnit> expected = expectedUnits(count);
        Map<String, StockUnit> scanned = scannedUnits(count);
        List<StockCounting.Line> lines = compare(count, expected, scanned);
        Map<UUID, StockUnit> units = new HashMap<>();
        expected.forEach(u -> units.put(u.getId(), u));
        scanned.values().forEach(u -> units.put(u.getId(), u));
        // Holds by other documents (this count holds its own units until it closes)
        Map<UUID, String> holds = new HashMap<>(stockService.holds(units.keySet()));
        holds.values().removeIf(number -> number.equals(count.getNumber()));

        LocalDateTime now = LocalDateTime.now(clock);
        String user = username();
        List<Planned> planned = new ArrayList<>();
        StockAdjustmentDto adjustment = new StockAdjustmentDto();
        adjustment.setReason(adjustmentReason);
        Set<UUID> movedTo = new HashSet<>();
        for (StockCounting.Line line : lines) {
            StockUnit unit = line.unit() == null ? null : units.get(line.unit().id());
            String heldBy = unit == null ? null : holds.get(unit.getId());
            CountAction action = CountAction.NONE;
            String note = null;
            switch (line.outcome()) {
                case MISPLACED -> {
                    if (heldBy != null) {
                        note = heldBy;
                    } else {
                        stockService.countMove(unit, byId.get(line.foundAt()), count.getId(), count.getNumber());
                        movedTo.add(line.foundAt());
                        action = CountAction.MOVED;
                    }
                }
                case MISSING -> {
                    if (heldBy != null) {
                        note = heldBy;
                    } else {
                        adjustment.getLines().add(adjustmentLine(AdjustmentKind.WRITE_OFF, unit.getCode(), WriteOffCause.MISSING, null));
                        action = CountAction.ADJUSTMENT;
                    }
                }
                case FOUND_LOST -> {
                    Location found = byId.get(line.foundAt());
                    if (heldBy != null) {
                        note = heldBy;
                    } else if (StockAdjustmentService.sheetOnOffcutRack(unit.getKind(), found, byId)) {
                        note = "OFFCUT_RACK";
                    } else {
                        adjustment.getLines().add(adjustmentLine(AdjustmentKind.FOUND, unit.getCode(), null, found.getId()));
                        action = CountAction.ADJUSTMENT;
                    }
                }
                default -> {
                    // matched; or recorded elsewhere, as gone or unknown: reported only
                }
            }
            planned.add(new Planned(line, unit, action, note));
        }

        // The result and the closing together (chk_stock_counts_closed), before the adjustment is checked:
        // closed, the count no longer holds its units.
        StockCounting.Totals totals = StockCounting.totals(expected.size(), count.getScans().size(), lines);
        count.setExpectedUnits(totals.expected());
        count.setCountedUnits(totals.counted());
        count.setMatchedUnits(totals.matched());
        count.setMissingUnits(totals.missing());
        count.setMisplacedUnits(totals.misplaced());
        count.setExtraUnits(totals.extra());
        count.setClosedAt(now);
        count.setClosedBy(user);
        count.setStatus(StockCountStatus.CLOSED);

        if (!adjustment.getLines().isEmpty()) {
            StockAdjustment created = adjustmentService.create(adjustment);
            count.setAdjustmentId(created.getId());
            count.setAdjustmentNumber(created.getNumber());
        }
        int no = 0;
        for (Planned p : planned) {
            StockCountLine line = new StockCountLine();
            line.setCountId(count.getId());
            line.setLineNo(++no);
            line.setOutcome(p.line().outcome());
            line.setCode(p.line().code());
            line.setStockUnitId(p.unit() == null ? null : p.unit().getId());
            line.setUnitStatus(p.unit() == null ? null : p.unit().getStatus());
            line.setExpectedLocationId(p.line().expectedAt());
            line.setFoundLocationId(p.line().foundAt());
            line.setAction(p.action());
            line.setNote(p.note());
            line.setCreatedAt(now);
            line.setUsername(user);
            lineRepo.save(line);
        }
        return new CloseResult(count, racksOverLimit(movedTo, byId));
    }

    private record Planned(StockCounting.Line line, StockUnit unit, CountAction action, String note) {
    }

    private static StockAdjustmentDto.Line adjustmentLine(AdjustmentKind kind, String code, WriteOffCause cause, UUID locationId) {
        StockAdjustmentDto.Line row = new StockAdjustmentDto.Line();
        row.setKind(kind);
        row.setUnitCode(code);
        row.setCause(cause);
        row.setLocationId(locationId);
        return row;
    }

    /** Racks the moves left over their piece or weight limit (the glass is there; somebody should sort it out). */
    private List<String> racksOverLimit(Set<UUID> movedTo, Map<UUID, Location> byId) {
        if (movedTo.isEmpty()) {
            return List.of();
        }
        Map<UUID, RackLoad> loads = stockService.rackLoads(byId);
        return movedTo.stream()
                .map(id -> StockService.rackOf(byId.get(id), byId))
                .filter(Objects::nonNull)
                .distinct()
                .filter(rack -> {
                    RackLoad load = loads.getOrDefault(rack.getId(), RackLoad.EMPTY);
                    return load.exceedsPieces(rack.getMaxPieces()) || load.exceedsKg(rack.getMaxWeightKg());
                })
                .map(Location::getCode)
                .sorted()
                .toList();
    }

    /** Cancels an open count (its units are no longer held); nothing changes in stock. */
    @Transactional
    public StockCount cancel(UUID countId, String reason) {
        StockCount count = lockOpen(countId);
        count.setCancelReason(reason.trim());
        count.setClosedAt(LocalDateTime.now(clock));
        count.setClosedBy(username());
        count.setStatus(StockCountStatus.CANCELLED);
        return count;
    }

    private StockCount lockOpen(UUID countId) {
        StockCount count = repo.lockById(countId).orElseThrow(() -> new NotFoundException("StockCount", countId));
        if (!count.isOpen()) {
            throw BusinessException.of("count.notOpen", count.getNumber());
        }
        return count;
    }

    private static String username() {
        return AppUserPrincipal.current().map(AppUserPrincipal::getUsername).orElse("system");
    }
}
