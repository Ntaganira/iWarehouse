package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.dto.DriverDto;
import com.ntaganira.heritier.iWarehouse.entity.Driver;
import com.ntaganira.heritier.iWarehouse.entity.Trip;
import com.ntaganira.heritier.iWarehouse.entity.User;
import com.ntaganira.heritier.iWarehouse.entity.Vehicle;
import com.ntaganira.heritier.iWarehouse.enums.SettingKey;
import com.ntaganira.heritier.iWarehouse.enums.TripStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.DriverRepository;
import com.ntaganira.heritier.iWarehouse.repository.TripRepository;
import com.ntaganira.heritier.iWarehouse.repository.UserRepository;
import com.ntaganira.heritier.iWarehouse.repository.VehicleRepository;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : DriverService.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : Drivers (FLT-03): an enabled user registered once, with a national ID and a licence nobody else has, and
 *               the vehicle they usually drive (active). The user cannot change afterwards. A driver is deactivated only
 *               with no trip planned or on the road. Their licence is VALID, SOON or EXPIRED against the Settings' alert
 *               days (FLT-04).
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class DriverService {

    private final DriverRepository repo;
    private final UserRepository userRepo;
    private final VehicleRepository vehicleRepo;
    private final TripRepository tripRepo;
    private final SettingService settings;
    private final Clock clock;

    public DriverService(DriverRepository repo, UserRepository userRepo, VehicleRepository vehicleRepo, TripRepository tripRepo,
                         SettingService settings, Clock clock) {
        this.repo = repo;
        this.userRepo = userRepo;
        this.vehicleRepo = vehicleRepo;
        this.tripRepo = tripRepo;
        this.settings = settings;
        this.clock = clock;
    }

    public Page<Driver> findPage(String search, String status, int page, int size) {
        Specification<Driver> spec = (root, query, cb) -> {
            Predicate p = cb.conjunction();
            if (StringUtils.hasText(search)) {
                String term = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                Join<Driver, User> user = root.join("user");
                p = cb.and(p, cb.or(cb.like(cb.lower(user.get("fullName")), term), cb.like(cb.lower(root.get("username")), term)));
            }
            if ("active".equals(status)) {
                p = cb.and(p, cb.isTrue(root.get("enabled")));
            } else if ("inactive".equals(status)) {
                p = cb.and(p, cb.isFalse(root.get("enabled")));
            }
            return p;
        };
        return repo.findAll(spec, PageRequest.of(page, size, Sort.by("username")));
    }

    public Driver findDetailed(UUID id) {
        return repo.findDetailedById(id).orElseThrow(() -> new NotFoundException("Driver", id));
    }

    /** The driver record of a user, if they are one. */
    public Optional<Driver> ofUser(Long userId) {
        return userId == null ? Optional.empty() : repo.findByUser_Id(userId);
    }

    /** Drivers a trip can take. */
    public List<Driver> active() {
        return repo.findByEnabledTrueOrderByUsername();
    }

    /** Users who can be registered: enabled and not a driver yet. */
    public List<User> candidates() {
        return userRepo.findDriverCandidates();
    }

    public FleetPapers.Stage licence(Driver driver) {
        return FleetPapers.stage(driver.getLicenceExpiry(), LocalDate.now(clock), settings.getInt(SettingKey.FLEET_EXPIRY_ALERT_DAYS));
    }

    public Optional<Trip> onTheRoad(UUID driverId) {
        return tripRepo.findFirstByDriver_IdAndStatus(driverId, TripStatus.DEPARTED);
    }

    public Page<Trip> trips(UUID driverId, int page, int size) {
        return tripRepo.findByDriver_Id(driverId, PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "tripDate").and(Sort.by(Sort.Direction.DESC, "number"))));
    }

    @Transactional
    public Driver create(DriverDto dto) {
        User user = userRepo.findById(dto.getUserId())
                .orElseThrow(() -> BusinessException.onField("userId", "driver.user.invalid"));
        if (!user.isEnabled()) {
            throw BusinessException.onField("userId", "driver.user.disabled", user.getUsername());
        }
        if (repo.existsByUser_Id(user.getId())) {
            throw BusinessException.onField("userId", "driver.user.taken", user.getFullName());
        }
        if (repo.existsByNationalId(dto.getNationalId())) {
            throw BusinessException.onField("nationalId", "driver.nationalId.taken");
        }
        if (repo.existsByLicenceNumber(dto.getLicenceNumber())) {
            throw BusinessException.onField("licenceNumber", "driver.licence.taken");
        }
        Driver driver = new Driver();
        driver.setUser(user);
        driver.setUsername(user.getUsername());
        apply(driver, dto);
        return repo.save(driver);
    }

    @Transactional
    public Driver update(UUID id, DriverDto dto) {
        Driver driver = findDetailed(id);
        if (repo.existsByNationalIdAndIdNot(dto.getNationalId(), id)) {
            throw BusinessException.onField("nationalId", "driver.nationalId.taken");
        }
        if (repo.existsByLicenceNumberAndIdNot(dto.getLicenceNumber(), id)) {
            throw BusinessException.onField("licenceNumber", "driver.licence.taken");
        }
        apply(driver, dto);
        return driver;
    }

    @Transactional
    public Driver setEnabled(UUID id, boolean enabled) {
        Driver driver = findDetailed(id);
        if (!enabled && tripRepo.existsByDriver_IdAndStatusIn(id, List.of(TripStatus.PLANNED, TripStatus.DEPARTED))) {
            throw BusinessException.of("driver.disable.trips", driver.getUser().getFullName());
        }
        if (enabled && !driver.getUser().isEnabled()) {
            throw BusinessException.of("driver.enable.userDisabled", driver.getUsername());
        }
        driver.setEnabled(enabled);
        return driver;
    }

    private void apply(Driver driver, DriverDto dto) {
        driver.setNationalId(dto.getNationalId());
        driver.setLicenceNumber(dto.getLicenceNumber());
        driver.setLicenceCategory(dto.getLicenceCategory());
        driver.setLicenceExpiry(dto.getLicenceExpiry());
        Vehicle vehicle = null;
        if (dto.getDefaultVehicleId() != null) {
            vehicle = vehicleRepo.findById(dto.getDefaultVehicleId())
                    .orElseThrow(() -> BusinessException.onField("defaultVehicleId", "driver.vehicle.invalid"));
            boolean unchanged = driver.getDefaultVehicle() != null && driver.getDefaultVehicle().getId().equals(vehicle.getId());
            if (!vehicle.isEnabled() && !unchanged) {
                throw BusinessException.onField("defaultVehicleId", "driver.vehicle.inactive", vehicle.getPlate());
            }
        }
        driver.setDefaultVehicle(vehicle);
    }
}
