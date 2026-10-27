package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.*;
import com.ntaganira.heritier.iWarehouse.enums.BreakageStatus;
import com.ntaganira.heritier.iWarehouse.enums.StockStatus;
import com.ntaganira.heritier.iWarehouse.enums.TripLineOutcome;
import com.ntaganira.heritier.iWarehouse.enums.TripStatus;
import com.ntaganira.heritier.iWarehouse.enums.UnitKind;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.*;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import org.springframework.stereotype.Service;
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
 * - File      : TripReturnService.java
 * - Date      : 2026. 10. 11.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : A trip back at the warehouse (FLT-09, FLT-10). Its units are scanned off the vehicle (a label that is no
 *               unit expected back is kept as unexpected), then the supervisor reconciles it once every breakage reported
 *               on the road is decided: expected back = loaded - sold - breakage accepted, compared with the scans
 *               (TripReconciliation). Units scanned back go on the rack they left from, or the place given; broken units
 *               leave stock to spoilage at their own cost (PostingService.tripReturn); missing units stay on the vehicle and,
 *               with any unexpected label, open the trip's audit case (the owner is told). Reconciling locks the trip, then
 *               the products of the broken units, and reads the stock held before changing it.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class TripReturnService {

    private final TripRepository tripRepo;
    private final TripBreakageRepository breakageRepo;
    private final TripReturnExtraRepository extraRepo;
    private final StockUnitRepository unitRepo;
    private final ProductRepository productRepo;
    private final LocationService locationService;
    private final StockService stockService;
    private final PostingService postingService;
    private final AuditCaseService auditCaseService;
    private final Clock clock;

    public TripReturnService(TripRepository tripRepo, TripBreakageRepository breakageRepo, TripReturnExtraRepository extraRepo,
                             StockUnitRepository unitRepo, ProductRepository productRepo, LocationService locationService,
                             StockService stockService, PostingService postingService, AuditCaseService auditCaseService, Clock clock) {
        this.tripRepo = tripRepo;
        this.breakageRepo = breakageRepo;
        this.extraRepo = extraRepo;
        this.unitRepo = unitRepo;
        this.productRepo = productRepo;
        this.locationService = locationService;
        this.stockService = stockService;
        this.postingService = postingService;
        this.auditCaseService = auditCaseService;
        this.clock = clock;
    }

    /** What a scanned label did: a unit of the manifest back (or sold and yet on board), scanned before, kept as unexpected, or a place. */
    public enum ScanOutcome {
        BACK, SOLD_BACK, ALREADY, EXTRA, PLACE
    }

    public record Scanned(String code, ScanOutcome outcome) {
    }

    public record ScanResult(Trip trip, List<Scanned> scans) {

        public List<Scanned> of(ScanOutcome outcome) {
            return scans.stream().filter(s -> s.outcome() == outcome).toList();
        }
    }

    /** A trip reconciled: what became of its units, and the audit case its differences opened (or none). */
    public record Reconciled(Trip trip, TripReconciliation.Result result, AuditCase auditCase) {
    }

    /** The labels scanned at a trip's return that are no unit expected back. */
    public List<TripReturnExtra> extras(UUID tripId) {
        return extraRepo.findByTrip_IdOrderByScannedAtAscCodeAsc(tripId);
    }

    /** Scans units off the vehicle (FLT-09): a scanner sends each label on its own line. */
    @Transactional
    public ScanResult scanBack(UUID tripId, String codesText) {
        Trip trip = lock(tripId);
        requireDeparted(trip);
        List<String> codes = StockTransferService.parseCodes(codesText);
        if (codes.isEmpty()) {
            throw BusinessException.onField("codes", "trip.return.scanRequired");
        }
        if (codes.size() > TripService.MAX_CODES) {
            throw BusinessException.onField("codes", "trip.codes.tooMany", TripService.MAX_CODES, codes.size());
        }
        Map<String, TripLine> byCode = trip.getLines().stream().collect(Collectors.toMap(TripLine::getUnitCode, Function.identity()));
        Map<UUID, StockUnit> manifestUnits = unitRepo.findByIdIn(trip.getLines().stream().map(TripLine::getStockUnitId).toList()).stream()
                .collect(Collectors.toMap(StockUnit::getId, Function.identity()));
        List<String> others = codes.stream().filter(c -> !byCode.containsKey(c)).toList();
        Map<String, StockUnit> otherUnits = others.isEmpty() ? Map.of()
                : unitRepo.findByCodeIn(others).stream().collect(Collectors.toMap(StockUnit::getCode, Function.identity()));
        Set<String> kept = extraRepo.findByTrip_IdOrderByScannedAtAscCodeAsc(tripId).stream().map(TripReturnExtra::getCode)
                .collect(Collectors.toCollection(HashSet::new));

        LocalDateTime now = LocalDateTime.now(clock);
        String user = AppUserPrincipal.currentUsername();
        List<Scanned> scans = new ArrayList<>();
        for (String code : codes) {
            TripLine line = byCode.get(code);
            if (line != null) {
                if (line.isScannedBack()) {
                    scans.add(new Scanned(code, ScanOutcome.ALREADY));
                    continue;
                }
                line.setReturnedAt(now);
                line.setReturnedBy(user);
                StockUnit unit = manifestUnits.get(line.getStockUnitId());
                boolean sold = unit != null && unit.getStatus() == StockStatus.SOLD;
                scans.add(new Scanned(code, sold ? ScanOutcome.SOLD_BACK : ScanOutcome.BACK));
            } else if (kept.contains(code)) {
                scans.add(new Scanned(code, ScanOutcome.ALREADY));
            } else if (!otherUnits.containsKey(code) && locationService.findByCode(code).isPresent()) {
                scans.add(new Scanned(code, ScanOutcome.PLACE));
            } else {
                TripReturnExtra extra = new TripReturnExtra();
                extra.setTrip(trip);
                extra.setCode(code);
                extra.setStockUnitId(otherUnits.containsKey(code) ? otherUnits.get(code).getId() : null);
                extra.setScannedAt(now);
                extra.setScannedBy(user);
                extraRepo.save(extra);
                kept.add(code);
                scans.add(new Scanned(code, ScanOutcome.EXTRA));
            }
        }
        return new ScanResult(trip, scans);
    }

    /** A unit scanned back by mistake, before the trip is reconciled. */
    @Transactional
    public TripLine clearScan(UUID tripId, UUID lineId) {
        Trip trip = lock(tripId);
        requireDeparted(trip);
        TripLine line = trip.getLines().stream().filter(l -> l.getId().equals(lineId)).findFirst()
                .orElseThrow(() -> new NotFoundException("TripLine", lineId));
        line.setReturnedAt(null);
        line.setReturnedBy(null);
        return line;
    }

    /** A label kept as unexpected that was scanned by mistake, before the trip is reconciled. */
    @Transactional
    public TripReturnExtra removeExtra(UUID tripId, UUID extraId) {
        Trip trip = lock(tripId);
        requireDeparted(trip);
        TripReturnExtra extra = extraRepo.findById(extraId).filter(e -> e.getTrip().getId().equals(trip.getId()))
                .orElseThrow(() -> new NotFoundException("TripReturnExtra", extraId));
        extraRepo.delete(extra);
        return extra;
    }

    /** What reconciling now would give, for the trip's page: the scans compared with what is expected back. */
    public TripReconciliation.Result preview(Trip trip) {
        return reconcile(trip, units(trip), acceptedUnits(trip.getId()), extras(trip.getId()));
    }

    /**
     * Reconciles the trip (FLT-09): every breakage decided first. Units scanned back go on the place given, or the rack each
     * left from; broken units leave stock; missing and unexpected units open the trip's audit case (FLT-10).
     */
    @Transactional
    public Reconciled reconcile(UUID tripId, String placeCode) {
        Trip trip = lock(tripId);
        requireDeparted(trip);
        long undecided = breakageRepo.countByTrip_IdAndStatus(trip.getId(), BreakageStatus.REPORTED);
        if (undecided > 0) {
            throw BusinessException.of("trip.return.breakageUndecided", trip.getNumber(), undecided);
        }
        Map<UUID, StockUnit> units = units(trip);
        List<TripBreakage> breakages = breakageRepo.findByTrip_IdOrderByHappenedAtAscUnitCodeAsc(trip.getId());
        Map<UUID, TripBreakage> accepted = breakages.stream().filter(TripBreakage::isAccepted)
                .collect(Collectors.toMap(TripBreakage::getStockUnitId, Function.identity()));
        TripReconciliation.Result result = reconcile(trip, units, accepted.keySet(), extras(trip.getId()));
        if (!result.canReconcile()) {
            throw BusinessException.of("trip.return.elsewhere", String.join(", ", result.elsewhere()));
        }

        // Where each unit back goes: the place given, else the rack or slot it left from (still in use)
        Map<UUID, Location> byId = stockService.locationsById();
        Set<UUID> storage = stockService.storagePlaces(byId).stream().map(Location::getId).collect(Collectors.toSet());
        Location place = null;
        if (StringUtils.hasText(placeCode)) {
            place = locationService.findByCode(placeCode).filter(l -> storage.contains(l.getId()))
                    .orElseThrow(() -> BusinessException.onField("place", "trip.return.place.invalid", placeCode.trim()));
        }
        List<TripLine> lines = TripService.sortedLines(trip);
        Map<UUID, Location> putBack = new LinkedHashMap<>();
        List<String> nowhere = new ArrayList<>();
        for (TripLine line : lines) {
            if (result.outcomes().get(line.getStockUnitId()) != TripLineOutcome.RETURNED) {
                continue;
            }
            Location to = place != null ? place
                    : line.getFromLocationId() != null && storage.contains(line.getFromLocationId()) ? byId.get(line.getFromLocationId()) : null;
            if (to == null) {
                nowhere.add(line.getUnitCode());
            } else {
                putBack.put(line.getStockUnitId(), to);
            }
        }
        if (!nowhere.isEmpty()) {
            throw BusinessException.onField("place", "trip.return.place.needed", String.join(", ", nowhere));
        }
        checkRacks(putBack, units, byId);

        // The glass of the broken units: locked, and its stock taken before it changes
        List<StockUnit> broken = lines.stream().filter(l -> result.outcomes().get(l.getStockUnitId()) == TripLineOutcome.BROKEN)
                .map(l -> units.get(l.getStockUnitId())).toList();
        Map<UUID, Product> products = Map.of();
        Map<UUID, BigDecimal> heldBefore = new HashMap<>();
        PostingService.StockValues before = null;
        if (!broken.isEmpty()) {
            Set<UUID> productIds = broken.stream().map(u -> u.getProduct().getId()).collect(Collectors.toSet());
            products = productRepo.lockAllById(productIds).stream().collect(Collectors.toMap(Product::getId, Function.identity()));
            for (UUID productId : productIds) {
                heldBefore.put(productId, stockService.heldArea(productId));
            }
            before = postingService.stockValues(products.values());
        }

        StockLeaving leaving = new StockLeaving();
        for (TripLine line : lines) {
            TripLineOutcome outcome = result.outcomes().get(line.getStockUnitId());
            StockUnit unit = units.get(line.getStockUnitId());
            line.setOutcome(outcome);
            if (outcome == TripLineOutcome.RETURNED) {
                Location to = putBack.get(unit.getId());
                stockService.unload(unit, to, StockService.REF_TRIP, trip.getId(), trip.getNumber());
                line.setReturnedToLocationId(to.getId());
            } else if (outcome == TripLineOutcome.BROKEN) {
                stockService.breakOnVehicle(unit, accepted.get(unit.getId()).getReason(), trip.getId(), trip.getNumber());
                leaving.add(unit);
            }
        }
        if (!leaving.isEmpty()) {
            leaving.moveMacs(products, heldBefore);
        }
        AuditCase auditCase = result.hasDifference() ? auditCaseService.open(trip, result.missing(), result.unexpected(), units) : null;

        trip.setReturnedPieces((int) result.count(TripLineOutcome.RETURNED));
        trip.setSoldPieces((int) result.count(TripLineOutcome.SOLD));
        trip.setBrokenPieces((int) result.count(TripLineOutcome.BROKEN));
        trip.setMissingPieces((int) result.count(TripLineOutcome.MISSING));
        trip.setReturnedAt(LocalDateTime.now(clock));
        trip.setReturnedBy(AppUserPrincipal.currentUsername());
        trip.setStatus(TripStatus.RETURNED);
        if (!leaving.isEmpty()) {
            postingService.tripReturn(trip, leaving.cost(), before);
        }
        return new Reconciled(trip, result, auditCase);
    }

    private static TripReconciliation.Result reconcile(Trip trip, Map<UUID, StockUnit> units, Set<UUID> accepted, List<TripReturnExtra> extras) {
        List<TripReconciliation.Line> lines = TripService.sortedLines(trip).stream().map(l -> {
            StockUnit unit = units.get(l.getStockUnitId());
            return new TripReconciliation.Line(l.getStockUnitId(), l.getUnitCode(), unit.getStatus(), l.isScannedBack(),
                    accepted.contains(l.getStockUnitId()));
        }).toList();
        return TripReconciliation.reconcile(lines, extras.stream().map(e -> new TripReconciliation.Extra(e.getCode(), e.getStockUnitId())).toList());
    }

    /** Glass going back fits its rack's piece and weight limits (MD-03); a full sheet never goes on an off-cut rack. */
    private void checkRacks(Map<UUID, Location> putBack, Map<UUID, StockUnit> units, Map<UUID, Location> byId) {
        Map<Location, List<StockUnit>> byRack = new LinkedHashMap<>();
        putBack.forEach((unitId, to) -> byRack.computeIfAbsent(StockService.rackOf(to, byId), k -> new ArrayList<>()).add(units.get(unitId)));
        Map<UUID, RackLoad> loads = byRack.isEmpty() ? Map.of() : stockService.rackLoads(byId);
        for (Map.Entry<Location, List<StockUnit>> e : byRack.entrySet()) {
            Location rack = e.getKey();
            List<StockUnit> going = e.getValue();
            if (rack.isOffcut()) {
                going.stream().filter(u -> u.getKind() == UnitKind.SHEET).findFirst().ifPresent(u -> {
                    throw BusinessException.onField("place", "trip.return.sheetToOffcut", u.getCode(), rack.getCode());
                });
            }
            RackLoad now = loads.getOrDefault(rack.getId(), RackLoad.EMPTY);
            BigDecimal kg = going.stream().map(StockUnit::getWeightKg).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
            RackLoad after = now.plus(going.size(), kg);
            if (after.exceedsPieces(rack.getMaxPieces())) {
                throw BusinessException.onField("place", "receipt.rack.pieces", rack.getCode(), now.pieces(), rack.getMaxPieces(), going.size());
            }
            if (after.exceedsKg(rack.getMaxWeightKg())) {
                throw BusinessException.onField("place", "receipt.rack.weight", rack.getCode(), now.kg(), rack.getMaxWeightKg(), kg);
            }
        }
    }

    private Map<UUID, StockUnit> units(Trip trip) {
        return unitRepo.findByIdIn(trip.getLines().stream().map(TripLine::getStockUnitId).toList()).stream()
                .collect(Collectors.toMap(StockUnit::getId, Function.identity()));
    }

    private Set<UUID> acceptedUnits(UUID tripId) {
        return breakageRepo.findByTrip_IdOrderByHappenedAtAscUnitCodeAsc(tripId).stream().filter(TripBreakage::isAccepted)
                .map(TripBreakage::getStockUnitId).collect(Collectors.toSet());
    }

    private Trip lock(UUID id) {
        tripRepo.lockById(id).orElseThrow(() -> new NotFoundException("Trip", id));
        return tripRepo.findDetailedById(id).orElseThrow(() -> new NotFoundException("Trip", id));
    }

    private static void requireDeparted(Trip trip) {
        if (!trip.isDeparted()) {
            throw TripService.notState(trip);
        }
    }
}
