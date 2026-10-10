package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.config.NumberFormats;
import com.ntaganira.heritier.iWarehouse.dto.TripDto;
import com.ntaganira.heritier.iWarehouse.entity.*;
import com.ntaganira.heritier.iWarehouse.enums.*;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.repository.*;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : TripServiceTest.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : Trips (FLT-04..07) with the real stock service: AT-03 (40 units on a 35-unit vehicle: departure refused with
 *               a clear message, nothing moves), a departure within limits (units ON_VEHICLE at the vehicle, LOAD movements,
 *               the driver told), every planned unit scanned first, a label not on the manifest refused, expired papers
 *               refusing a trip, a manifest holding its units and a cancellation freeing them.
 * </pre>
 */
class TripServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-10T06:00:00Z"), ZoneId.of("Africa/Kigali"));
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 10);

    private final Location zone = location("WH-A", LocationType.ZONE, null);
    private final Location rack1 = location("WH-A-R01", LocationType.RACK, zone.getId());
    private final Location rack2 = location("WH-A-R02", LocationType.RACK, zone.getId());
    private final Location truckLocation = location("VEH-RAC123A", LocationType.VEHICLE, null);
    private final Product clear6 = product();
    private final List<StockUnit> units = new ArrayList<>();
    private final Map<UUID, Trip> trips = new HashMap<>();

    private Vehicle truck;
    private Driver jean;
    private StockMovementRepository movementRepo;
    private Notifier notifier;
    private StockService stockService;
    private TripService service;

    @BeforeEach
    void setUp() {
        StockUnitRepository unitRepo = mock(StockUnitRepository.class);
        movementRepo = mock(StockMovementRepository.class);
        LocationRepository locationRepo = mock(LocationRepository.class);
        TripRepository tripRepo = mock(TripRepository.class);
        VehicleRepository vehicleRepo = mock(VehicleRepository.class);
        DriverRepository driverRepo = mock(DriverRepository.class);
        notifier = mock(Notifier.class);
        when(locationRepo.findAll()).thenReturn(List.of(zone, rack1, rack2, truckLocation));

        truck = new Vehicle();
        truck.setId(UUID.randomUUID());
        truck.setPlate("RAC123A");
        truck.setModel("Isuzu NPR");
        truck.setMaxPieces(35);
        truck.setMaxLoadKg(2000);
        truck.setInsuranceExpiry(TODAY.plusMonths(6));
        truck.setInspectionExpiry(TODAY.plusMonths(3));
        truck.setLocation(truckLocation);
        truck.setEnabled(true);
        User user = User.builder().id(21L).username("jdriver").fullName("Jean Driver").email("j@x").password("x").build();
        jean = new Driver();
        jean.setId(UUID.randomUUID());
        jean.setUser(user);
        jean.setUsername("jdriver");
        jean.setLicenceExpiry(TODAY.plusYears(2));
        jean.setLicenceCategory("C");
        jean.setEnabled(true);
        when(vehicleRepo.findDetailedById(truck.getId())).thenReturn(Optional.of(truck));
        when(vehicleRepo.lockById(truck.getId())).thenReturn(Optional.of(truck));
        when(driverRepo.findDetailedById(jean.getId())).thenReturn(Optional.of(jean));
        when(driverRepo.lockById(jean.getId())).thenReturn(Optional.of(jean));

        when(unitRepo.findByCodeIn(any())).thenAnswer(a -> {
            Collection<String> codes = a.getArgument(0);
            return units.stream().filter(u -> codes.contains(u.getCode())).toList();
        });
        when(unitRepo.findByIdIn(any())).thenAnswer(a -> {
            Collection<UUID> ids = a.getArgument(0);
            return units.stream().filter(u -> ids.contains(u.getId())).toList();
        });
        when(unitRepo.findByLocation_IdInAndStatusIn(any(), any())).thenAnswer(a -> {
            Collection<UUID> places = a.getArgument(0);
            Collection<StockStatus> statuses = a.getArgument(1);
            return units.stream().filter(u -> u.getLocation() != null && places.contains(u.getLocation().getId())
                    && statuses.contains(u.getStatus())).toList();
        });

        when(tripRepo.save(any())).thenAnswer(a -> {
            Trip t = a.getArgument(0);
            t.setId(UUID.randomUUID());
            trips.put(t.getId(), t);
            return t;
        });
        when(tripRepo.lockById(any())).thenAnswer(a -> Optional.ofNullable(trips.get(a.<UUID>getArgument(0))));
        when(tripRepo.findDetailedById(any())).thenAnswer(a -> Optional.ofNullable(trips.get(a.<UUID>getArgument(0))));
        // Planned trips hold the units on their manifest
        when(tripRepo.findHolds(any())).thenAnswer(a -> {
            Collection<UUID> ids = a.getArgument(0);
            List<Object[]> rows = new ArrayList<>();
            trips.values().stream().filter(Trip::isPlanned).forEach(t -> t.getLines().stream()
                    .filter(l -> ids.contains(l.getStockUnitId())).forEach(l -> rows.add(new Object[]{l.getStockUnitId(), t.getNumber()})));
            return rows;
        });

        DocumentNumberService numbers = mock(DocumentNumberService.class);
        when(numbers.next(DocumentType.TRIP)).thenReturn("TRP-WH-2026-000001", "TRP-WH-2026-000002");
        stockService = new StockService(unitRepo, movementRepo, mock(StockCostEntryRepository.class), mock(StockAdjustmentLineRepository.class),
                mock(StockCountRepository.class), mock(SalesInvoiceLineRepository.class), tripRepo, locationRepo, numbers, CLOCK);
        service = new TripService(tripRepo, vehicleRepo, driverRepo, unitRepo, stockService, numbers, notifier, new NumberFormats(), CLOCK);
        signIn(7L, "supervisor1");
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- AT-03 and departure (FLT-06, FLT-07)

    @Test
    void at03FortyUnitsOnAThirtyFiveUnitVehicleCannotDepart() {
        Trip trip = plan();
        List<StockUnit> forty = sheets(40, rack1, "14.40");
        service.addUnits(trip.getId(), codes(forty));
        service.scan(trip.getId(), codes(forty));
        assertThat(trip.getLines()).allMatch(TripLine::isLoaded);

        assertThatThrownBy(() -> service.depart(trip.getId(), null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("messageKey", "trip.depart.overPieces")
                .satisfies(e -> assertThat(((BusinessException) e).getArgs()).containsExactly(40, "RAC123A", 35, 5));
        // Nothing moved: still planned, the units on their rack and available, no movement written
        assertThat(trip.getStatus()).isEqualTo(TripStatus.PLANNED);
        assertThat(forty).allMatch(u -> u.getStatus() == StockStatus.AVAILABLE && u.getLocation() == rack1);
        verify(movementRepo, never()).save(any());
    }

    @Test
    void aLoadWithinTheLimitsDepartsInTheDriversCharge() {
        Trip trip = plan();
        List<StockUnit> fromRack1 = sheets(20, rack1, "14.40");
        List<StockUnit> fromRack2 = sheets(15, rack2, "20.00");
        List<StockUnit> all = new ArrayList<>(fromRack1);
        all.addAll(fromRack2);
        service.addUnits(trip.getId(), codes(all));
        service.scan(trip.getId(), codes(all));

        service.depart(trip.getId(), 45210);

        assertThat(trip.getStatus()).isEqualTo(TripStatus.DEPARTED);
        assertThat(trip.getLoadedPieces()).isEqualTo(35);
        assertThat(trip.getLoadedKg()).isEqualByComparingTo("588.00");     // 20 x 14.40 + 15 x 20
        assertThat(trip.getDepartedBy()).isEqualTo("supervisor1");
        assertThat(trip.getOdometerStart()).isEqualTo(45210);
        assertThat(truck.getOdometerKm()).isEqualTo(45210);
        assertThat(all).allMatch(u -> u.getStatus() == StockStatus.ON_VEHICLE && u.getLocation() == truckLocation);
        // Each line keeps the rack its unit left from
        Map<UUID, UUID> from = trip.getLines().stream().collect(Collectors.toMap(TripLine::getStockUnitId, TripLine::getFromLocationId));
        assertThat(fromRack1).allMatch(u -> from.get(u.getId()).equals(rack1.getId()));
        assertThat(fromRack2).allMatch(u -> from.get(u.getId()).equals(rack2.getId()));

        ArgumentCaptor<StockMovement> moves = ArgumentCaptor.forClass(StockMovement.class);
        verify(movementRepo, times(35)).save(moves.capture());
        assertThat(moves.getAllValues()).allSatisfy(m -> {
            assertThat(m.getType()).isEqualTo(MovementType.LOAD);
            assertThat(m.getFromStatus()).isEqualTo(StockStatus.AVAILABLE);
            assertThat(m.getToStatus()).isEqualTo(StockStatus.ON_VEHICLE);
            assertThat(m.getToLocationId()).isEqualTo(truckLocation.getId());
            assertThat(m.getRefType()).isEqualTo(StockService.REF_TRIP);
            assertThat(m.getRefNumber()).isEqualTo("TRP-WH-2026-000001");
        });
        verify(notifier).user(eq(21L), eq(NotificationKind.FLEET), eq("notify.trip.departed.title"), eq("notify.trip.departed.message"),
                eq("/trips/" + trip.getId()), eq("TRP-WH-2026-000001"), eq("35"), eq("588"), eq("RAC123A"));
        // Departed: the manifest no longer holds the units, and they cannot be loaded again
        assertThat(stockService.holds(all.stream().map(StockUnit::getId).toList())).isEmpty();
    }

    @Test
    void aHeavyLoadCannotDepart() {
        truck.setMaxLoadKg(200);
        Trip trip = plan();
        List<StockUnit> heavy = sheets(2, rack1, "108.34");
        service.addUnits(trip.getId(), codes(heavy));
        service.scan(trip.getId(), codes(heavy));

        assertThatThrownBy(() -> service.depart(trip.getId(), null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("messageKey", "trip.depart.overKg")
                .satisfies(e -> assertThat(((BusinessException) e).getArgs()).containsExactly("216.68", "RAC123A", "200", "16.68"));
    }

    @Test
    void everyPlannedUnitMustBeScannedBeforeDeparture() {
        Trip trip = plan();
        List<StockUnit> five = sheets(5, rack1, "14.40");
        service.addUnits(trip.getId(), codes(five));
        service.scan(trip.getId(), five.get(0).getCode() + "\n" + five.get(1).getCode());

        assertThatThrownBy(() -> service.depart(trip.getId(), null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("messageKey", "trip.depart.notLoaded")
                .satisfies(e -> assertThat(((BusinessException) e).getArgs()[0]).isEqualTo(3));
    }

    @Test
    void anOdometerBelowTheLastReadingIsRefused() {
        truck.setOdometerKm(50000);
        Trip trip = plan();
        List<StockUnit> one = sheets(1, rack1, "14.40");
        service.addUnits(trip.getId(), codes(one));
        service.scan(trip.getId(), codes(one));

        assertThatThrownBy(() -> service.depart(trip.getId(), 49000))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "trip.odometer.belowLast");
        assertThat(one.get(0).getStatus()).isEqualTo(StockStatus.AVAILABLE);
    }

    // ---------------------------------------------------------------- scanning (FLT-06)

    @Test
    void aLabelNotOnTheManifestIsRefused() {
        Trip trip = plan();
        List<StockUnit> planned = sheets(2, rack1, "14.40");
        StockUnit other = sheets(1, rack2, "14.40").get(0);
        service.addUnits(trip.getId(), codes(planned));

        TripService.ScanResult r = service.scan(trip.getId(), planned.get(0).getCode() + "\n" + other.getCode() + "\nU-XX-1");

        assertThat(r.loadedNow()).extracting(TripService.Scanned::code).containsExactly(planned.get(0).getCode());
        assertThat(r.refused()).extracting(TripService.Scanned::code, TripService.Scanned::known)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(other.getCode(), true), org.assertj.core.groups.Tuple.tuple("U-XX-1", false));
        assertThat(trip.getLines()).hasSize(2);
        assertThat(r.loaded().load().pieces()).isEqualTo(1);
        // Scanning again changes nothing
        assertThat(service.scan(trip.getId(), planned.get(0).getCode()).scans().get(0).outcome())
                .isEqualTo(TripLoading.ScanOutcome.ALREADY_LOADED);
    }

    // ---------------------------------------------------------------- the manifest (FLT-05, INV-05)

    @Test
    void aManifestHoldsItsUnitsUntilTheTripIsCancelled() {
        Trip first = plan();
        Trip second = plan();
        StockUnit unit = sheets(1, rack1, "14.40").get(0);
        service.addUnits(first.getId(), unit.getCode());

        assertThat(stockService.holds(List.of(unit.getId()))).containsEntry(unit.getId(), "TRP-WH-2026-000001");
        assertThatThrownBy(() -> service.addUnits(second.getId(), unit.getCode()))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "trip.codes.held");
        // Adding it again to its own trip says so, without adding it twice
        assertThat(service.addUnits(first.getId(), unit.getCode()).already()).containsExactly(unit.getCode());
        assertThat(first.getLines()).hasSize(1);

        service.cancel(first.getId(), "Truck at the garage");
        assertThat(first.getStatus()).isEqualTo(TripStatus.CANCELLED);
        assertThat(first.getCancelReason()).isEqualTo("Truck at the garage");
        assertThat(stockService.holds(List.of(unit.getId()))).isEmpty();
        assertThat(service.addUnits(second.getId(), unit.getCode()).added()).containsExactly(unit.getCode());
    }

    @Test
    void aRackLabelAddsItsAvailableUnitsAndUnitsNotAvailableAreRefused() {
        Trip trip = plan();
        List<StockUnit> onRack = sheets(3, rack2, "14.40");
        onRack.get(2).setStatus(StockStatus.RESERVED);                       // not available: left out
        StockUnit sold = sheets(1, rack1, "14.40").get(0);
        sold.setStatus(StockStatus.SOLD);
        sold.setLocation(null);

        TripService.AddResult r = service.addUnits(trip.getId(), rack2.getCode());
        assertThat(r.added()).containsExactly(onRack.get(0).getCode(), onRack.get(1).getCode());
        assertThat(r.planned().load().pieces()).isEqualTo(2);

        assertThatThrownBy(() -> service.addUnits(trip.getId(), sold.getCode()))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "trip.codes.notLoadable");
        assertThatThrownBy(() -> service.addUnits(trip.getId(), "NOPE-1"))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "trip.codes.unknown");
    }

    @Test
    void aPlannedTripTakesOffAUnitAndNothingChangesOnceDeparted() {
        Trip trip = plan();
        List<StockUnit> two = sheets(2, rack1, "14.40");
        service.addUnits(trip.getId(), codes(two));
        TripLine first = TripService.sortedLines(trip).get(0);
        first.setId(UUID.randomUUID());
        service.removeLine(trip.getId(), first.getId());
        assertThat(trip.getLines()).hasSize(1);

        service.scan(trip.getId(), codes(two.subList(1, 2)));
        service.depart(trip.getId(), null);
        assertThatThrownBy(() -> service.addUnits(trip.getId(), two.get(0).getCode()))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "trip.notAllowed");
        assertThatThrownBy(() -> service.cancel(trip.getId(), "late"))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "trip.notAllowed");
    }

    // ---------------------------------------------------------------- papers (FLT-04)

    @Test
    void expiredPapersRefuseATrip() {
        jean.setLicenceExpiry(TODAY.minusDays(1));
        assertThatThrownBy(() -> service.create(tripForm(TODAY)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("messageKey", "trip.papers.LICENCE")
                .hasFieldOrPropertyWithValue("field", "driverId");

        jean.setLicenceExpiry(TODAY.plusYears(1));
        truck.setInsuranceExpiry(TODAY.plusDays(5));
        // The insurance covers today but not a trip in a week
        assertThatThrownBy(() -> service.create(tripForm(TODAY.plusDays(7))))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("messageKey", "trip.papers.INSURANCE")
                .hasFieldOrPropertyWithValue("field", "vehicleId");
        assertThat(service.create(tripForm(TODAY.plusDays(5))).getNumber()).isEqualTo("TRP-WH-2026-000001");
    }

    @Test
    void papersThatExpiredSincePlanningRefuseTheDeparture() {
        Trip trip = plan();
        List<StockUnit> one = sheets(1, rack1, "14.40");
        service.addUnits(trip.getId(), codes(one));
        service.scan(trip.getId(), codes(one));
        truck.setInspectionExpiry(TODAY.minusDays(2));

        assertThatThrownBy(() -> service.depart(trip.getId(), null))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "trip.papers.INSPECTION");
        assertThat(one.get(0).getStatus()).isEqualTo(StockStatus.AVAILABLE);
    }

    @Test
    void aTripCannotBePlannedInThePastNorDepartBeforeItsDay() {
        assertThatThrownBy(() -> service.create(tripForm(TODAY.minusDays(1))))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "trip.date.past");
        Trip later = service.create(tripForm(TODAY.plusDays(2)));
        assertThatThrownBy(() -> service.depart(later.getId(), null))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "trip.depart.early");
    }

    // ---------------------------------------------------------------- helpers

    private Trip plan() {
        return service.create(tripForm(TODAY));
    }

    private TripDto tripForm(LocalDate day) {
        TripDto dto = new TripDto();
        dto.setVehicleId(truck.getId());
        dto.setDriverId(jean.getId());
        dto.setTripDate(day);
        dto.setArea("Musanze");
        return dto;
    }

    private List<StockUnit> sheets(int count, Location at, String kg) {
        int start = units.size() + 100;
        return IntStream.range(0, count).mapToObj(i -> {
            StockUnit u = new StockUnit();
            u.setId(UUID.randomUUID());
            u.setCode(String.format("U-WH-%06d", start + i));
            u.setProduct(clear6);
            u.setKind(UnitKind.CUT_PIECE);
            u.setWidthMm(1200);
            u.setHeightMm(800);
            u.setAreaM2(new BigDecimal("0.9600"));
            u.setWeightKg(new BigDecimal(kg));
            u.setUnitCost(new BigDecimal("33100.00"));
            u.setStatus(StockStatus.AVAILABLE);
            u.setLocation(at);
            units.add(u);
            return u;
        }).toList();
    }

    private static String codes(List<StockUnit> units) {
        return units.stream().map(StockUnit::getCode).collect(Collectors.joining("\n"));
    }

    private static Location location(String code, LocationType type, UUID parentId) {
        Location l = new Location();
        l.setId(UUID.randomUUID());
        l.setCode(code);
        l.setType(type);
        l.setParentId(parentId);
        l.setEnabled(true);
        return l;
    }

    private static Product product() {
        Product p = new Product();
        p.setId(UUID.randomUUID());
        p.setCode("CLR-6");
        p.setGlassType(GlassType.CLEAR);
        p.setThicknessMm(new BigDecimal("6.00"));
        p.setEnabled(true);
        return p;
    }

    private static void signIn(long id, String username) {
        AppUserPrincipal principal = new AppUserPrincipal(id, username, username, "x", true, true, List.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
