package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.dto.VehicleDto;
import com.ntaganira.heritier.iWarehouse.entity.Location;
import com.ntaganira.heritier.iWarehouse.entity.StockUnit;
import com.ntaganira.heritier.iWarehouse.entity.Trip;
import com.ntaganira.heritier.iWarehouse.entity.Vehicle;
import com.ntaganira.heritier.iWarehouse.enums.LocationType;
import com.ntaganira.heritier.iWarehouse.enums.SettingKey;
import com.ntaganira.heritier.iWarehouse.enums.StockStatus;
import com.ntaganira.heritier.iWarehouse.enums.TripStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.LocationRepository;
import com.ntaganira.heritier.iWarehouse.repository.StockUnitRepository;
import com.ntaganira.heritier.iWarehouse.repository.TripRepository;
import com.ntaganira.heritier.iWarehouse.repository.VehicleRepository;
import jakarta.persistence.criteria.Predicate;
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
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : VehicleService.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : Vehicles (FLT-01, FLT-02). Adding a vehicle makes its VEHICLE location (no parent, code VEH-plate, its
 *               model as name); a new plate or model renames it, deactivating the vehicle deactivates it. A vehicle is
 *               deactivated only with no trip planned or on the road and nothing on board. Its papers are VALID, SOON or
 *               EXPIRED against the Settings' alert days (FLT-04).
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class VehicleService {

    /** Prefix of a vehicle location's code. */
    static final String LOCATION_PREFIX = "VEH-";

    private final VehicleRepository repo;
    private final LocationRepository locationRepo;
    private final TripRepository tripRepo;
    private final StockUnitRepository unitRepo;
    private final SettingService settings;
    private final Clock clock;

    public VehicleService(VehicleRepository repo, LocationRepository locationRepo, TripRepository tripRepo,
                          StockUnitRepository unitRepo, SettingService settings, Clock clock) {
        this.repo = repo;
        this.locationRepo = locationRepo;
        this.tripRepo = tripRepo;
        this.unitRepo = unitRepo;
        this.settings = settings;
        this.clock = clock;
    }

    /** What a vehicle carries now: pieces and kg of the units on board. */
    public record OnBoard(long pieces, BigDecimal kg) {
    }

    public Page<Vehicle> findPage(String search, String status, int page, int size) {
        Specification<Vehicle> spec = (root, query, cb) -> {
            Predicate p = cb.conjunction();
            if (StringUtils.hasText(search)) {
                String term = "%" + search.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "") + "%";
                String words = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                p = cb.and(p, cb.or(cb.like(cb.lower(root.get("plate")), term), cb.like(cb.lower(root.get("model")), words)));
            }
            if ("active".equals(status)) {
                p = cb.and(p, cb.isTrue(root.get("enabled")));
            } else if ("inactive".equals(status)) {
                p = cb.and(p, cb.isFalse(root.get("enabled")));
            }
            return p;
        };
        return repo.findAll(spec, PageRequest.of(page, size, Sort.by("plate")));
    }

    public Vehicle findDetailed(UUID id) {
        return repo.findDetailedById(id).orElseThrow(() -> new NotFoundException("Vehicle", id));
    }

    /** Vehicles a trip or a driver can take. */
    public List<Vehicle> active() {
        return repo.findByEnabledTrueOrderByPlate();
    }

    /** The vehicle of a VEHICLE location, for the Locations screen. */
    public Optional<Vehicle> ofLocation(UUID locationId) {
        return repo.findByLocation_Id(locationId);
    }

    public int alertDays() {
        return settings.getInt(SettingKey.FLEET_EXPIRY_ALERT_DAYS);
    }

    /** Insurance and inspection: VALID, SOON or EXPIRED today (FLT-04). */
    public Map<FleetPapers.Paper, FleetPapers.Stage> papers(Vehicle vehicle) {
        LocalDate today = LocalDate.now(clock);
        int days = alertDays();
        Map<FleetPapers.Paper, FleetPapers.Stage> stages = new EnumMap<>(FleetPapers.Paper.class);
        stages.put(FleetPapers.Paper.INSURANCE, FleetPapers.stage(vehicle.getInsuranceExpiry(), today, days));
        stages.put(FleetPapers.Paper.INSPECTION, FleetPapers.stage(vehicle.getInspectionExpiry(), today, days));
        return stages;
    }

    /** The trip it is on, if any. */
    public Optional<Trip> onTheRoad(UUID vehicleId) {
        return tripRepo.findFirstByVehicle_IdAndStatus(vehicleId, TripStatus.DEPARTED);
    }

    public Page<Trip> trips(UUID vehicleId, int page, int size) {
        return tripRepo.findByVehicle_Id(vehicleId, PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "tripDate").and(Sort.by(Sort.Direction.DESC, "number"))));
    }

    /** The units on board, by code. */
    public List<StockUnit> unitsOnBoard(Vehicle vehicle) {
        return unitRepo.findByLocation_IdInAndStatusIn(List.of(vehicle.getLocation().getId()), StockStatus.onHand()).stream()
                .sorted(java.util.Comparator.comparing(StockUnit::getCode))
                .toList();
    }

    public OnBoard onBoard(List<StockUnit> units) {
        return new OnBoard(units.size(), units.stream().map(StockUnit::getWeightKg).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    @Transactional
    public Vehicle create(VehicleDto dto) {
        if (repo.existsByPlate(dto.getPlate())) {
            throw BusinessException.onField("plate", "vehicle.plate.taken", dto.getPlate());
        }
        String code = locationCode(dto.getPlate());
        if (locationRepo.existsByCode(code)) {
            throw BusinessException.onField("plate", "vehicle.location.taken", code);
        }
        Location location = new Location();
        location.setType(LocationType.VEHICLE);
        location.setCode(code);
        location.setName(dto.getModel().trim());
        locationRepo.save(location);

        Vehicle vehicle = new Vehicle();
        vehicle.setLocation(location);
        apply(vehicle, dto);
        return repo.save(vehicle);
    }

    @Transactional
    public Vehicle update(UUID id, VehicleDto dto) {
        Vehicle vehicle = findDetailed(id);
        Location location = vehicle.getLocation();
        if (!dto.getPlate().equals(vehicle.getPlate())) {
            if (repo.existsByPlateAndIdNot(dto.getPlate(), id)) {
                throw BusinessException.onField("plate", "vehicle.plate.taken", dto.getPlate());
            }
            String code = locationCode(dto.getPlate());
            if (locationRepo.existsByCodeAndIdNot(code, location.getId())) {
                throw BusinessException.onField("plate", "vehicle.location.taken", code);
            }
            location.setCode(code);
        }
        location.setName(dto.getModel().trim());
        apply(vehicle, dto);
        return vehicle;
    }

    /**
     * Deactivates (no trip planned or on the road, nothing on board) or reactivates a vehicle; its location follows, so
     * nothing is moved to a vehicle that is gone.
     */
    @Transactional
    public Vehicle setEnabled(UUID id, boolean enabled) {
        Vehicle vehicle = findDetailed(id);
        if (!enabled) {
            if (tripRepo.existsByVehicle_IdAndStatusIn(id, List.of(TripStatus.PLANNED, TripStatus.DEPARTED))) {
                throw BusinessException.of("vehicle.disable.trips", vehicle.getPlate());
            }
            long onBoard = unitRepo.countByLocation_IdAndStatusIn(vehicle.getLocation().getId(), StockStatus.onHand());
            if (onBoard > 0) {
                throw BusinessException.of("vehicle.disable.stock", vehicle.getPlate(), onBoard);
            }
        }
        vehicle.setEnabled(enabled);
        vehicle.getLocation().setEnabled(enabled);
        return vehicle;
    }

    static String locationCode(String plate) {
        return LOCATION_PREFIX + plate;
    }

    private static void apply(Vehicle vehicle, VehicleDto dto) {
        vehicle.setPlate(dto.getPlate());
        vehicle.setModel(dto.getModel().trim());
        vehicle.setRackConfiguration(PartyRules.clean(dto.getRackConfiguration()));
        vehicle.setMaxLoadKg(dto.getMaxLoadKg());
        vehicle.setMaxPieces(dto.getMaxPieces());
        vehicle.setInsuranceExpiry(dto.getInsuranceExpiry());
        vehicle.setInspectionExpiry(dto.getInspectionExpiry());
        vehicle.setNotes(PartyRules.clean(dto.getNotes()));
    }
}
