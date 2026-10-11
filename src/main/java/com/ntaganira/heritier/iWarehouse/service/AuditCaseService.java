package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.*;
import com.ntaganira.heritier.iWarehouse.enums.*;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.AuditCaseLineRepository;
import com.ntaganira.heritier.iWarehouse.repository.AuditCaseRepository;
import com.ntaganira.heritier.iWarehouse.repository.ProductRepository;
import com.ntaganira.heritier.iWarehouse.repository.StockUnitRepository;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import jakarta.persistence.criteria.Join;
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

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : AuditCaseService.java
 * - Date      : 2026. 10. 11.
 * - User      : Hntaganira
 * - Desc      : Audit cases (FLT-10, FLT-11): opened when a trip is reconciled with units missing or unexpected (the owner
 *               and the supervisor told), then each unit settled on its line. A missing unit is found (back on a rack), sold
 *               (its sale synced late from the phone), written off (LOST, Dr Stock Adjustment) or charged to the driver (LOST,
 *               Dr Driver Shortage Receivable; the caller checks the owner's approval, CHARGE_DRIVER); an unexpected unit is
 *               explained. The case is resolved once every line is; escalated to the owner, it lets the trip close. Settling
 *               locks the case, then the glass, and reads the stock held before changing it.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class AuditCaseService {

    private final AuditCaseRepository repo;
    private final AuditCaseLineRepository lineRepo;
    private final StockUnitRepository unitRepo;
    private final ProductRepository productRepo;
    private final LocationService locationService;
    private final StockService stockService;
    private final PostingService postingService;
    private final DocumentNumberService numbers;
    private final Notifier notifier;
    private final Clock clock;

    public AuditCaseService(AuditCaseRepository repo, AuditCaseLineRepository lineRepo, StockUnitRepository unitRepo,
                            ProductRepository productRepo, LocationService locationService, StockService stockService,
                            PostingService postingService, DocumentNumberService numbers, Notifier notifier, Clock clock) {
        this.repo = repo;
        this.lineRepo = lineRepo;
        this.unitRepo = unitRepo;
        this.productRepo = productRepo;
        this.locationService = locationService;
        this.stockService = stockService;
        this.postingService = postingService;
        this.numbers = numbers;
        this.notifier = notifier;
        this.clock = clock;
    }

    public Page<AuditCase> findPage(String search, AuditCaseStatus status, int page, int size) {
        Specification<AuditCase> spec = (root, query, cb) -> {
            Predicate p = cb.conjunction();
            if (StringUtils.hasText(search)) {
                String term = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                Join<AuditCase, Trip> trip = root.join("trip");
                p = cb.and(p, cb.or(cb.like(cb.lower(root.get("number")), term), cb.like(cb.lower(trip.get("number")), term),
                        cb.like(cb.lower(trip.get("vehicle").get("plate")), term), cb.like(cb.lower(trip.get("driver").get("username")), term)));
            }
            if (status != null) {
                p = cb.and(p, cb.equal(root.get("status"), status));
            }
            return p;
        };
        return repo.findAll(spec, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "openedAt")));
    }

    public AuditCase findDetailed(UUID id) {
        return repo.findDetailedById(id).orElseThrow(() -> new NotFoundException("AuditCase", id));
    }

    public Optional<AuditCase> ofTrip(UUID tripId) {
        return repo.findByTrip_Id(tripId);
    }

    /** Cases not resolved yet: open, or escalated to the owner (the dashboard, the menu badge). */
    public long unresolvedCount() {
        return repo.countByStatusIn(List.of(AuditCaseStatus.OPEN, AuditCaseStatus.ESCALATED));
    }

    public long openCount() {
        return repo.countByStatus(AuditCaseStatus.OPEN);
    }

    /** The units of a case, missing first, by label (rows written together share a timestamp). */
    public static List<AuditCaseLine> sortedLines(AuditCase c) {
        return c.getLines().stream().sorted(Comparator.comparing(AuditCaseLine::getKind).thenComparing(AuditCaseLine::getUnitCode)).toList();
    }

    /** The units of a case as they are now (where a missing unit still is). */
    public Map<UUID, StockUnit> units(AuditCase c) {
        List<UUID> ids = c.getLines().stream().map(AuditCaseLine::getStockUnitId).filter(Objects::nonNull).toList();
        Map<UUID, StockUnit> units = new HashMap<>();
        unitRepo.findByIdIn(ids).forEach(u -> units.put(u.getId(), u));
        return units;
    }

    /** Opens the case of a trip reconciled with differences (FLT-10); the owner and the supervisor are told. */
    @Transactional(propagation = Propagation.MANDATORY)
    public AuditCase open(Trip trip, List<TripReconciliation.Line> missing, List<TripReconciliation.Unexpected> unexpected,
                          Map<UUID, StockUnit> units) {
        AuditCase c = new AuditCase();
        c.setNumber(numbers.next(DocumentType.AUDIT_CASE));
        c.setTrip(trip);
        c.setOpenedAt(LocalDateTime.now(clock));
        BigDecimal value = BigDecimal.ZERO;
        for (TripReconciliation.Line m : missing) {
            StockUnit unit = units.get(m.unitId());
            BigDecimal cost = unit == null || unit.getUnitCost() == null ? BigDecimal.ZERO : unit.getUnitCost();
            c.getLines().add(line(c, AuditLineKind.MISSING, m.unitId(), m.code(), cost));
            value = value.add(cost);
        }
        for (TripReconciliation.Unexpected u : unexpected) {
            c.getLines().add(line(c, AuditLineKind.UNEXPECTED, u.unitId(), u.code(), BigDecimal.ZERO));
        }
        c.setMissingCount(missing.size());
        c.setUnexpectedCount(unexpected.size());
        c.setMissingValue(value);
        repo.save(c);
        notifier.holders("ALERT_EOD", null, NotificationKind.EOD, "notify.eod.case.title", "notify.eod.case.message", "/audit-cases/" + c.getId(),
                c.getNumber(), trip.getNumber(), trip.getVehicle().getPlate(), trip.getDriver().getUser().getFullName(),
                String.valueOf(missing.size()), String.valueOf(unexpected.size()));
        return c;
    }

    private static AuditCaseLine line(AuditCase c, AuditLineKind kind, UUID unitId, String code, BigDecimal value) {
        AuditCaseLine line = new AuditCaseLine();
        line.setAuditCase(c);
        line.setKind(kind);
        line.setStockUnitId(unitId);
        line.setUnitCode(code);
        line.setUnitValue(value);
        return line;
    }

    /** A missing unit found after the trip (on the vehicle all along, or brought back later): back on the place given. */
    @Transactional
    public AuditCaseLine found(UUID caseId, UUID lineId, String placeCode, String note) {
        AuditCase c = lock(caseId);
        AuditCaseLine line = openLine(c, lineId, AuditLineKind.MISSING);
        StockUnit unit = onVehicle(c, line);
        Map<UUID, Location> byId = stockService.locationsById();
        Set<UUID> storage = new HashSet<>(stockService.storagePlaces(byId).stream().map(Location::getId).toList());
        Location place = locationService.findByCode(placeCode).filter(l -> storage.contains(l.getId()))
                .orElseThrow(() -> BusinessException.onField("place", "trip.return.place.invalid", placeCode == null ? "" : placeCode.trim()));
        checkRack(place, unit, byId);
        stockService.unload(unit, place, StockService.REF_AUDIT_CASE, c.getId(), c.getNumber());
        settle(line, AuditLineOutcome.FOUND, StringUtils.hasText(note) ? note.trim() : place.getCode());
        refresh(c);
        return line;
    }

    /** A missing unit written off: the business's loss (LOST, Dr Stock Adjustment at its own cost). */
    @Transactional
    public AuditCaseLine writeOff(UUID caseId, UUID lineId, String reason) {
        return lose(caseId, lineId, reason, false);
    }

    /** A missing unit charged to the driver with the owner's approval (FLT-11): LOST, Dr Driver Shortage Receivable. */
    @Transactional
    public AuditCaseLine charge(UUID caseId, UUID lineId, String reason) {
        return lose(caseId, lineId, reason, true);
    }

    private AuditCaseLine lose(UUID caseId, UUID lineId, String reason, boolean toDriver) {
        if (!StringUtils.hasText(reason)) {
            throw BusinessException.onField("reason", "auditCase.reason.required");
        }
        AuditCase c = lock(caseId);
        AuditCaseLine line = openLine(c, lineId, AuditLineKind.MISSING);
        StockUnit unit = onVehicle(c, line);
        Product product = productRepo.lockAllById(List.of(unit.getProduct().getId())).get(0);
        Map<UUID, BigDecimal> heldBefore = Map.of(product.getId(), stockService.heldArea(product.getId()));
        PostingService.StockValues before = postingService.stockValues(List.of(product));
        stockService.loseFromVehicle(unit, reason.trim(), c.getId(), c.getNumber());
        StockLeaving leaving = new StockLeaving();
        leaving.add(unit);
        leaving.moveMacs(Map.of(product.getId(), product), heldBefore);
        settle(line, toDriver ? AuditLineOutcome.CHARGED : AuditLineOutcome.WRITTEN_OFF, reason.trim());
        refresh(c);
        postingService.auditLoss(c, line.getUnitCode(), leaving.cost(), toDriver ? c.getTrip().getDriver().getId() : null, before);
        return line;
    }

    /** An unexpected unit explained: its own documents (a transfer, a count, a credit note) put it right. */
    @Transactional
    public AuditCaseLine explain(UUID caseId, UUID lineId, String note) {
        if (!StringUtils.hasText(note)) {
            throw BusinessException.onField("reason", "auditCase.note.required");
        }
        AuditCase c = lock(caseId);
        AuditCaseLine line = openLine(c, lineId, AuditLineKind.UNEXPECTED);
        settle(line, AuditLineOutcome.EXPLAINED, note.trim());
        refresh(c);
        return line;
    }

    /** Escalated to the owner (FLT-10): the trip may close; the units are still to settle. */
    @Transactional
    public AuditCase escalate(UUID caseId, String note) {
        if (!StringUtils.hasText(note)) {
            throw BusinessException.onField("reason", "auditCase.note.required");
        }
        AuditCase c = lock(caseId);
        if (!c.isOpen()) {
            throw BusinessException.of("auditCase.notOpen", c.getNumber(), new org.springframework.context.support.DefaultMessageSourceResolvable(
                    "auditCase.status." + c.getStatus().name()));
        }
        c.setEscalatedAt(LocalDateTime.now(clock));
        c.setEscalatedBy(AppUserPrincipal.currentUsername());
        c.setEscalationNote(note.trim());
        c.setStatus(AuditCaseStatus.ESCALATED);
        notifier.holders("CHARGE_DRIVER", AppUserPrincipal.current().map(AppUserPrincipal::getId).orElse(null), NotificationKind.EOD,
                "notify.eod.escalated.title", "notify.eod.escalated.message", "/audit-cases/" + c.getId(), c.getNumber(),
                c.getTrip().getNumber(), note.trim());
        return c;
    }

    /** A missing unit sold from the vehicle, its sale synced after the trip came back: the line is settled as sold. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void unitSold(UUID unitId, String invoiceNumber) {
        lineRepo.findOpenMissing(unitId).ifPresent(found -> {
            AuditCase c = lock(found.getAuditCase().getId());
            AuditCaseLine line = c.getLines().stream().filter(l -> l.getId().equals(found.getId())).findFirst().orElseThrow();
            settle(line, AuditLineOutcome.SOLD, invoiceNumber);
            refresh(c);
        });
    }

    private void settle(AuditCaseLine line, AuditLineOutcome outcome, String note) {
        line.setNote(note);
        line.setOutcomeBy(AppUserPrincipal.currentUsername());
        line.setOutcomeAt(LocalDateTime.now(clock));
        line.setOutcome(outcome);
    }

    /** Resolved once every unit is settled. */
    private void refresh(AuditCase c) {
        if (!c.isResolved() && c.getLines().stream().allMatch(AuditCaseLine::isSettled)) {
            c.setResolvedAt(LocalDateTime.now(clock));
            c.setStatus(AuditCaseStatus.RESOLVED);
        }
    }

    private static AuditCaseLine openLine(AuditCase c, UUID lineId, AuditLineKind kind) {
        AuditCaseLine line = c.getLines().stream().filter(l -> l.getId().equals(lineId)).findFirst()
                .orElseThrow(() -> new NotFoundException("AuditCaseLine", lineId));
        if (line.isSettled()) {
            throw BusinessException.of("auditCase.line.settled", line.getUnitCode());
        }
        if (line.getKind() != kind) {
            throw BusinessException.of("auditCase.line.kind", line.getUnitCode());
        }
        return line;
    }

    /** A missing unit is still on its trip's vehicle (where the reconciliation left it). */
    private StockUnit onVehicle(AuditCase c, AuditCaseLine line) {
        StockUnit unit = line.getStockUnitId() == null ? null : unitRepo.findByIdIn(List.of(line.getStockUnitId())).stream().findFirst().orElse(null);
        UUID vehicleLocation = c.getTrip().getVehicle().getLocation().getId();
        if (unit == null || unit.getStatus() != StockStatus.ON_VEHICLE || unit.getLocation() == null || !unit.getLocation().getId().equals(vehicleLocation)) {
            throw BusinessException.of("auditCase.unit.notOnVehicle", line.getUnitCode(), c.getTrip().getVehicle().getPlate());
        }
        return unit;
    }

    /** Glass found goes on a rack within its limits (MD-03); a full sheet never on an off-cut rack. */
    private void checkRack(Location place, StockUnit unit, Map<UUID, Location> byId) {
        Location rack = StockService.rackOf(place, byId);
        if (rack.isOffcut() && unit.getKind() == UnitKind.SHEET) {
            throw BusinessException.onField("place", "trip.return.sheetToOffcut", unit.getCode(), rack.getCode());
        }
        RackLoad now = stockService.rackLoads(byId).getOrDefault(rack.getId(), RackLoad.EMPTY);
        BigDecimal kg = unit.getWeightKg() == null ? BigDecimal.ZERO : unit.getWeightKg();
        RackLoad after = now.plus(1, kg);
        if (after.exceedsPieces(rack.getMaxPieces())) {
            throw BusinessException.onField("place", "receipt.rack.pieces", rack.getCode(), now.pieces(), rack.getMaxPieces(), 1);
        }
        if (after.exceedsKg(rack.getMaxWeightKg())) {
            throw BusinessException.onField("place", "receipt.rack.weight", rack.getCode(), now.kg(), rack.getMaxWeightKg(), kg);
        }
    }

    private AuditCase lock(UUID id) {
        repo.lockById(id).orElseThrow(() -> new NotFoundException("AuditCase", id));
        return findDetailed(id);
    }
}
