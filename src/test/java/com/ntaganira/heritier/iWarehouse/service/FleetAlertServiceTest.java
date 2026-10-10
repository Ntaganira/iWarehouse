package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.AlertState;
import com.ntaganira.heritier.iWarehouse.entity.Driver;
import com.ntaganira.heritier.iWarehouse.entity.User;
import com.ntaganira.heritier.iWarehouse.entity.Vehicle;
import com.ntaganira.heritier.iWarehouse.enums.NotificationKind;
import com.ntaganira.heritier.iWarehouse.enums.SettingKey;
import com.ntaganira.heritier.iWarehouse.repository.AlertStateRepository;
import com.ntaganira.heritier.iWarehouse.repository.DriverRepository;
import com.ntaganira.heritier.iWarehouse.repository.VehicleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : FleetAlertServiceTest.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : Papers about to expire (FLT-04): told once within the alert days, again when expired, the driver about
 *               their own licence; a renewal clears the alert; papers far off are not told.
 * </pre>
 */
class FleetAlertServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-10T08:00:00Z"), ZoneId.of("Africa/Kigali"));
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 10);

    private final Map<String, AlertState> states = new HashMap<>();
    private final Notifier notifier = mock(Notifier.class);
    private final VehicleRepository vehicleRepo = mock(VehicleRepository.class);
    private final DriverRepository driverRepo = mock(DriverRepository.class);
    private Vehicle truck;
    private Driver jean;
    private FleetAlertService alerts;

    @BeforeEach
    void setUp() {
        AlertStateRepository stateRepo = mock(AlertStateRepository.class);
        when(stateRepo.findByKeyStartingWithAndClearedAtIsNull("FLEET:"))
                .thenAnswer(i -> states.values().stream().filter(AlertState::isActive).toList());
        when(stateRepo.findById(anyString())).thenAnswer(i -> Optional.ofNullable(states.get(i.<String>getArgument(0))));
        when(stateRepo.save(any(AlertState.class))).thenAnswer(i -> {
            AlertState s = i.getArgument(0);
            states.put(s.getKey(), s);
            return s;
        });
        SettingService settings = mock(SettingService.class);
        when(settings.getInt(SettingKey.FLEET_EXPIRY_ALERT_DAYS)).thenReturn(30);

        truck = new Vehicle();
        truck.setId(UUID.randomUUID());
        truck.setPlate("RAC123A");
        truck.setInsuranceExpiry(TODAY.plusDays(12));
        truck.setInspectionExpiry(TODAY.plusDays(200));
        truck.setEnabled(true);
        jean = new Driver();
        jean.setId(UUID.randomUUID());
        jean.setUser(User.builder().id(21L).username("jdriver").fullName("Jean Driver").email("j@x").password("x").build());
        jean.setLicenceExpiry(TODAY.minusDays(1));
        jean.setEnabled(true);
        when(vehicleRepo.findByEnabledTrueOrderByPlate()).thenReturn(List.of(truck));
        when(driverRepo.findByEnabledTrueOrderByUsername()).thenReturn(List.of(jean));
        alerts = new FleetAlertService(vehicleRepo, driverRepo, stateRepo, settings, notifier, CLOCK);
    }

    @Test
    void papersDueAreToldOnceAndClearedWhenRenewed() {
        assertThat(alerts.checkPapers()).isEqualTo(2);
        assertThat(alerts.dueCount()).isEqualTo(2);
        verify(notifier).holders(eq("ALERT_FLEET"), isNull(), eq(NotificationKind.FLEET), eq("notify.fleet.insurance.soon.title"),
                eq("notify.fleet.insurance.soon.message"), eq("/vehicles/" + truck.getId()), eq("RAC123A"), eq("22/10/2026"), eq("12"));
        // The licence: the holders but the driver, and the driver on their own
        verify(notifier).holders(eq("ALERT_FLEET"), eq(21L), eq(NotificationKind.FLEET), eq("notify.fleet.licence.expired.title"),
                eq("notify.fleet.licence.expired.message"), eq("/drivers/" + jean.getId()), eq("Jean Driver"), eq("09/10/2026"), eq("0"));
        verify(notifier).user(eq(21L), eq(NotificationKind.FLEET), eq("notify.fleet.licence.expired.title"),
                eq("notify.fleet.licence.expired.message"), eq("/trips"), eq("Jean Driver"), eq("09/10/2026"), eq("0"));

        assertThat(alerts.checkPapers()).isZero();                         // still due: told once

        jean.setLicenceExpiry(TODAY.plusYears(3));                          // renewed
        assertThat(alerts.checkPapers()).isZero();
        assertThat(states.values().stream().filter(AlertState::isActive).map(AlertState::getKey))
                .containsExactly(FleetPapers.alertKey(FleetPapers.Paper.INSURANCE, truck.getId(), TODAY.plusDays(12), FleetPapers.Stage.SOON));
        assertThat(alerts.dueCount()).isEqualTo(1);
    }

    @Test
    void anInactiveVehicleIsNotWatched() {
        when(vehicleRepo.findByEnabledTrueOrderByPlate()).thenReturn(List.of());
        when(driverRepo.findByEnabledTrueOrderByUsername()).thenReturn(List.of());
        assertThat(alerts.checkPapers()).isZero();
        verifyNoInteractions(notifier);
    }
}
