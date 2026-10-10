package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.config.NumberFormats;
import com.ntaganira.heritier.iWarehouse.dto.TripDto;
import com.ntaganira.heritier.iWarehouse.dto.TripFuelDto;
import com.ntaganira.heritier.iWarehouse.entity.Driver;
import com.ntaganira.heritier.iWarehouse.entity.Location;
import com.ntaganira.heritier.iWarehouse.entity.StockUnit;
import com.ntaganira.heritier.iWarehouse.entity.Trip;
import com.ntaganira.heritier.iWarehouse.entity.TripFuel;
import com.ntaganira.heritier.iWarehouse.entity.TripLine;
import com.ntaganira.heritier.iWarehouse.entity.User;
import com.ntaganira.heritier.iWarehouse.entity.Vehicle;
import com.ntaganira.heritier.iWarehouse.enums.DocumentType;
import com.ntaganira.heritier.iWarehouse.enums.LocationType;
import com.ntaganira.heritier.iWarehouse.enums.NotificationKind;
import com.ntaganira.heritier.iWarehouse.enums.StockAction;
import com.ntaganira.heritier.iWarehouse.enums.StockStatus;
import com.ntaganira.heritier.iWarehouse.enums.TripStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.DriverRepository;
import com.ntaganira.heritier.iWarehouse.repository.StockUnitRepository;
import com.ntaganira.heritier.iWarehouse.repository.TripRepository;
import com.ntaganira.heritier.iWarehouse.repository.VehicleRepository;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : TripService.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : Moving-shop trips (FLT-04..07, FLT-12). Planning a trip takes an active vehicle and driver whose papers
 *               cover its day (FLT-04). Its manifest takes available units nothing else holds, scanned or typed, or every
 *               available unit of a rack or slot whose label is scanned; it holds them while the trip is planned (INV-05).
 *               Loading is confirmed by scanning each unit; a label not on the manifest is refused (FLT-06). Departure
 *               needs every planned unit scanned, the papers valid today, the vehicle and driver on no other trip and the
 *               load within the vehicle's pieces and kg (AT-03); it then moves each unit to the vehicle, ON_VEHICLE, with a
 *               LOAD movement, and the driver is told the units are in their charge (FLT-07). Changes lock the trip; the
 *               departure locks the trip, then the vehicle, then the driver.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class TripService {

    /** Most units one manifest holds. */
    static final int MAX_LINES = 500;
    /** Most labels one scan or one addition takes. */
    static final int MAX_CODES = 200;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final TripRepository repo;
    private final VehicleRepository vehicleRepo;
    private final DriverRepository driverRepo;
    private final StockUnitRepository unitRepo;
    private final StockService stockService;
    private final DocumentNumberService numbers;
    private final Notifier notifier;
    private final NumberFormats num;
    private final Clock clock;

    public TripService(TripRepository repo, VehicleRepository vehicleRepo, DriverRepository driverRepo, StockUnitRepository unitRepo,
                       StockService stockService, DocumentNumberService numbers, Notifier notifier, NumberFormats num, Clock clock) {
        this.repo = repo;
        this.vehicleRepo = vehicleRepo;
        this.driverRepo = driverRepo;
        this.unitRepo = unitRepo;
        this.stockService = stockService;
        this.numbers = numbers;
        this.notifier = notifier;
        this.num = num;
        this.clock = clock;
    }

    /** Trip list filters; driverUserId limits the list to that driver's trips (a driver without PERM_VIEW_TRIP). */
    public record Filter(String search, TripStatus status, UUID vehicleId, Long driverUserId) {
    }

    /** Units planned and scanned on a trip's manifest. */
    public record Counts(long planned, long loaded) {
        public static final Counts NONE = new Counts(0, 0);
    }

    /** What adding labels to a manifest did: units added, those already on it, units of a place others hold. */
    public record AddResult(Trip trip, List<String> added, List<String> already, int skipped, TripLoading.Check planned) {
    }

    /** One label scanned onto a vehicle: what it did, and whether any unit has it. */
    public record Scanned(String code, TripLoading.ScanOutcome outcome, boolean known) {
    }

    public record ScanResult(Trip trip, List<Scanned> scans, TripLoading.Check loaded) {

        public List<Scanned> refused() {
            return scans.stream().filter(s -> s.outcome() == TripLoading.ScanOutcome.NOT_ON_MANIFEST).toList();
        }

        public List<Scanned> loadedNow() {
            return scans.stream().filter(s -> s.outcome() == TripLoading.ScanOutcome.LOADED).toList();
        }
    }

    // ---------------------------------------------------------------- reading

    public Page<Trip> findPage(Filter f, int page, int size) {
        Specification<Trip> spec = (root, query, cb) -> {
            Predicate p = cb.conjunction();
            Join<Trip, Vehicle> vehicle = root.join("vehicle");
            Join<Trip, Driver> driver = root.join("driver");
            if (StringUtils.hasText(f.search())) {
                String term = "%" + f.search().trim().toLowerCase(Locale.ROOT) + "%";
                Join<Driver, User> user = driver.join("user");
                p = cb.and(p, cb.or(
                        cb.like(cb.lower(root.get("number")), term),
                        cb.like(cb.lower(root.get("area")), term),
                        cb.like(cb.lower(vehicle.get("plate")), term.replaceAll("\\s+", "")),
                        cb.like(cb.lower(user.get("fullName")), term)));
            }
            if (f.status() != null) {
                p = cb.and(p, cb.equal(root.get("status"), f.status()));
            }
            if (f.vehicleId() != null) {
                p = cb.and(p, cb.equal(vehicle.get("id"), f.vehicleId()));
            }
            if (f.driverUserId() != null) {
                p = cb.and(p, cb.equal(driver.get("user").get("id"), f.driverUserId()));
            }
            return p;
        };
        return repo.findAll(spec, PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "tripDate").and(Sort.by(Sort.Direction.DESC, "number"))));
    }

    /** Units planned and scanned per trip of a page, in one query. */
    public Map<UUID, Counts> counts(Collection<Trip> trips) {
        if (trips.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Counts> counts = new HashMap<>();
        for (Object[] row : repo.countLines(trips.stream().map(Trip::getId).toList())) {
            counts.put((UUID) row[0], new Counts(((Number) row[1]).longValue(), ((Number) row[2]).longValue()));
        }
        return counts;
    }

    public Trip findDetailed(UUID id) {
        return repo.findDetailedById(id).orElseThrow(() -> new NotFoundException("Trip", id));
    }

    public long countByStatus(TripStatus status) {
        return repo.countByStatus(status);
    }

    /** True when the current user may see the trip: PERM_VIEW_TRIP, or it is their own as its driver. */
    public static boolean canSee(Trip trip) {
        if (AppUserPrincipal.currentHas("PERM_VIEW_TRIP")) {
            return true;
        }
        Long me = AppUserPrincipal.current().map(AppUserPrincipal::getId).orElse(null);
        return me != null && me.equals(trip.getDriver().getUser().getId());
    }

    /** The manifest's units by id, with their glass and location. */
    public Map<UUID, StockUnit> units(Trip trip) {
        List<UUID> ids = trip.getLines().stream().map(TripLine::getStockUnitId).toList();
        return ids.isEmpty() ? Map.of() : unitRepo.findByIdIn(ids).stream().collect(Collectors.toMap(StockUnit::getId, Function.identity()));
    }

    /** The manifest's lines in the order they were planned, then by code (one addition shares its time). */
    public static List<TripLine> sortedLines(Trip trip) {
        return trip.getLines().stream()
                .sorted(Comparator.comparing(TripLine::getPlannedAt).thenComparing(TripLine::getUnitCode))
                .toList();
    }

    /** Pieces and kg of the units planned, or only of those scanned, against the vehicle's limits. */
    public static TripLoading.Check check(Trip trip, Map<UUID, StockUnit> units, boolean loadedOnly) {
        List<BigDecimal> weights = trip.getLines().stream()
                .filter(l -> !loadedOnly || l.isLoaded())
                .map(l -> units.get(l.getStockUnitId()))
                .filter(Objects::nonNull)
                .map(StockUnit::getWeightKg)
                .toList();
        Vehicle v = trip.getVehicle();
        return TripLoading.check(TripLoading.Load.of(weights), v.getMaxPieces(), v.getMaxLoadKg());
    }

    // ---------------------------------------------------------------- planning (FLT-04, FLT-05)

    @Transactional
    public Trip create(TripDto dto) {
        LocalDate today = LocalDate.now(clock);
        Vehicle vehicle = activeVehicle(dto.getVehicleId());
        Driver driver = activeDriver(dto.getDriverId());
        if (dto.getTripDate().isBefore(today)) {
            throw BusinessException.onField("tripDate", "trip.date.past");
        }
        requirePapers(driver, vehicle, dto.getTripDate(), true);
        Trip trip = new Trip();
        trip.setNumber(numbers.next(DocumentType.TRIP));
        apply(trip, dto, vehicle, driver);
        return repo.save(trip);
    }

    @Transactional
    public Trip update(UUID id, TripDto dto) {
        Trip trip = lock(id);
        requirePlanned(trip);
        Vehicle vehicle = activeVehicle(dto.getVehicleId());
        Driver driver = activeDriver(dto.getDriverId());
        if (!dto.getTripDate().equals(trip.getTripDate()) && dto.getTripDate().isBefore(LocalDate.now(clock))) {
            throw BusinessException.onField("tripDate", "trip.date.past");
        }
        requirePapers(driver, vehicle, dto.getTripDate(), true);
        apply(trip, dto, vehicle, driver);
        return trip;
    }

    @Transactional
    public Trip cancel(UUID id, String reason) {
        Trip trip = lock(id);
        requirePlanned(trip);
        trip.setCancelledAt(LocalDateTime.now(clock));
        trip.setCancelledBy(AppUserPrincipal.currentUsername());
        trip.setCancelReason(reason.trim());
        trip.setStatus(TripStatus.CANCELLED);
        return trip;
    }

    // ---------------------------------------------------------------- the manifest (FLT-05)

    /**
     * Adds units to a planned trip's manifest: each label scanned or typed, or every available unit on a rack or slot
     * (and the slots of a rack) whose label is among them, other than those another document holds.
     */
    @Transactional
    public AddResult addUnits(UUID id, String codesText) {
        Trip trip = lock(id);
        requirePlanned(trip);
        List<String> codes = StockTransferService.parseCodes(codesText);
        if (codes.isEmpty()) {
            throw BusinessException.onField("codes", "trip.codes.required");
        }
        if (codes.size() > MAX_CODES) {
            throw BusinessException.onField("codes", "trip.codes.tooMany", MAX_CODES, codes.size());
        }
        Map<String, StockUnit> found = unitRepo.findByCodeIn(codes).stream()
                .collect(Collectors.toMap(StockUnit::getCode, Function.identity()));
        Map<UUID, Location> byId = stockService.locationsById();
        Map<String, Location> places = stockService.storagePlaces(byId).stream()
                .collect(Collectors.toMap(Location::getCode, Function.identity()));
        List<String> unknown = codes.stream().filter(c -> !found.containsKey(c) && !places.containsKey(c)).toList();
        if (!unknown.isEmpty()) {
            throw BusinessException.onField("codes", "trip.codes.unknown", String.join(", ", unknown));
        }
        Set<UUID> onManifest = trip.getLines().stream().map(TripLine::getStockUnitId).collect(Collectors.toSet());

        // Units named one by one must be loadable and free
        List<StockUnit> named = codes.stream().filter(found::containsKey).map(found::get).toList();
        List<String> already = new ArrayList<>(named.stream().filter(u -> onManifest.contains(u.getId())).map(StockUnit::getCode).toList());
        List<StockUnit> wanted = new ArrayList<>(named.stream().filter(u -> !onManifest.contains(u.getId())).toList());
        refuse(wanted, u -> !StockAction.LOAD.allows(u.getStatus()), "trip.codes.notLoadable");
        Map<UUID, String> holds = stockService.holds(wanted.stream().map(StockUnit::getId).toList());
        List<String> held = wanted.stream().filter(u -> holds.containsKey(u.getId()))
                .map(u -> u.getCode() + " (" + holds.get(u.getId()) + ")").toList();
        if (!held.isEmpty()) {
            throw BusinessException.onField("codes", "trip.codes.held", String.join(", ", held));
        }

        // A rack or slot label adds what is available there and free
        int skipped = 0;
        List<Location> scannedPlaces = codes.stream().filter(c -> !found.containsKey(c)).map(places::get).toList();
        if (!scannedPlaces.isEmpty()) {
            Set<UUID> placeIds = new HashSet<>();
            for (Location place : scannedPlaces) {
                placeIds.add(place.getId());
                if (place.getType() == LocationType.RACK) {
                    byId.values().stream().filter(l -> place.getId().equals(l.getParentId())).forEach(l -> placeIds.add(l.getId()));
                }
            }
            Set<UUID> taken = wanted.stream().map(StockUnit::getId).collect(Collectors.toSet());
            List<StockUnit> there = unitRepo.findByLocation_IdInAndStatusIn(placeIds, List.of(StockStatus.AVAILABLE)).stream()
                    .filter(u -> !onManifest.contains(u.getId()) && !taken.contains(u.getId()))
                    .sorted(Comparator.comparing(StockUnit::getCode))
                    .toList();
            Map<UUID, String> placeHolds = stockService.holds(there.stream().map(StockUnit::getId).toList());
            for (StockUnit u : there) {
                if (placeHolds.containsKey(u.getId())) {
                    skipped++;
                } else {
                    wanted.add(u);
                }
            }
            if (wanted.isEmpty() && already.isEmpty()) {
                throw BusinessException.onField("codes", "trip.codes.placeEmpty",
                        scannedPlaces.stream().map(Location::getCode).collect(Collectors.joining(", ")));
            }
        }
        if (trip.getLines().size() + wanted.size() > MAX_LINES) {
            throw BusinessException.onField("codes", "trip.codes.manifestFull", MAX_LINES, trip.getLines().size());
        }

        LocalDateTime now = LocalDateTime.now(clock);
        String user = AppUserPrincipal.currentUsername();
        for (StockUnit unit : wanted) {
            TripLine line = new TripLine();
            line.setTrip(trip);
            line.setStockUnitId(unit.getId());
            line.setUnitCode(unit.getCode());
            line.setPlannedAt(now);
            line.setPlannedBy(user);
            trip.getLines().add(line);
        }
        Map<UUID, StockUnit> units = units(trip);
        wanted.forEach(u -> units.putIfAbsent(u.getId(), u));
        return new AddResult(trip, wanted.stream().map(StockUnit::getCode).toList(), already, skipped, check(trip, units, false));
    }

    /** Takes a unit off a planned trip's manifest, scanned or not: it is free again. */
    @Transactional
    public TripLine removeLine(UUID id, UUID lineId) {
        Trip trip = lock(id);
        requirePlanned(trip);
        TripLine line = trip.getLines().stream().filter(l -> l.getId().equals(lineId)).findFirst()
                .orElseThrow(() -> new NotFoundException("TripLine", lineId));
        trip.getLines().remove(line);
        return line;
    }

    // ---------------------------------------------------------------- loading (FLT-06)

    /** Confirms units on the vehicle by scanning their labels; a label not on the manifest is refused. */
    @Transactional
    public ScanResult scan(UUID id, String codesText) {
        Trip trip = lock(id);
        requirePlanned(trip);
        List<String> codes = StockTransferService.parseCodes(codesText);
        if (codes.isEmpty()) {
            throw BusinessException.of("trip.scan.required");
        }
        if (codes.size() > MAX_CODES) {
            throw BusinessException.of("trip.codes.tooMany", MAX_CODES, codes.size());
        }
        Map<String, TripLine> byCode = trip.getLines().stream().collect(Collectors.toMap(TripLine::getUnitCode, Function.identity()));
        Map<String, Boolean> manifest = new HashMap<>();
        byCode.forEach((code, line) -> manifest.put(code, line.isLoaded()));
        List<String> notOnManifest = codes.stream().filter(c -> !manifest.containsKey(c)).toList();
        Set<String> known = notOnManifest.isEmpty() ? Set.of()
                : unitRepo.findByCodeIn(notOnManifest).stream().map(StockUnit::getCode).collect(Collectors.toSet());

        LocalDateTime now = LocalDateTime.now(clock);
        String user = AppUserPrincipal.currentUsername();
        List<Scanned> scans = new ArrayList<>();
        for (String code : codes) {
            TripLoading.ScanOutcome outcome = TripLoading.scan(manifest, code);
            if (outcome == TripLoading.ScanOutcome.LOADED) {
                TripLine line = byCode.get(code);
                line.setLoadedAt(now);
                line.setLoadedBy(user);
                manifest.put(code, true);
            }
            scans.add(new Scanned(code, outcome, outcome != TripLoading.ScanOutcome.NOT_ON_MANIFEST || known.contains(code)));
        }
        return new ScanResult(trip, scans, check(trip, units(trip), true));
    }

    // ---------------------------------------------------------------- departure (FLT-04, FLT-06, FLT-07)

    /**
     * The supervisor confirms the departure: every planned unit scanned, papers valid today, vehicle and driver on no other
     * trip, the load within the vehicle's limits. The units go on the vehicle, ON_VEHICLE, in the driver's charge.
     */
    @Transactional
    public Trip depart(UUID id, Integer odometerStart) {
        Trip trip = lock(id);
        requirePlanned(trip);
        LocalDate today = LocalDate.now(clock);
        if (trip.getTripDate().isAfter(today)) {
            throw BusinessException.of("trip.depart.early", trip.getNumber(), trip.getTripDate().format(DAY));
        }
        Vehicle vehicle = vehicleRepo.lockById(trip.getVehicle().getId()).orElseThrow();
        Driver driver = driverRepo.lockById(trip.getDriver().getId()).orElseThrow();
        if (!vehicle.isEnabled()) {
            throw BusinessException.of("trip.vehicle.inactive", vehicle.getPlate());
        }
        if (!driver.isEnabled()) {
            throw BusinessException.of("trip.driver.inactive", driver.getUser().getFullName());
        }
        requirePapers(driver, vehicle, today, false);
        repo.findFirstByVehicle_IdAndStatus(vehicle.getId(), TripStatus.DEPARTED).ifPresent(other -> {
            throw BusinessException.of("trip.depart.vehicleOnRoad", vehicle.getPlate(), other.getNumber());
        });
        repo.findFirstByDriver_IdAndStatus(driver.getId(), TripStatus.DEPARTED).ifPresent(other -> {
            throw BusinessException.of("trip.depart.driverOnRoad", driver.getUser().getFullName(), other.getNumber());
        });
        if (trip.getLines().isEmpty()) {
            throw BusinessException.of("trip.depart.empty", trip.getNumber());
        }
        List<TripLine> lines = sortedLines(trip);
        List<String> notLoaded = lines.stream().filter(l -> !l.isLoaded()).map(TripLine::getUnitCode).toList();
        if (!notLoaded.isEmpty()) {
            throw BusinessException.of("trip.depart.notLoaded", notLoaded.size(), shortList(notLoaded));
        }
        Map<UUID, StockUnit> units = units(trip);
        List<StockUnit> loading = lines.stream().map(l -> units.get(l.getStockUnitId())).toList();
        List<String> gone = loading.stream().filter(u -> !StockAction.LOAD.allows(u.getStatus()) || u.getLocation() == null)
                .map(StockUnit::getCode).toList();
        if (!gone.isEmpty()) {
            throw BusinessException.of("trip.depart.notAvailable", String.join(", ", gone));
        }
        stockService.requireNotHeld(loading, trip.getNumber());
        TripLoading.Check check = check(trip, units, true);
        if (check.overPieces()) {
            throw BusinessException.of("trip.depart.overPieces", check.load().pieces(), vehicle.getPlate(), vehicle.getMaxPieces(),
                    check.piecesOver());
        }
        if (check.overKg()) {
            throw BusinessException.of("trip.depart.overKg", num.kg(check.load().kg()), vehicle.getPlate(),
                    num.kg(BigDecimal.valueOf(vehicle.getMaxLoadKg())), num.kg(check.kgOver()));
        }
        if (odometerStart != null) {
            if (odometerStart < 0) {
                throw BusinessException.onField("odometerStart", "trip.odometer.invalid");
            }
            if (vehicle.getOdometerKm() != null && odometerStart < vehicle.getOdometerKm()) {
                throw BusinessException.onField("odometerStart", "trip.odometer.belowLast", odometerStart, vehicle.getOdometerKm());
            }
        }

        for (TripLine line : lines) {
            StockUnit unit = units.get(line.getStockUnitId());
            line.setFromLocationId(unit.getLocation().getId());
            stockService.load(unit, vehicle.getLocation(), trip.getId(), trip.getNumber());
        }
        if (odometerStart != null) {
            vehicle.setOdometerKm(odometerStart);
        }
        trip.setOdometerStart(odometerStart);
        trip.setLoadedPieces(check.load().pieces());
        trip.setLoadedKg(check.load().kg());
        trip.setDepartedAt(LocalDateTime.now(clock));
        trip.setDepartedBy(AppUserPrincipal.currentUsername());
        trip.setStatus(TripStatus.DEPARTED);
        notifier.user(driver.getUser().getId(), NotificationKind.FLEET, "notify.trip.departed.title", "notify.trip.departed.message",
                "/trips/" + trip.getId(), trip.getNumber(), String.valueOf(check.load().pieces()), num.kg(check.load().kg()),
                vehicle.getPlate());
        return trip;
    }

    // ---------------------------------------------------------------- readings and fuel (FLT-12)

    /** The odometer of a trip on the road: its start when departure did not take it, and its end. */
    @Transactional
    public Trip recordReadings(UUID id, Integer odometerStart, Integer odometerEnd) {
        Trip trip = lock(id);
        if (!trip.isDeparted()) {
            throw notState(trip);
        }
        Integer start = trip.getOdometerStart() != null ? trip.getOdometerStart() : odometerStart;
        if (start == null && odometerEnd != null) {
            throw BusinessException.onField("odometerStart", "trip.odometer.startFirst");
        }
        if (start == null) {
            throw BusinessException.onField("odometerStart", "trip.odometer.required");
        }
        if (start < 0 || (odometerEnd != null && odometerEnd < 0)) {
            throw BusinessException.onField(start < 0 ? "odometerStart" : "odometerEnd", "trip.odometer.invalid");
        }
        if (odometerEnd != null && odometerEnd < start) {
            throw BusinessException.onField("odometerEnd", "trip.odometer.endBelowStart", odometerEnd, start);
        }
        Vehicle vehicle = trip.getVehicle();
        if (trip.getOdometerStart() == null && vehicle.getOdometerKm() != null && start < vehicle.getOdometerKm()) {
            throw BusinessException.onField("odometerStart", "trip.odometer.belowLast", start, vehicle.getOdometerKm());
        }
        trip.setOdometerStart(start);
        trip.setOdometerEnd(odometerEnd);
        int last = odometerEnd != null ? odometerEnd : start;
        if (vehicle.getOdometerKm() == null || vehicle.getOdometerKm() < last) {
            vehicle.setOdometerKm(last);
        }
        return trip;
    }

    @Transactional
    public TripFuel addFuel(UUID id, TripFuelDto dto) {
        Trip trip = lock(id);
        if (trip.isCancelled()) {
            throw notState(trip);
        }
        if (dto.getFilledOn().isAfter(LocalDate.now(clock))) {
            throw BusinessException.onField("filledOn", "tripFuel.date.future");
        }
        TripFuel fuel = new TripFuel();
        fuel.setTrip(trip);
        fuel.setFilledOn(dto.getFilledOn());
        fuel.setLitres(dto.getLitres());
        fuel.setAmount(dto.getAmount());
        fuel.setStation(PartyRules.clean(dto.getStation()));
        fuel.setNote(PartyRules.clean(dto.getNote()));
        trip.getFuel().add(fuel);
        return fuel;
    }

    /** Takes off a fuel entry made by mistake (the caller gives the reason to the change log). */
    @Transactional
    public TripFuel removeFuel(UUID id, UUID fuelId) {
        Trip trip = lock(id);
        if (trip.isCancelled()) {
            throw notState(trip);
        }
        TripFuel fuel = trip.getFuel().stream().filter(f -> f.getId().equals(fuelId)).findFirst()
                .orElseThrow(() -> new NotFoundException("TripFuel", fuelId));
        trip.getFuel().remove(fuel);
        return fuel;
    }

    public static BigDecimal fuelTotal(Trip trip) {
        return trip.getFuel().stream().map(TripFuel::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public static BigDecimal litresTotal(Trip trip) {
        return trip.getFuel().stream().map(TripFuel::getLitres).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ---------------------------------------------------------------- rules

    /**
     * The papers that must cover the day (FLT-04): the driver's licence, then the vehicle's insurance and inspection. When
     * planning, the refusal shows under the driver or vehicle field.
     */
    private void requirePapers(Driver driver, Vehicle vehicle, LocalDate day, boolean onField) {
        List<FleetPapers.Blocker> blockers = FleetPapers.blocking(day, driver.getLicenceExpiry(), vehicle.getInsuranceExpiry(),
                vehicle.getInspectionExpiry());
        if (blockers.isEmpty()) {
            return;
        }
        FleetPapers.Blocker b = blockers.get(0);
        boolean licence = b.paper() == FleetPapers.Paper.LICENCE;
        String who = licence ? driver.getUser().getFullName() : vehicle.getPlate();
        String key = "trip.papers." + b.paper().name();
        Object[] args = {who, b.expiry().format(DAY), day.format(DAY)};
        throw onField ? BusinessException.onField(licence ? "driverId" : "vehicleId", key, args) : BusinessException.of(key, args);
    }

    private Vehicle activeVehicle(UUID id) {
        Vehicle vehicle = vehicleRepo.findDetailedById(id).orElseThrow(() -> BusinessException.onField("vehicleId", "trip.vehicle.invalid"));
        if (!vehicle.isEnabled()) {
            throw BusinessException.onField("vehicleId", "trip.vehicle.inactive", vehicle.getPlate());
        }
        return vehicle;
    }

    private Driver activeDriver(UUID id) {
        Driver driver = driverRepo.findDetailedById(id).orElseThrow(() -> BusinessException.onField("driverId", "trip.driver.invalid"));
        if (!driver.isEnabled()) {
            throw BusinessException.onField("driverId", "trip.driver.inactive", driver.getUser().getFullName());
        }
        return driver;
    }

    private static void apply(Trip trip, TripDto dto, Vehicle vehicle, Driver driver) {
        trip.setVehicle(vehicle);
        trip.setDriver(driver);
        trip.setTripDate(dto.getTripDate());
        trip.setArea(dto.getArea().trim());
        trip.setNote(PartyRules.clean(dto.getNote()));
    }

    private Trip lock(UUID id) {
        repo.lockById(id).orElseThrow(() -> new NotFoundException("Trip", id));
        return findDetailed(id);
    }

    private static void requirePlanned(Trip trip) {
        if (!trip.isPlanned()) {
            throw notState(trip);
        }
    }

    private static BusinessException notState(Trip trip) {
        return BusinessException.of("trip.notAllowed", trip.getNumber(),
                new DefaultMessageSourceResolvable("trip.status." + trip.getStatus().name()));
    }

    private static void refuse(List<StockUnit> units, java.util.function.Predicate<StockUnit> refused, String key) {
        List<String> codes = units.stream().filter(refused).map(StockUnit::getCode).toList();
        if (!codes.isEmpty()) {
            throw BusinessException.onField("codes", key, String.join(", ", codes));
        }
    }

    /** The first ten codes, then "…". */
    private static String shortList(List<String> codes) {
        return codes.size() <= 10 ? String.join(", ", codes) : String.join(", ", codes.subList(0, 10)) + ", …";
    }
}
