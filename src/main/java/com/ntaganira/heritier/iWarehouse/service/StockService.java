package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.CrateBatch;
import com.ntaganira.heritier.iWarehouse.entity.Customer;
import com.ntaganira.heritier.iWarehouse.entity.GoodsReceipt;
import com.ntaganira.heritier.iWarehouse.entity.Location;
import com.ntaganira.heritier.iWarehouse.entity.Product;
import com.ntaganira.heritier.iWarehouse.entity.StockCostEntry;
import com.ntaganira.heritier.iWarehouse.entity.StockMovement;
import com.ntaganira.heritier.iWarehouse.entity.StockUnit;
import com.ntaganira.heritier.iWarehouse.enums.AdjustmentStatus;
import com.ntaganira.heritier.iWarehouse.enums.CostEntryType;
import com.ntaganira.heritier.iWarehouse.enums.DocumentType;
import com.ntaganira.heritier.iWarehouse.enums.LocationType;
import com.ntaganira.heritier.iWarehouse.enums.MovementType;
import com.ntaganira.heritier.iWarehouse.enums.StockAction;
import com.ntaganira.heritier.iWarehouse.enums.StockStatus;
import com.ntaganira.heritier.iWarehouse.enums.UnitKind;
import com.ntaganira.heritier.iWarehouse.enums.WriteOffCause;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.LocationRepository;
import com.ntaganira.heritier.iWarehouse.repository.StockAdjustmentLineRepository;
import com.ntaganira.heritier.iWarehouse.repository.StockCostEntryRepository;
import com.ntaganira.heritier.iWarehouse.repository.SalesInvoiceLineRepository;
import com.ntaganira.heritier.iWarehouse.repository.StockCountRepository;
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
 *               entry for every cost change (receipt cost, landed costs, PRC-05, part of a cut sheet,
 *               PRD-07). Units are taken for a cut, put back, consumed and cut here (PRD-02..04). Also the stock
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
    /** ref_type of movements and cost entries caused by a cutting job. */
    public static final String REF_CUTTING_JOB = "CUTTING_JOB";
    /** ref_type of movements caused by a transfer. */
    public static final String REF_TRANSFER = "TRANSFER";
    /** ref_type of movements and cost entries caused by an adjustment. */
    public static final String REF_ADJUSTMENT = "ADJUSTMENT";
    /** ref_type of reservation movements (the customer). */
    public static final String REF_CUSTOMER = "CUSTOMER";
    /** Reference type of COUNT movements (INV-08). */
    public static final String REF_STOCK_COUNT = "STOCK_COUNT";
    /** Movements of units sold, referring to their invoice. */
    public static final String REF_SALES_INVOICE = "SALES_INVOICE";
    /** Status filter value for every status; empty means "in stock". */
    public static final String ALL_STATUSES = "all";

    private final StockUnitRepository unitRepo;
    private final StockMovementRepository movementRepo;
    private final StockCostEntryRepository costEntryRepo;
    private final StockAdjustmentLineRepository adjustmentLineRepo;
    private final StockCountRepository countRepo;
    private final SalesInvoiceLineRepository saleLineRepo;
    private final LocationRepository locationRepo;
    private final DocumentNumberService numbers;
    private final Clock clock;

    public StockService(StockUnitRepository unitRepo, StockMovementRepository movementRepo,
                        StockCostEntryRepository costEntryRepo, StockAdjustmentLineRepository adjustmentLineRepo,
                        StockCountRepository countRepo, SalesInvoiceLineRepository saleLineRepo, LocationRepository locationRepo,
                        DocumentNumberService numbers, Clock clock) {
        this.unitRepo = unitRepo;
        this.movementRepo = movementRepo;
        this.costEntryRepo = costEntryRepo;
        this.adjustmentLineRepo = adjustmentLineRepo;
        this.countRepo = countRepo;
        this.saleLineRepo = saleLineRepo;
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

    /** Units cut from a unit (PRD-03), by code. */
    public List<StockUnit> cutFrom(UUID unitId) {
        return unitRepo.findByParentUnitIdOrderByCode(unitId);
    }

    /**
     * Units a cut can take (PRD-02, INV-06): available units of a product whose short side and long side
     * take the largest piece either way round, at least {@code minArea} m², smallest first.
     */
    public List<StockUnit> cuttingSources(UUID productId, int shortSideMm, int longSideMm, BigDecimal minArea, int limit) {
        Specification<StockUnit> spec = (root, query, cb) -> {
            var w = root.<Integer>get("widthMm");
            var h = root.<Integer>get("heightMm");
            return cb.and(
                    cb.equal(root.get("product").get("id"), productId),
                    cb.equal(root.get("status"), StockStatus.AVAILABLE),
                    cb.ge(root.get("areaM2"), minArea),
                    cb.or(cb.and(cb.ge(w, longSideMm), cb.ge(h, shortSideMm)),
                            cb.and(cb.ge(w, shortSideMm), cb.ge(h, longSideMm))));
        };
        return unitRepo.findAll(spec, PageRequest.of(0, limit, Sort.by("areaM2").and(Sort.by("code")))).getContent();
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

    // ---------------------------------------------------------------- what a unit may do (INV-05)

    /** Units held by pending adjustments and open stock counts: unit id to document number (INV-05, INV-08). */
    public Map<UUID, String> holds(Collection<UUID> unitIds) {
        if (unitIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> holds = new HashMap<>();
        for (Object[] row : adjustmentLineRepo.findHolds(AdjustmentStatus.PENDING_APPROVAL, unitIds)) {
            holds.putIfAbsent((UUID) row[0], (String) row[1]);
        }
        for (Object[] row : countRepo.findHolds(unitIds)) {
            holds.putIfAbsent((UUID) row[0], (String) row[1]);
        }
        // A sale being rung up holds its units until it is paid or cancelled (POS-01)
        for (Object[] row : saleLineRepo.findHolds(unitIds)) {
            holds.putIfAbsent((UUID) row[0], (String) row[1]);
        }
        return holds;
    }

    /** Refuses units a pending adjustment holds, other than {@code exceptNumber} (the one being posted). */
    public void requireNotHeld(Collection<StockUnit> units, String exceptNumber) {
        Map<UUID, String> holds = holds(units.stream().map(StockUnit::getId).toList());
        for (StockUnit unit : units) {
            String number = holds.get(unit.getId());
            if (number != null && !number.equals(exceptNumber)) {
                throw BusinessException.of("stock.held", unit.getCode(), number);
            }
        }
    }

    /** Refuses an action the unit's state does not allow (INV-05). */
    public static void requireAllowed(StockUnit unit, StockAction action) {
        if (!action.allows(unit.getStatus())) {
            throw BusinessException.of("stock.notAllowed." + action.name(), unit.getCode());
        }
    }

    // ---------------------------------------------------------------- reservations (INV-05)

    /** Reserves an available unit for a customer: nobody else can buy, load or cut it. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserve(StockUnit unit, Customer customer, String note) {
        requireAllowed(unit, StockAction.RESERVE);
        requireNotHeld(List.of(unit), null);
        StockStatus from = unit.getStatus();
        unit.setStatus(StockStatus.RESERVED);
        unit.setReservedCustomer(customer);
        unit.setReservedNote(note);
        UUID location = unit.getLocation() == null ? null : unit.getLocation().getId();
        record(unit, MovementType.RESERVE, location, from, LocalDateTime.now(clock), note, REF_CUSTOMER,
                customer.getId(), customer.getCode());
    }

    /** Releases a reserved unit: available to anyone again. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(StockUnit unit, String reason) {
        requireAllowed(unit, StockAction.RELEASE);
        Customer customer = unit.getReservedCustomer();
        unit.setStatus(StockStatus.AVAILABLE);
        unit.setReservedCustomer(null);
        unit.setReservedNote(null);
        UUID location = unit.getLocation() == null ? null : unit.getLocation().getId();
        record(unit, MovementType.RELEASE, location, StockStatus.RESERVED, LocalDateTime.now(clock), reason,
                customer == null ? null : REF_CUSTOMER, customer == null ? null : customer.getId(),
                customer == null ? null : customer.getCode());
    }

    // ---------------------------------------------------------------- sales (POS-01)

    /** A unit sold at the counter leaves stock: SOLD, off its rack, its reservation cleared. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void sell(StockUnit unit, UUID invoiceId, String invoiceNumber) {
        requireAllowed(unit, StockAction.SELL);
        UUID from = unit.getLocation() == null ? null : unit.getLocation().getId();
        StockStatus fromStatus = unit.getStatus();
        unit.setStatus(StockStatus.SOLD);
        unit.setLocation(null);
        unit.setReservedCustomer(null);
        unit.setReservedNote(null);
        record(unit, MovementType.SALE, from, fromStatus, LocalDateTime.now(clock), null, REF_SALES_INVOICE, invoiceId, invoiceNumber);
    }

    // ---------------------------------------------------------------- transfers and adjustments (INV-07)

    /** Moves a unit to another rack or slot; its status stays. The caller checked holds and rack limits. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void transfer(StockUnit unit, Location to, UUID transferId, String transferNumber) {
        requireAllowed(unit, StockAction.TRANSFER);
        UUID from = unit.getLocation() == null ? null : unit.getLocation().getId();
        unit.setLocation(to);
        record(unit, MovementType.TRANSFER, from, unit.getStatus(), LocalDateTime.now(clock), null, REF_TRANSFER,
                transferId, transferNumber);
    }

    /**
     * A unit a stock count found on another rack or slot: its location follows (INV-08). The glass is
     * there already, so rack limits are not checked; the count reports racks left over their limits.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void countMove(StockUnit unit, Location to, UUID countId, String countNumber) {
        requireAllowed(unit, StockAction.TRANSFER);
        UUID from = unit.getLocation() == null ? null : unit.getLocation().getId();
        unit.setLocation(to);
        record(unit, MovementType.COUNT, from, unit.getStatus(), LocalDateTime.now(clock), null, REF_STOCK_COUNT,
                countId, countNumber);
    }

    /** Writes a unit off: damaged units become BROKEN, missing ones LOST; either way it leaves stock. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void writeOff(StockUnit unit, WriteOffCause cause, String reason, UUID adjustmentId, String adjustmentNumber) {
        requireAllowed(unit, StockAction.ADJUST);
        leaveStock(unit, cause.status(), reason, adjustmentId, adjustmentNumber);
    }

    /** A lost unit found again: back in stock, available, where it was found, at its old cost. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void find(StockUnit unit, Location location, String reason, UUID adjustmentId, String adjustmentNumber) {
        requireAllowed(unit, StockAction.FIND);
        unit.setStatus(StockStatus.AVAILABLE);
        unit.setLocation(location);
        record(unit, MovementType.ADJUSTMENT, null, StockStatus.LOST, LocalDateTime.now(clock), reason, REF_ADJUSTMENT,
                adjustmentId, adjustmentNumber);
    }

    /** A unit replaced by one of the right size (resize): it leaves stock as CONSUMED. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void replace(StockUnit unit, String reason, UUID adjustmentId, String adjustmentNumber) {
        requireAllowed(unit, StockAction.ADJUST);
        leaveStock(unit, StockStatus.CONSUMED, reason, adjustmentId, adjustmentNumber);
    }

    /** A unit an adjustment adds: a piece nobody recorded, or the corrected size of a replaced unit. */
    public record NewUnit(Product product, UnitKind kind, CrateBatch crateBatch, UUID parentUnitId, int widthMm,
                          int heightMm, BigDecimal weightKg, Location location, BigDecimal cost, StockStatus status,
                          Customer reservedCustomer, String reservedNote) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public StockUnit createAdjusted(NewUnit spec, String reason, UUID adjustmentId, String adjustmentNumber) {
        LocalDateTime now = LocalDateTime.now(clock);
        StockUnit unit = new StockUnit();
        unit.setCode(numbers.next(DocumentType.STOCK_UNIT));
        unit.setProduct(spec.product());
        unit.setKind(spec.kind());
        unit.setCrateBatch(spec.crateBatch());
        unit.setParentUnitId(spec.parentUnitId());
        unit.setWidthMm(spec.widthMm());
        unit.setHeightMm(spec.heightMm());
        unit.setAreaM2(Pricing.areaM2(spec.widthMm(), spec.heightMm()));
        unit.setWeightKg(spec.weightKg());
        unit.setStatus(spec.status());
        unit.setLocation(spec.location());
        unit.setReservedCustomer(spec.status() == StockStatus.RESERVED ? spec.reservedCustomer() : null);
        unit.setReservedNote(spec.status() == StockStatus.RESERVED ? spec.reservedNote() : null);
        unit.setUnitCost(spec.cost());
        unitRepo.save(unit);
        record(unit, MovementType.ADJUSTMENT, null, null, now, reason, REF_ADJUSTMENT, adjustmentId, adjustmentNumber);
        recordCost(unit, CostEntryType.ADJUSTMENT, spec.cost(), now, REF_ADJUSTMENT, adjustmentId, adjustmentNumber);
        return unit;
    }

    private void leaveStock(StockUnit unit, StockStatus to, String reason, UUID refId, String refNumber) {
        UUID from = unit.getLocation() == null ? null : unit.getLocation().getId();
        StockStatus fromStatus = unit.getStatus();
        unit.setStatus(to);
        unit.setLocation(null);
        unit.setReservedCustomer(null);
        unit.setReservedNote(null);
        record(unit, MovementType.ADJUSTMENT, from, fromStatus, LocalDateTime.now(clock), reason, REF_ADJUSTMENT, refId, refNumber);
    }

    /** Racks and slots that can hold glass: active, under an active rack. Sorted by code. */
    public List<Location> storagePlaces(Map<UUID, Location> byId) {
        return byId.values().stream()
                .filter(Location::isEnabled)
                .filter(l -> l.getType() == LocationType.RACK || l.getType() == LocationType.SLOT)
                .filter(l -> {
                    Location rack = rackOf(l, byId);
                    return rack != null && rack.isEnabled();
                })
                .sorted(Comparator.comparing(Location::getCode))
                .toList();
    }

    // ---------------------------------------------------------------- cutting (PRD-02..04)

    /** Takes an available unit for a cutting job: it goes IN_CUTTING, so nothing else uses it (PRD-02, INV-05). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void startCutting(StockUnit unit, UUID jobId, String jobNumber) {
        if (unit.getStatus() != StockStatus.AVAILABLE) {
            throw BusinessException.of("cutting.source.notAvailable", unit.getCode());
        }
        requireNotHeld(List.of(unit), null);
        moveStatus(unit, StockStatus.IN_CUTTING, MovementType.CUTTING_START, null, jobId, jobNumber);
    }

    /** Puts a unit taken for a cutting job back, uncut: available again where it is. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseCutting(StockUnit unit, String reason, UUID jobId, String jobNumber) {
        requireInCutting(unit);
        moveStatus(unit, StockStatus.AVAILABLE, MovementType.CUTTING_RELEASE, reason, jobId, jobNumber);
    }

    /** The unit was cut: it leaves stock, the units cut from it replace it (PRD-03). Its cost is unchanged. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void consumeByCutting(StockUnit unit, UUID jobId, String jobNumber) {
        requireInCutting(unit);
        UUID from = unit.getLocation() == null ? null : unit.getLocation().getId();
        unit.setStatus(StockStatus.CONSUMED);
        unit.setLocation(null);
        record(unit, MovementType.CUTTING_CONSUMED, from, StockStatus.IN_CUTTING, LocalDateTime.now(clock), null,
                REF_CUTTING_JOB, jobId, jobNumber);
    }

    /**
     * Creates a cut piece or off-cut from a source (PRD-03, PRD-04): own label code, the source's product
     * and crate, its part of the source cost (PRD-07), a movement and a cost entry. Runs inside the cut.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public StockUnit createCut(StockUnit source, UnitKind kind, int widthMm, int heightMm, BigDecimal weightKg,
                               StockStatus status, Location location, BigDecimal cost, Customer reservedFor,
                               UUID jobId, String jobNumber) {
        LocalDateTime now = LocalDateTime.now(clock);
        StockUnit unit = new StockUnit();
        unit.setCode(numbers.next(DocumentType.STOCK_UNIT));
        unit.setProduct(source.getProduct());
        unit.setKind(kind);
        unit.setCrateBatch(source.getCrateBatch());
        unit.setParentUnitId(source.getId());
        unit.setWidthMm(widthMm);
        unit.setHeightMm(heightMm);
        unit.setAreaM2(Pricing.areaM2(widthMm, heightMm));
        unit.setWeightKg(weightKg);
        unit.setStatus(status);
        unit.setLocation(location);
        if (status == StockStatus.RESERVED && reservedFor != null) {
            unit.setReservedCustomer(reservedFor);
            unit.setReservedNote(jobNumber);
        }
        unit.setUnitCost(cost);
        unitRepo.save(unit);
        record(unit, MovementType.CUTTING_OUTPUT, null, null, now, null, REF_CUTTING_JOB, jobId, jobNumber);
        recordCost(unit, CostEntryType.CUTTING, cost, now, REF_CUTTING_JOB, jobId, jobNumber);
        return unit;
    }

    private static void requireInCutting(StockUnit unit) {
        if (unit.getStatus() != StockStatus.IN_CUTTING) {
            throw BusinessException.of("cutting.source.notInCutting", unit.getCode());
        }
    }

    /** Changes a unit's status where it is, with its movement. */
    private void moveStatus(StockUnit unit, StockStatus to, MovementType type, String reason, UUID refId, String refNumber) {
        StockStatus from = unit.getStatus();
        unit.setStatus(to);
        UUID location = unit.getLocation() == null ? null : unit.getLocation().getId();
        record(unit, type, location, from, LocalDateTime.now(clock), reason, REF_CUTTING_JOB, refId, refNumber);
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
