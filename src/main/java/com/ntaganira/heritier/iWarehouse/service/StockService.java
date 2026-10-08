package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.CrateBatch;
import com.ntaganira.heritier.iWarehouse.entity.GoodsReceipt;
import com.ntaganira.heritier.iWarehouse.entity.Location;
import com.ntaganira.heritier.iWarehouse.entity.StockCostEntry;
import com.ntaganira.heritier.iWarehouse.entity.StockMovement;
import com.ntaganira.heritier.iWarehouse.entity.StockUnit;
import com.ntaganira.heritier.iWarehouse.enums.CostEntryType;
import com.ntaganira.heritier.iWarehouse.enums.DocumentType;
import com.ntaganira.heritier.iWarehouse.enums.LocationType;
import com.ntaganira.heritier.iWarehouse.enums.MovementType;
import com.ntaganira.heritier.iWarehouse.enums.StockStatus;
import com.ntaganira.heritier.iWarehouse.enums.UnitKind;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.LocationRepository;
import com.ntaganira.heritier.iWarehouse.repository.StockCostEntryRepository;
import com.ntaganira.heritier.iWarehouse.repository.StockMovementRepository;
import com.ntaganira.heritier.iWarehouse.repository.StockUnitRepository;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : StockService.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Stock units (INV-01..04, INV-06). The only place that creates units or changes their
 *               status, location or cost: it writes a stock movement for every move (INV-04) and a cost
 *               entry for every cost change (receipt cost, landed costs, PRC-05). Also the stock
 *               search (smallest fitting piece first), rack loads against rack limits (MD-03), and the
 *               stock held per product or location for other modules' checks.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class StockService {

    /** ref_type of movements caused by a goods receipt. */
    public static final String REF_GOODS_RECEIPT = "GOODS_RECEIPT";
    /** ref_type of cost entries caused by a shipment posting (landed cost). */
    public static final String REF_SHIPMENT = "SHIPMENT";
    /** Status filter value for every status; empty means "in stock". */
    public static final String ALL_STATUSES = "all";

    private final StockUnitRepository unitRepo;
    private final StockMovementRepository movementRepo;
    private final StockCostEntryRepository costEntryRepo;
    private final LocationRepository locationRepo;
    private final DocumentNumberService numbers;
    private final Clock clock;

    public StockService(StockUnitRepository unitRepo, StockMovementRepository movementRepo,
                        StockCostEntryRepository costEntryRepo, LocationRepository locationRepo,
                        DocumentNumberService numbers, Clock clock) {
        this.unitRepo = unitRepo;
        this.movementRepo = movementRepo;
        this.costEntryRepo = costEntryRepo;
        this.locationRepo = locationRepo;
        this.numbers = numbers;
        this.clock = clock;
    }

    /**
     * Stock list filters. status: empty = in stock (held), "all", or a StockStatus name. With a minimum
     * size the search returns pieces at least that big either way round, smallest first (INV-06), and
     * only available ones unless a status is chosen.
     */
    public record UnitFilter(String search, UUID productId, UUID locationId, String status, UnitKind kind,
                             Integer minWidth, Integer minHeight) {

        public boolean fitSearch() {
            return minWidth != null && minHeight != null;
        }
    }

    // ---------------------------------------------------------------- reading

    public Page<StockUnit> findPage(UnitFilter f, int page, int size) {
        Set<UUID> locationIds = f.locationId() == null ? null : withDescendants(f.locationId());
        Specification<StockUnit> spec = (root, query, cb) -> {
            Predicate p = cb.conjunction();
            if (StringUtils.hasText(f.search())) {
                String term = "%" + f.search().trim().toLowerCase(Locale.ROOT) + "%";
                Join<StockUnit, CrateBatch> crate = root.join("crateBatch", JoinType.LEFT);
                p = cb.and(p, cb.or(cb.like(cb.lower(root.get("code")), term),
                        cb.like(cb.lower(crate.get("batchNo")), term)));
            }
            if (f.productId() != null) {
                p = cb.and(p, cb.equal(root.get("product").get("id"), f.productId()));
            }
            if (locationIds != null) {
                p = cb.and(p, root.get("location").get("id").in(locationIds));
            }
            if (f.kind() != null) {
                p = cb.and(p, cb.equal(root.get("kind"), f.kind()));
            }
            StockStatus status = parseStatus(f.status());
            if (status != null) {
                p = cb.and(p, cb.equal(root.get("status"), status));
            } else if (!ALL_STATUSES.equalsIgnoreCase(f.status())) {
                p = cb.and(p, f.fitSearch() ? cb.equal(root.get("status"), StockStatus.AVAILABLE)
                        : root.get("status").in(StockStatus.onHand()));
            }
            if (f.fitSearch()) {
                var w = root.<Integer>get("widthMm");
                var h = root.<Integer>get("heightMm");
                p = cb.and(p, cb.or(
                        cb.and(cb.ge(w, f.minWidth()), cb.ge(h, f.minHeight())),
                        cb.and(cb.ge(w, f.minHeight()), cb.ge(h, f.minWidth()))));
            }
            return p;
        };
        Sort sort = f.fitSearch()
                ? Sort.by("areaM2").and(Sort.by("code"))
                : Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "code"));
        return unitRepo.findAll(spec, PageRequest.of(page, size, sort));
    }

    /** A StockStatus name, or null for "in stock" / "all" / anything else. */
    public static StockStatus parseStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return null;
        }
        try {
            return StockStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** A unit by its label code, e.g. scanned into the search box. */
    public Optional<StockUnit> findByCode(String code) {
        return StringUtils.hasText(code) ? unitRepo.findByCodeIgnoreCase(code.trim()) : Optional.empty();
    }

    /** A unit with its product, location and where it came from (crate, receipt, order, supplier). */
    public StockUnit findDetailed(UUID id) {
        return unitRepo.findDetailedById(id).orElseThrow(() -> new NotFoundException("StockUnit", id));
    }

    /** Movements of a unit, oldest first. */
    public List<StockMovement> movements(UUID unitId) {
        return movementRepo.findByStockUnitIdOrderByMovedAtAsc(unitId);
    }

    /** How a unit's cost was built up: receipt cost, then landed costs, oldest first. */
    public List<StockCostEntry> costEntries(UUID unitId) {
        return costEntryRepo.findByStockUnitIdOrderByCreatedAtAscIdAsc(unitId);
    }

    public List<StockUnit> unitsOfReceipt(UUID receiptId) {
        return unitRepo.findByCrateBatch_GoodsReceipt_IdOrderByCode(receiptId);
    }

    public List<StockUnit> unitsOfCrate(UUID crateId) {
        return unitRepo.findByCrateBatch_IdOrderByCode(crateId);
    }

    /** Sheets as received of some crates (not pieces cut from them later), by code. */
    public List<StockUnit> receivedSheetsOf(Collection<UUID> crateIds) {
        if (crateIds.isEmpty()) {
            return List.of();
        }
        return unitRepo.findByCrateBatch_IdInOrderByCode(crateIds).stream()
                .filter(u -> u.getKind() == UnitKind.SHEET && u.getParentUnitId() == null)
                .toList();
    }

    /** m² of a product still held (moving average cost). */
    public BigDecimal heldArea(UUID productId) {
        return unitRepo.sumArea(productId, StockStatus.onHand());
    }

    /** Units held on a location: it stays active while they are there. */
    public long countHeldAt(UUID locationId) {
        return unitRepo.countByLocation_IdAndStatusIn(locationId, StockStatus.onHand());
    }

    /** Units held of a product: it stays active while there are some. */
    public long countHeldOf(UUID productId) {
        return unitRepo.countByProduct_IdAndStatusIn(productId, StockStatus.onHand());
    }

    // ---------------------------------------------------------------- locations and rack limits

    /** Every location by id (the tree is small). */
    public Map<UUID, Location> locationsById() {
        return locationRepo.findAll().stream().collect(Collectors.toMap(Location::getId, Function.identity()));
    }

    /** The rack a location counts towards: itself for a rack, its parent for a slot, otherwise none. */
    public static Location rackOf(Location location, Map<UUID, Location> byId) {
        if (location == null) {
            return null;
        }
        if (location.getType() == LocationType.RACK) {
            return location;
        }
        if (location.getType() == LocationType.SLOT && location.getParentId() != null) {
            return byId.get(location.getParentId());
        }
        return null;
    }

    /** Pieces and kg held on each rack now, keyed by rack id; slots count towards their rack (MD-03). */
    public Map<UUID, RackLoad> rackLoads(Map<UUID, Location> byId) {
        Map<UUID, RackLoad> loads = new HashMap<>();
        for (Object[] row : unitRepo.loadByLocation(StockStatus.onHand())) {
            Location rack = rackOf(byId.get((UUID) row[0]), byId);
            if (rack != null) {
                loads.merge(rack.getId(), new RackLoad(((Number) row[1]).longValue(), (BigDecimal) row[2]),
                        (a, b) -> a.plus(b.pieces(), b.kg()));
            }
        }
        return loads;
    }

    /**
     * Where a crate of full sheets can go: active racks and slots, not on an off-cut rack. Sorted by code.
     */
    public List<Location> receivingLocations(Map<UUID, Location> byId) {
        return byId.values().stream()
                .filter(Location::isEnabled)
                .filter(l -> l.getType() == LocationType.RACK || l.getType() == LocationType.SLOT)
                .filter(l -> {
                    Location rack = rackOf(l, byId);
                    return rack != null && !rack.isOffcut();
                })
                .sorted(Comparator.comparing(Location::getCode))
                .toList();
    }

    /** A location and every location under it, for the stock filter. */
    private Set<UUID> withDescendants(UUID rootId) {
        Map<UUID, List<UUID>> children = new HashMap<>();
        for (Location l : locationRepo.findAll()) {
            if (l.getParentId() != null) {
                children.computeIfAbsent(l.getParentId(), k -> new ArrayList<>()).add(l.getId());
            }
        }
        Set<UUID> ids = new HashSet<>();
        Deque<UUID> todo = new ArrayDeque<>(List.of(rootId));
        while (!todo.isEmpty()) {
            UUID id = todo.pop();
            if (ids.add(id)) {
                todo.addAll(children.getOrDefault(id, List.of()));
            }
        }
        return ids;
    }

    // ---------------------------------------------------------------- changing stock

    /**
     * Creates one AVAILABLE sheet per good sheet of a crate, on the crate's rack, each with its label
     * code, a RECEIPT movement and its receipt cost (PRC-02, INV-03, INV-04). Runs inside the receipt's
     * posting.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<StockUnit> receive(CrateBatch crate, BigDecimal unitWeightKg, BigDecimal unitCost, GoodsReceipt receipt) {
        LocalDateTime now = LocalDateTime.now(clock);
        List<StockUnit> units = new ArrayList<>(crate.getSheets());
        for (int i = 0; i < crate.getSheets(); i++) {
            StockUnit unit = new StockUnit();
            unit.setCode(numbers.next(DocumentType.STOCK_UNIT));
            unit.setProduct(crate.getProduct());
            unit.setKind(UnitKind.SHEET);
            unit.setCrateBatch(crate);
            unit.setWidthMm(crate.getWidthMm());
            unit.setHeightMm(crate.getHeightMm());
            unit.setAreaM2(crate.getSheetArea());
            unit.setWeightKg(unitWeightKg);
            unit.setStatus(StockStatus.AVAILABLE);
            unit.setLocation(crate.getLocation());
            unit.setUnitCost(unitCost);
            unitRepo.save(unit);
            record(unit, MovementType.RECEIPT, null, null, now, null, REF_GOODS_RECEIPT, receipt.getId(), receipt.getNumber());
            recordCost(unit, CostEntryType.RECEIPT, unitCost, now, REF_GOODS_RECEIPT, receipt.getId(), receipt.getNumber());
            units.add(unit);
        }
        return units;
    }

    /**
     * Adds a landed cost (or, negative, a credit) to a unit and records it (PRC-05). A cost can't go below
     * zero. Runs inside the shipment's posting.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void addLandedCost(StockUnit unit, BigDecimal amount, UUID shipmentId, String shipmentNumber) {
        BigDecimal after = unit.getUnitCost().add(amount);
        if (after.signum() < 0) {
            throw BusinessException.of("shipment.post.negativeCost", unit.getCode());
        }
        unit.setUnitCost(after);
        recordCost(unit, CostEntryType.LANDED_COST, amount, LocalDateTime.now(clock), REF_SHIPMENT, shipmentId, shipmentNumber);
    }

    /** Writes a change of a unit's cost, with the cost after it. */
    private void recordCost(StockUnit unit, CostEntryType type, BigDecimal amount, LocalDateTime at, String refType,
                            UUID refId, String refNumber) {
        StockCostEntry entry = new StockCostEntry();
        entry.setStockUnitId(unit.getId());
        entry.setType(type);
        entry.setAmount(amount);
        entry.setCostAfter(unit.getUnitCost());
        entry.setRefType(refType);
        entry.setRefId(refId);
        entry.setRefNumber(refNumber);
        entry.setCreatedAt(at);
        Optional<AppUserPrincipal> user = AppUserPrincipal.current();
        entry.setUserId(user.map(AppUserPrincipal::getId).orElse(null));
        entry.setUsername(user.map(AppUserPrincipal::getUsername).orElse("system"));
        costEntryRepo.save(entry);
    }

    /** Writes the movement that brought the unit to its current status and location (INV-04). */
    private void record(StockUnit unit, MovementType type, UUID fromLocationId, StockStatus fromStatus,
                        LocalDateTime at, String reason, String refType, UUID refId, String refNumber) {
        StockMovement movement = new StockMovement();
        movement.setStockUnitId(unit.getId());
        movement.setMovedAt(at);
        movement.setType(type);
        movement.setFromLocationId(fromLocationId);
        movement.setToLocationId(unit.getLocation() == null ? null : unit.getLocation().getId());
        movement.setFromStatus(fromStatus);
        movement.setToStatus(unit.getStatus());
        movement.setReason(reason);
        movement.setRefType(refType);
        movement.setRefId(refId);
        movement.setRefNumber(refNumber);
        Optional<AppUserPrincipal> user = AppUserPrincipal.current();
        movement.setUserId(user.map(AppUserPrincipal::getId).orElse(null));
        movement.setUsername(user.map(AppUserPrincipal::getUsername).orElse("system"));
        movementRepo.save(movement);
    }
}
