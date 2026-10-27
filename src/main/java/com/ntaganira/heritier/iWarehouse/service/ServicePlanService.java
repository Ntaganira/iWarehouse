package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.config.NumberFormats;
import com.ntaganira.heritier.iWarehouse.dto.ServicePlanDto;
import com.ntaganira.heritier.iWarehouse.entity.*;
import com.ntaganira.heritier.iWarehouse.enums.NotificationKind;
import com.ntaganira.heritier.iWarehouse.enums.SettingKey;
import com.ntaganira.heritier.iWarehouse.enums.VehicleCostStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.*;
import org.springframework.scheduling.annotation.Scheduled;
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
 * - File      : ServicePlanService.java
 * - Date      : 2026. 10. 12.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : A vehicle's service plans (MANAGE_SERVICE_PLANS): a name unique on the vehicle, every so many km and/or
 *               months, when it was last done (up to today) and at how many km (the vehicle's latest reading when left
 *               empty). Where each stands (ServicePlans) from the vehicle's latest odometer: its trips' or a higher one
 *               its costs recorded. Before a plan falls due within the Settings' km or days, and when it is due, the
 *               ALERT_FLEET holders are told once (alert_states); doing it (a posted cost, VehicleCostService) clears
 *               the alert. Also the vehicle's mileage log (trips and costs) and what it cost this year per km.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class ServicePlanService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final List<VehicleCostStatus> RECORDED = List.of(VehicleCostStatus.PENDING_APPROVAL, VehicleCostStatus.POSTED);

    private final VehicleServicePlanRepository repo;
    private final VehicleRepository vehicleRepo;
    private final TripRepository tripRepo;
    private final VehicleCostRepository costRepo;
    private final VehicleCostService costService;
    private final AlertStateRepository stateRepo;
    private final SettingService settings;
    private final Notifier notifier;
    private final NumberFormats num;
    private final Clock clock;

    public ServicePlanService(VehicleServicePlanRepository repo, VehicleRepository vehicleRepo, TripRepository tripRepo,
                              VehicleCostRepository costRepo, VehicleCostService costService, AlertStateRepository stateRepo,
                              SettingService settings, Notifier notifier, NumberFormats num, Clock clock) {
        this.repo = repo;
        this.vehicleRepo = vehicleRepo;
        this.tripRepo = tripRepo;
        this.costRepo = costRepo;
        this.costService = costService;
        this.stateRepo = stateRepo;
        this.settings = settings;
        this.notifier = notifier;
        this.num = num;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- reading

    /** A plan and where it stands (null while it is inactive). */
    public record Row(VehicleServicePlan plan, ServicePlans.Status status) {
    }

    /** A vehicle's plans, active first, each with where it stands at the vehicle's latest odometer. */
    public List<Row> plansOf(Vehicle vehicle) {
        Integer km = costService.currentKm(vehicle);
        LocalDate today = today();
        return repo.findByVehicleIdOrderByEnabledDescNameAsc(vehicle.getId()).stream()
                .map(p -> new Row(p, p.isEnabled() ? status(p, km, today) : null)).toList();
    }

    public VehicleServicePlan findById(UUID id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException("VehicleServicePlan", id));
    }

    public ServicePlanDto form(VehicleServicePlan plan) {
        ServicePlanDto dto = new ServicePlanDto();
        dto.setName(plan.getName());
        dto.setEveryKm(plan.getEveryKm());
        dto.setEveryMonths(plan.getEveryMonths());
        dto.setLastDoneOn(plan.getLastDoneOn());
        dto.setLastDoneKm(plan.getLastDoneKm());
        dto.setNote(plan.getNote());
        return dto;
    }

    /** A new plan, last done today at the vehicle's latest reading. */
    public ServicePlanDto newForm(Vehicle vehicle) {
        ServicePlanDto dto = new ServicePlanDto();
        dto.setLastDoneOn(today());
        dto.setLastDoneKm(costService.currentKm(vehicle));
        return dto;
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    // ---------------------------------------------------------------- changing

    @Transactional
    public VehicleServicePlan create(UUID vehicleId, ServicePlanDto dto) {
        Vehicle vehicle = vehicleRepo.findById(vehicleId).orElseThrow(() -> new NotFoundException("Vehicle", vehicleId));
        String name = dto.getName().trim();
        if (repo.existsByVehicleIdAndNameIgnoreCase(vehicleId, name)) {
            throw BusinessException.onField("name", "servicePlan.name.taken", name, vehicle.getPlate());
        }
        VehicleServicePlan plan = new VehicleServicePlan();
        plan.setVehicleId(vehicleId);
        apply(plan, dto, vehicle);
        return repo.save(plan);
    }

    @Transactional
    public VehicleServicePlan update(UUID id, ServicePlanDto dto) {
        VehicleServicePlan plan = findById(id);
        Vehicle vehicle = vehicleRepo.findById(plan.getVehicleId()).orElseThrow(() -> new NotFoundException("Vehicle", plan.getVehicleId()));
        String name = dto.getName().trim();
        if (repo.existsByVehicleIdAndNameIgnoreCaseAndIdNot(plan.getVehicleId(), name, id)) {
            throw BusinessException.onField("name", "servicePlan.name.taken", name, vehicle.getPlate());
        }
        apply(plan, dto, vehicle);
        return plan;
    }

    /** Deactivates a plan (no more reminders) or activates it again. */
    @Transactional
    public VehicleServicePlan setEnabled(UUID id, boolean enabled) {
        VehicleServicePlan plan = findById(id);
        plan.setEnabled(enabled);
        return plan;
    }

    private void apply(VehicleServicePlan plan, ServicePlanDto dto, Vehicle vehicle) {
        if (dto.getEveryKm() == null && dto.getEveryMonths() == null) {
            throw BusinessException.onField("everyKm", "servicePlan.every.required");
        }
        if (dto.getLastDoneOn().isAfter(today())) {
            throw BusinessException.onField("lastDoneOn", "servicePlan.lastOn.future");
        }
        Integer lastKm = dto.getLastDoneKm() != null ? dto.getLastDoneKm() : costService.currentKm(vehicle);
        if (dto.getEveryKm() != null && lastKm == null) {
            throw BusinessException.onField("lastDoneKm", "servicePlan.lastKm.required");
        }
        plan.setName(dto.getName().trim());
        plan.setEveryKm(dto.getEveryKm());
        plan.setEveryMonths(dto.getEveryMonths());
        plan.setLastDoneOn(dto.getLastDoneOn());
        plan.setLastDoneKm(lastKm);
        plan.setNote(StringUtils.hasText(dto.getNote()) ? dto.getNote().trim() : null);
    }

    // ---------------------------------------------------------------- the vehicle's mileage and costs

    /** An odometer reading: when, how many km, what recorded it (TRIP_START, TRIP_END, COST), its number and page. */
    public record Reading(LocalDate date, int km, String kind, String reference, String link) {
    }

    /** The vehicle's readings, the latest first: its trips' departures and returns, and its costs that recorded one. */
    public List<Reading> mileage(Vehicle vehicle) {
        List<Reading> readings = new ArrayList<>();
        for (Trip t : tripRepo.findByVehicle_IdAndOdometerStartIsNotNull(vehicle.getId())) {
            LocalDate left = t.getDepartedAt() != null ? t.getDepartedAt().toLocalDate() : t.getTripDate();
            readings.add(new Reading(left, t.getOdometerStart(), "TRIP_START", t.getNumber(), "/trips/" + t.getId()));
            if (t.getOdometerEnd() != null) {
                LocalDate back = t.getReturnedAt() != null ? t.getReturnedAt().toLocalDate() : left;
                readings.add(new Reading(back, t.getOdometerEnd(), "TRIP_END", t.getNumber(), "/trips/" + t.getId()));
            }
        }
        for (VehicleCost c : costRepo.findByVehicle_IdAndOdometerKmIsNotNullAndStatusIn(vehicle.getId(), RECORDED)) {
            readings.add(new Reading(c.getCostDate(), c.getOdometerKm(), "COST", c.getNumber(), "/vehicle-costs/" + c.getId()));
        }
        readings.sort(Comparator.comparing(Reading::date).thenComparing(Reading::km).reversed());
        return readings;
    }

    /** What the vehicle cost this year (posted, without VAT), the km its trips drove this year and the cost per km. */
    public record Summary(Integer currentKm, BigDecimal costsThisYear, long kmThisYear, BigDecimal perKm) {
    }

    public Summary summary(Vehicle vehicle) {
        LocalDate today = today();
        LocalDate first = today.withDayOfYear(1);
        BigDecimal costs = costRepo.totalOf(vehicle.getId(), VehicleCostStatus.POSTED, first, today);
        long km = tripRepo.findByVehicle_IdAndOdometerStartIsNotNull(vehicle.getId()).stream()
                .filter(t -> t.getDistanceKm() != null && t.getDepartedAt() != null && !t.getDepartedAt().toLocalDate().isBefore(first))
                .mapToLong(Trip::getDistanceKm).sum();
        return new Summary(costService.currentKm(vehicle), costs, km, VehicleCosts.perKm(costs, km));
    }

    // ---------------------------------------------------------------- reminders

    /** Raises the reminders of plans newly due or due soon and clears those done or deactivated; how many were raised. */
    @Scheduled(fixedDelayString = "${app.alerts.fleet-every:PT6H}", initialDelayString = "${app.alerts.first-check:PT1M}")
    @Transactional
    public int checkServices() {
        LocalDateTime now = LocalDateTime.now(clock);
        Map<String, AlertState> raised = new HashMap<>();
        stateRepo.findByKeyStartingWithAndClearedAtIsNull(ServicePlans.ALERT_PREFIX).forEach(s -> raised.put(s.getKey(), s));
        int count = 0;
        for (Due d : due()) {
            String key = ServicePlans.alertKey(d.plan().getId(), d.plan().getLastDoneOn(), d.plan().getLastDoneKm(), d.status().stage());
            if (raised.remove(key) != null) {
                continue;                                           // told already
            }
            AlertState state = stateRepo.findById(key).orElseGet(AlertState::new);
            state.setKey(key);
            state.setRaisedAt(now);
            state.setClearedAt(null);
            stateRepo.save(state);
            String base = "notify.service." + d.status().stage().name().toLowerCase(Locale.ROOT);
            notifier.holders("ALERT_FLEET", null, NotificationKind.FLEET, base + ".title", base + ".message",
                    "/vehicles/" + d.vehicle().getId() + "?tab=service", d.plan().getName(), d.vehicle().getPlate(), when(d.status()));
            count++;
        }
        raised.values().forEach(s -> s.setClearedAt(now));         // done, changed or deactivated
        return count;
    }

    /** Active plans of active vehicles due or due soon (the owner dashboard). */
    public int dueCount() {
        return due().size();
    }

    private record Due(VehicleServicePlan plan, Vehicle vehicle, ServicePlans.Status status) {
    }

    private List<Due> due() {
        LocalDate today = today();
        Map<UUID, Vehicle> vehicles = vehicleRepo.findByEnabledTrueOrderByPlate().stream()
                .collect(Collectors.toMap(Vehicle::getId, Function.identity()));
        Map<UUID, Integer> fromCosts = costService.costOdometers();
        List<Due> due = new ArrayList<>();
        for (VehicleServicePlan p : repo.findByEnabledTrueOrderByName()) {
            Vehicle v = vehicles.get(p.getVehicleId());
            if (v == null) {
                continue;
            }
            ServicePlans.Status s = status(p, ServicePlans.latest(v.getOdometerKm(), fromCosts.get(v.getId())), today);
            if (s.stage() != ServicePlans.Stage.OK) {
                due.add(new Due(p, v, s));
            }
        }
        return due;
    }

    private ServicePlans.Status status(VehicleServicePlan p, Integer km, LocalDate today) {
        return ServicePlans.of(p.getEveryKm(), p.getEveryMonths(), p.getLastDoneOn(), p.getLastDoneKm(), km, today,
                settings.getInt(SettingKey.SERVICE_ALERT_KM), settings.getInt(SettingKey.FLEET_EXPIRY_ALERT_DAYS));
    }

    /** When it is due, in numbers and a date only (the notification's text is translated around it). */
    private String when(ServicePlans.Status s) {
        List<String> parts = new ArrayList<>();
        if (s.nextKm() != null) {
            parts.add(num.whole(s.nextKm()) + " km");
        }
        if (s.nextDate() != null) {
            parts.add(s.nextDate().format(DAY));
        }
        return String.join(" · ", parts);
    }
}
