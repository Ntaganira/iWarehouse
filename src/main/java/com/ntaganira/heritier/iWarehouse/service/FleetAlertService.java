package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.AlertState;
import com.ntaganira.heritier.iWarehouse.entity.Driver;
import com.ntaganira.heritier.iWarehouse.entity.Vehicle;
import com.ntaganira.heritier.iWarehouse.enums.NotificationKind;
import com.ntaganira.heritier.iWarehouse.enums.SettingKey;
import com.ntaganira.heritier.iWarehouse.repository.AlertStateRepository;
import com.ntaganira.heritier.iWarehouse.repository.DriverRepository;
import com.ntaganira.heritier.iWarehouse.repository.VehicleRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : FleetAlertService.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : Watches the papers trips need (FLT-04): an active driver's licence, an active vehicle's insurance and
 *               inspection. Within the Settings' days before expiry the ALERT_FLEET holders are told once, and again the
 *               day it expires (a licence tells its driver too). A renewal changes the date, so the alert clears
 *               (alert_states). Also counts the papers expiring or expired, for the owner dashboard.
 * </pre>
 */
@Service
public class FleetAlertService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final VehicleRepository vehicleRepo;
    private final DriverRepository driverRepo;
    private final AlertStateRepository stateRepo;
    private final SettingService settings;
    private final Notifier notifier;
    private final Clock clock;

    public FleetAlertService(VehicleRepository vehicleRepo, DriverRepository driverRepo, AlertStateRepository stateRepo,
                             SettingService settings, Notifier notifier, Clock clock) {
        this.vehicleRepo = vehicleRepo;
        this.driverRepo = driverRepo;
        this.stateRepo = stateRepo;
        this.settings = settings;
        this.notifier = notifier;
        this.clock = clock;
    }

    /** A paper due: whose (plate or driver's name), when it expires, the page to open and the driver to tell. */
    record Due(FleetPapers.Paper paper, FleetPapers.Stage stage, UUID ownerId, String who, LocalDate expiry, String link, Long tellUserId) {
    }

    /** Raises the alerts of papers newly due and clears those renewed or no longer watched; how many were raised. */
    @Scheduled(fixedDelayString = "${app.alerts.fleet-every:PT6H}", initialDelayString = "${app.alerts.first-check:PT1M}")
    @Transactional
    public int checkPapers() {
        LocalDate today = LocalDate.now(clock);
        LocalDateTime now = LocalDateTime.now(clock);
        Map<String, AlertState> raised = new HashMap<>();
        stateRepo.findByKeyStartingWithAndClearedAtIsNull(FleetPapers.ALERT_PREFIX).forEach(s -> raised.put(s.getKey(), s));
        int count = 0;
        for (Map.Entry<String, Due> e : due(today).entrySet()) {
            if (raised.remove(e.getKey()) != null) {
                continue;                                        // already told
            }
            AlertState state = stateRepo.findById(e.getKey()).orElseGet(AlertState::new);
            state.setKey(e.getKey());
            state.setRaisedAt(now);
            state.setClearedAt(null);
            stateRepo.save(state);
            tell(e.getValue(), today);
            count++;
        }
        raised.values().forEach(s -> s.setClearedAt(now));     // renewed, deactivated or back to valid
        return count;
    }

    /** Papers expiring within the alert days or expired, of active vehicles and drivers (the owner dashboard). */
    @Transactional(readOnly = true)
    public int dueCount() {
        return (int) due(LocalDate.now(clock)).values().stream().map(d -> d.paper() + ":" + d.ownerId()).distinct().count();
    }

    /** Every paper due today, by alert key. */
    private Map<String, Due> due(LocalDate today) {
        int days = settings.getInt(SettingKey.FLEET_EXPIRY_ALERT_DAYS);
        Map<String, Due> due = new LinkedHashMap<>();
        for (Vehicle v : vehicleRepo.findByEnabledTrueOrderByPlate()) {
            add(due, FleetPapers.Paper.INSURANCE, v.getId(), v.getPlate(), v.getInsuranceExpiry(), "/vehicles/" + v.getId(), null, today, days);
            add(due, FleetPapers.Paper.INSPECTION, v.getId(), v.getPlate(), v.getInspectionExpiry(), "/vehicles/" + v.getId(), null, today, days);
        }
        for (Driver d : driverRepo.findByEnabledTrueOrderByUsername()) {
            add(due, FleetPapers.Paper.LICENCE, d.getId(), d.getUser().getFullName(), d.getLicenceExpiry(), "/drivers/" + d.getId(),
                    d.getUser().getId(), today, days);
        }
        return due;
    }

    private static void add(Map<String, Due> due, FleetPapers.Paper paper, UUID ownerId, String who, LocalDate expiry, String link,
                            Long tellUserId, LocalDate today, int days) {
        FleetPapers.Stage stage = FleetPapers.stage(expiry, today, days);
        if (stage != FleetPapers.Stage.VALID) {
            due.put(FleetPapers.alertKey(paper, ownerId, expiry, stage), new Due(paper, stage, ownerId, who, expiry, link, tellUserId));
        }
    }

    private void tell(Due d, LocalDate today) {
        String base = "notify.fleet." + d.paper().name().toLowerCase(Locale.ROOT) + "." + d.stage().name().toLowerCase(Locale.ROOT);
        String left = String.valueOf(Math.max(0, FleetPapers.daysLeft(d.expiry(), today)));
        notifier.holders("ALERT_FLEET", d.tellUserId(), NotificationKind.FLEET, base + ".title", base + ".message", d.link(),
                d.who(), d.expiry().format(DAY), left);
        // The driver hears about their own licence; their trips page is the page they can open
        notifier.user(d.tellUserId(), NotificationKind.FLEET, base + ".title", base + ".message", "/trips",
                d.who(), d.expiry().format(DAY), left);
    }
}
