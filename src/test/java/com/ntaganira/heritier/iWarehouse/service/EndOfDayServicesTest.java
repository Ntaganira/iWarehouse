package com.ntaganira.heritier.iWarehouse.service;

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
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : EndOfDayServicesTest.java
 * - Date      : 2026. 10. 11.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The end of the day of a trip (FLT-08..FLT-11, MPOS-07, SRS 5.1). AT-06: back with a unit missing and no
 *               breakage report, an audit case opens, the owner is told and the trip cannot close. Units scanned back go on
 *               the rack they left from; an accepted breakage leaves stock to spoilage; undecided breakage stops the
 *               reconciliation; the reporter never decides on their own report. A missing unit is found, written off or
 *               charged to the driver, or settled by a sale synced late; the case is resolved once every unit is. The cash is
 *               declared once, and the trip closes once its case is settled, its cash declared and its float cleared.
 * </pre>
 */
class EndOfDayServicesTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-11T16:00:00Z"), ZoneId.of("Africa/Kigali"));

    private final Location zone = location("WH-A", LocationType.ZONE, null);
    private final Location rack4 = location("WH-A-R04", LocationType.RACK, zone.getId());
    private final Location rack5 = location("WH-A-R05", LocationType.RACK, zone.getId());
    private final Location truckLocation = location("VEH-RAC123A", LocationType.VEHICLE, null);
    private final Product clear6 = new Product();
    private final List<StockUnit> units = new ArrayList<>();
    private final List<TripBreakage> breakages = new ArrayList<>();
    private final List<TripReturnExtra> extras = new ArrayList<>();
    private final List<AuditCase> cases = new ArrayList<>();

    private Trip trip;
    private Driver jean;
    private ApiDevice phone;
    private StockMovementRepository movementRepo;
    private PostingService postingService;
    private Notifier notifier;
    private FloatService floatService;
    private TripReturnService returnService;
    private BreakageService breakageService;
    private AuditCaseService caseService;
    private TripCloseService closeService;

    private final SettingService settings = mock(SettingService.class);

    @BeforeEach
    void setUp() {
        clear6.setId(UUID.randomUUID());
        clear6.setCode("CLR-6");
        clear6.setMacPerM2(new BigDecimal("5000.0000"));
        Vehicle truck = new Vehicle();
        truck.setId(UUID.randomUUID());
        truck.setPlate("RAC123A");
        truck.setLocation(truckLocation);
        jean = new Driver();
        jean.setId(UUID.randomUUID());
        jean.setUsername("jdriver");
        jean.setUser(User.builder().id(21L).username("jdriver").fullName("Jean Driver").email("j@x").password("x").build());
        trip = new Trip();
        trip.setId(UUID.randomUUID());
        trip.setNumber("TRP-WH-2026-000009");
        trip.setVehicle(truck);
        trip.setDriver(jean);
        trip.setStatus(TripStatus.DEPARTED);
        trip.setDepartedAt(LocalDateTime.of(2026, 10, 11, 7, 0));
        trip.setLoadedPieces(3);
        trip.setLoadedKg(new BigDecimal("300.00"));
        for (String code : List.of("U-WH-000101", "U-WH-000102", "U-WH-000103")) {
            StockUnit u = new StockUnit();
            u.setId(UUID.randomUUID());
            u.setCode(code);
            u.setProduct(clear6);
            u.setKind(UnitKind.SHEET);
            u.setStatus(StockStatus.ON_VEHICLE);
            u.setLocation(truckLocation);
            u.setAreaM2(new BigDecimal("7.2225"));
            u.setWeightKg(new BigDecimal("108.34"));
            u.setUnitCost(new BigDecimal("36000.00"));
            units.add(u);
            TripLine line = new TripLine();
            line.setId(UUID.randomUUID());
            line.setTrip(trip);
            line.setStockUnitId(u.getId());
            line.setUnitCode(code);
            line.setPlannedAt(LocalDateTime.of(2026, 10, 10, 15, 0));
            line.setLoadedAt(LocalDateTime.of(2026, 10, 11, 6, 30));
            line.setFromLocationId(rack4.getId());
            trip.getLines().add(line);
        }

        StockUnitRepository unitRepo = mock(StockUnitRepository.class);
        when(unitRepo.findByIdIn(any())).thenAnswer(a -> {
            Collection<UUID> ids = a.getArgument(0);
            return units.stream().filter(u -> ids.contains(u.getId())).toList();
        });
        when(unitRepo.findByCodeIn(any())).thenAnswer(a -> {
            Collection<String> codes = a.getArgument(0);
            return units.stream().filter(u -> codes.contains(u.getCode())).toList();
        });
        when(unitRepo.sumArea(any(), any())).thenAnswer(a -> units.stream().filter(u -> u.getStatus().isOnHand())
                .map(StockUnit::getAreaM2).reduce(BigDecimal.ZERO, BigDecimal::add));
        when(unitRepo.loadByLocation(any())).thenReturn(List.of());
        movementRepo = mock(StockMovementRepository.class);
        LocationRepository locationRepo = mock(LocationRepository.class);
        when(locationRepo.findAll()).thenReturn(List.of(zone, rack4, rack5, truckLocation));
        TripRepository tripRepo = mock(TripRepository.class);
        when(tripRepo.lockById(trip.getId())).thenReturn(Optional.of(trip));
        when(tripRepo.findDetailedById(trip.getId())).thenReturn(Optional.of(trip));
        ProductRepository productRepo = mock(ProductRepository.class);
        when(productRepo.lockAllById(any())).thenReturn(List.of(clear6));
        LocationService locationService = mock(LocationService.class);
        when(locationService.findByCode(any())).thenAnswer(a -> List.of(rack4, rack5, truckLocation).stream()
                .filter(l -> l.getCode().equalsIgnoreCase(String.valueOf(a.<String>getArgument(0)).trim())).findFirst());

        TripBreakageRepository breakageRepo = mock(TripBreakageRepository.class);
        when(breakageRepo.save(any())).thenAnswer(a -> {
            TripBreakage b = a.getArgument(0);
            b.setId(UUID.randomUUID());
            breakages.add(b);
            return b;
        });
        when(breakageRepo.findByTrip_IdOrderByHappenedAtAscUnitCodeAsc(any())).thenAnswer(a -> List.copyOf(breakages));
        when(breakageRepo.countByTrip_IdAndStatus(any(), any())).thenAnswer(a -> breakages.stream()
                .filter(b -> b.getStatus() == a.getArgument(1)).count());
        when(breakageRepo.findByClientId(any())).thenAnswer(a -> breakages.stream().filter(b -> a.getArgument(0).equals(b.getClientId())).findFirst());
        when(breakageRepo.findStanding(any(), any())).thenAnswer(a -> breakages.stream()
                .filter(b -> b.getStockUnitId().equals(a.getArgument(1)) && b.getStatus() != BreakageStatus.REJECTED).findFirst());
        when(breakageRepo.lockById(any())).thenAnswer(a -> breakages.stream().filter(b -> b.getId().equals(a.getArgument(0))).findFirst());
        TripReturnExtraRepository extraRepo = mock(TripReturnExtraRepository.class);
        when(extraRepo.findByTrip_IdOrderByScannedAtAscCodeAsc(any())).thenAnswer(a -> List.copyOf(extras));
        when(extraRepo.save(any())).thenAnswer(a -> {
            TripReturnExtra x = a.getArgument(0);
            x.setId(UUID.randomUUID());
            extras.add(x);
            return x;
        });

        AuditCaseRepository caseRepo = mock(AuditCaseRepository.class);
        when(caseRepo.save(any())).thenAnswer(a -> {
            AuditCase c = a.getArgument(0);
            c.setId(UUID.randomUUID());
            c.getLines().forEach(l -> l.setId(UUID.randomUUID()));
            cases.add(c);
            return c;
        });
        when(caseRepo.lockById(any())).thenAnswer(a -> cases.stream().filter(c -> c.getId().equals(a.getArgument(0))).findFirst());
        when(caseRepo.findDetailedById(any())).thenAnswer(a -> cases.stream().filter(c -> c.getId().equals(a.getArgument(0))).findFirst());
        when(caseRepo.findByTrip_Id(any())).thenAnswer(a -> cases.stream().filter(c -> c.getTrip().getId().equals(a.getArgument(0))).findFirst());
        AuditCaseLineRepository lineRepo = mock(AuditCaseLineRepository.class);
        when(lineRepo.findOpenMissing(any())).thenAnswer(a -> cases.stream().flatMap(c -> c.getLines().stream())
                .filter(l -> a.getArgument(0).equals(l.getStockUnitId()) && l.isMissing() && !l.isSettled()).findFirst());

        DocumentNumberService numbers = mock(DocumentNumberService.class);
        when(numbers.next(DocumentType.AUDIT_CASE)).thenReturn("AC-WH-2026-000001");
        postingService = mock(PostingService.class);
        when(postingService.stockValues(any())).thenAnswer(a -> new PostingService.StockValues(Map.of(), Map.of()));
        notifier = mock(Notifier.class);
        StockService stockService = new StockService(unitRepo, movementRepo, mock(StockCostEntryRepository.class), mock(StockAdjustmentLineRepository.class),
                mock(StockCountRepository.class), mock(SalesInvoiceLineRepository.class), tripRepo, locationRepo, numbers, CLOCK);
        AttachmentService attachments = mock(AttachmentService.class);
        when(attachments.add(any(), any(), any(), any(), any())).thenAnswer(a -> {
            Attachment photo = new Attachment();
            photo.setId(UUID.randomUUID());
            return photo;
        });
        ApiDeviceRepository deviceRepo = mock(ApiDeviceRepository.class);
        phone = new ApiDevice();
        phone.setId(UUID.randomUUID());
        when(deviceRepo.findById(phone.getId())).thenReturn(Optional.of(phone));
        DriverRepository driverRepo = mock(DriverRepository.class);
        when(driverRepo.findByUser_Id(21L)).thenReturn(Optional.of(jean));
        floatService = mock(FloatService.class);
        when(floatService.cashOf(any(UUID.class))).thenReturn(BigDecimal.ZERO);
        when(floatService.ofTrip(any())).thenReturn(Optional.empty());
        LinePricing pricing = mock(LinePricing.class);
        when(pricing.baseDecimals()).thenReturn(0);

        caseService = new AuditCaseService(caseRepo, lineRepo, unitRepo, productRepo, locationService, stockService, postingService, numbers,
                notifier, CLOCK);
        returnService = new TripReturnService(tripRepo, breakageRepo, extraRepo, unitRepo, productRepo, locationService, stockService,
                postingService, caseService, CLOCK);
        breakageService = new BreakageService(tripRepo, breakageRepo, unitRepo, driverRepo, deviceRepo, attachments, notifier, CLOCK);
        closeService = new TripCloseService(tripRepo, caseRepo, floatService, pricing, CLOCK, settings, notifier);
        signIn(7L, "supervisor1");
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aUnitMissingWithNoBreakageOpensAnAuditCaseAndTheTripCannotClose() {   // AT-06, FLT-09, FLT-10
        TripReturnService.ScanResult scans = returnService.scanBack(trip.getId(), "u-wh-000101\nU-WH-000102\nU-WH-000102");
        assertThat(scans.scans()).extracting(TripReturnService.Scanned::outcome)
                .containsExactly(TripReturnService.ScanOutcome.BACK, TripReturnService.ScanOutcome.BACK);    // a label twice in one scan counts once
        assertThat(returnService.scanBack(trip.getId(), "U-WH-000102").scans()).extracting(TripReturnService.Scanned::outcome)
                .containsExactly(TripReturnService.ScanOutcome.ALREADY);

        TripReturnService.Reconciled r = returnService.reconcile(trip.getId(), null);
        assertThat(trip.getStatus()).isEqualTo(TripStatus.RETURNED);
        assertThat(trip.getReturnedPieces()).isEqualTo(2);
        assertThat(trip.getMissingPieces()).isEqualTo(1);
        // Units back go on the rack they left from, available, with an UNLOAD movement; the missing one stays on the vehicle
        assertThat(unit("U-WH-000101").getStatus()).isEqualTo(StockStatus.AVAILABLE);
        assertThat(unit("U-WH-000101").getLocation()).isEqualTo(rack4);
        assertThat(unit("U-WH-000103").getStatus()).isEqualTo(StockStatus.ON_VEHICLE);
        ArgumentCaptor<StockMovement> moves = ArgumentCaptor.forClass(StockMovement.class);
        verify(movementRepo, times(2)).save(moves.capture());
        assertThat(moves.getAllValues()).extracting(StockMovement::getType).containsOnly(MovementType.UNLOAD);
        verify(postingService, never()).tripReturn(any(), any(), any());             // nothing left stock

        // The audit case, the owner told
        AuditCase c = r.auditCase();
        assertThat(c.getNumber()).isEqualTo("AC-WH-2026-000001");
        assertThat(c.getMissingCount()).isEqualTo(1);
        assertThat(c.getMissingValue()).isEqualByComparingTo("36000.00");
        assertThat(c.getLines()).extracting(AuditCaseLine::getUnitCode).containsExactly("U-WH-000103");
        verify(notifier).holders(eq("ALERT_EOD"), isNull(), eq(NotificationKind.EOD), eq("notify.eod.case.title"), eq("notify.eod.case.message"),
                eq("/audit-cases/" + c.getId()), eq("AC-WH-2026-000001"), eq("TRP-WH-2026-000009"), eq("RAC123A"), eq("Jean Driver"), eq("1"), eq("0"));

        // The trip cannot close while the case is open, even with the cash declared
        closeService.declareCash(trip.getId(), new BigDecimal("0"), TripCloseService.VIA_OFFICE);
        assertThatThrownBy(() -> closeService.close(trip.getId()))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "trip.close.caseOpen");
        // Escalated to the owner, it may close
        caseService.escalate(c.getId(), "The driver says it was handed over at Musanze");
        assertThat(c.getStatus()).isEqualTo(AuditCaseStatus.ESCALATED);
        closeService.close(trip.getId());
        assertThat(trip.getStatus()).isEqualTo(TripStatus.CLOSED);
    }

    @Test
    void anAcceptedBreakageLeavesStockToSpoilageAndUndecidedBreakageWaits() {   // FLT-08
        signIn(21L, "jdriver");
        UUID report = UUID.randomUUID();
        BreakageService.Result first = breakageService.reportFromPhone(principal(21L, "jdriver"), phone.getId(), new BreakageService.Report(report, trip.getId(),
                unit("U-WH-000103").getId(), "Fell when braking", "Nyabugogo", OffsetDateTime.parse("2026-10-11T11:40:00+02:00")), "u.jpg", new byte[]{1});
        assertThat(first.outcome()).isEqualTo(BreakageService.Outcome.RECORDED);
        BreakageService.Result again = breakageService.reportFromPhone(principal(21L, "jdriver"), phone.getId(), new BreakageService.Report(report, trip.getId(),
                unit("U-WH-000103").getId(), "Fell when braking", "Nyabugogo", null), "u.jpg", new byte[]{1});
        assertThat(again.outcome()).isEqualTo(BreakageService.Outcome.ALREADY);   // the same report sent twice is kept once
        assertThat(breakages).hasSize(1);
        assertThat(first.breakage().getHappenedAt()).isEqualTo(LocalDateTime.of(2026, 10, 11, 11, 40));
        verify(notifier).holders(eq("VALIDATE_BREAKAGE"), any(), eq(NotificationKind.EOD), eq("notify.eod.breakage.title"),
                eq("notify.eod.breakage.message"), eq("/trips/" + trip.getId() + "?tab=return"), eq("U-WH-000103"), any(), any(), any());

        // The reporter never decides; until someone does, the trip cannot be reconciled
        assertThatThrownBy(() -> breakageService.decide(trip.getId(), first.breakage().getId(), true, null))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "breakage.ownReport");
        signIn(7L, "supervisor1");
        assertThatThrownBy(() -> returnService.reconcile(trip.getId(), null))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "trip.return.breakageUndecided");
        assertThatThrownBy(() -> breakageService.decide(trip.getId(), first.breakage().getId(), false, " "))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "breakage.note.required");
        breakageService.decide(trip.getId(), first.breakage().getId(), true, null);

        returnService.scanBack(trip.getId(), "U-WH-000101 U-WH-000102");
        TripReturnService.Reconciled r = returnService.reconcile(trip.getId(), "wh-a-r05");
        assertThat(r.auditCase()).isNull();                                          // every unit accounted for
        assertThat(unit("U-WH-000103").getStatus()).isEqualTo(StockStatus.BROKEN);
        assertThat(unit("U-WH-000103").getLocation()).isNull();
        assertThat(unit("U-WH-000101").getLocation()).isEqualTo(rack5);              // the place given
        assertThat(trip.getBrokenPieces()).isEqualTo(1);
        verify(postingService).tripReturn(eq(trip), argThat(cost -> cost.compareTo(new BigDecimal("36000.00")) == 0), any());
        assertThat(clear6.getMacPerM2()).isGreaterThan(new BigDecimal("5000"));      // left at its own cost, below area x MAC: the MAC rises
    }

    @Test
    void aLabelNotOnTheTripIsKeptAsUnexpectedAndAPlaceLabelIsNot() {
        StockUnit other = new StockUnit();
        other.setId(UUID.randomUUID());
        other.setCode("U-WH-000200");
        other.setProduct(clear6);
        other.setStatus(StockStatus.AVAILABLE);
        units.add(other);
        TripReturnService.ScanResult scans = returnService.scanBack(trip.getId(), "U-WH-000200 WH-A-R04 XYZ-1");
        assertThat(scans.scans()).extracting(TripReturnService.Scanned::outcome).containsExactly(TripReturnService.ScanOutcome.EXTRA,
                TripReturnService.ScanOutcome.PLACE, TripReturnService.ScanOutcome.EXTRA);
        assertThat(returnService.scanBack(trip.getId(), "U-WH-000200").scans()).extracting(TripReturnService.Scanned::outcome)
                .containsExactly(TripReturnService.ScanOutcome.ALREADY);
        assertThat(extras).extracting(TripReturnExtra::getCode).containsExactly("U-WH-000200", "XYZ-1");
        assertThat(extras.get(0).getStockUnitId()).isEqualTo(other.getId());

        returnService.scanBack(trip.getId(), "U-WH-000101 U-WH-000102 U-WH-000103");
        TripReturnService.Reconciled r = returnService.reconcile(trip.getId(), null);
        assertThat(r.auditCase().getUnexpectedCount()).isEqualTo(2);
        assertThat(r.auditCase().getMissingCount()).isZero();
        AuditCaseLine line = AuditCaseService.sortedLines(r.auditCase()).get(0);
        assertThatThrownBy(() -> caseService.writeOff(r.auditCase().getId(), line.getId(), "Lost"))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "auditCase.line.kind");
        caseService.explain(r.auditCase().getId(), line.getId(), "Moved to the vehicle by mistake, transferred back");
        caseService.explain(r.auditCase().getId(), AuditCaseService.sortedLines(r.auditCase()).get(1).getId(), "Old label on a rack post");
        assertThat(r.auditCase().getStatus()).isEqualTo(AuditCaseStatus.RESOLVED);
    }

    @Test
    void missingUnitsAreFoundWrittenOffChargedOrSettledByALateSale() {   // FLT-10, FLT-11
        returnService.reconcile(trip.getId(), null);                                // nothing scanned: three missing
        AuditCase c = cases.get(0);
        List<AuditCaseLine> lines = AuditCaseService.sortedLines(c);
        assertThat(lines).hasSize(3);

        caseService.found(c.getId(), lines.get(0).getId(), "WH-A-R05", null);
        assertThat(unit(lines.get(0).getUnitCode()).getStatus()).isEqualTo(StockStatus.AVAILABLE);
        assertThat(unit(lines.get(0).getUnitCode()).getLocation()).isEqualTo(rack5);
        assertThat(lines.get(0).getOutcome()).isEqualTo(AuditLineOutcome.FOUND);

        caseService.writeOff(c.getId(), lines.get(1).getId(), "Not found after a week");
        assertThat(unit(lines.get(1).getUnitCode()).getStatus()).isEqualTo(StockStatus.LOST);
        verify(postingService).auditLoss(eq(c), eq(lines.get(1).getUnitCode()), argThat(v -> v.compareTo(new BigDecimal("36000.00")) == 0),
                isNull(), any());
        assertThatThrownBy(() -> caseService.writeOff(c.getId(), lines.get(1).getId(), "again"))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "auditCase.line.settled");
        assertThat(c.getStatus()).isEqualTo(AuditCaseStatus.OPEN);

        // A sale of the last one, made before the return and synced after it, settles it as sold
        caseService.unitSold(unit(lines.get(2).getUnitCode()).getId(), "MINV-WH-2026-000020");
        assertThat(lines.get(2).getOutcome()).isEqualTo(AuditLineOutcome.SOLD);
        assertThat(lines.get(2).getNote()).isEqualTo("MINV-WH-2026-000020");
        assertThat(c.getStatus()).isEqualTo(AuditCaseStatus.RESOLVED);
        assertThat(c.getResolvedAt()).isNotNull();
    }

    @Test
    void aMissingUnitChargedToTheDriverNamesThem() {   // FLT-11
        returnService.scanBack(trip.getId(), "U-WH-000101 U-WH-000102");
        returnService.reconcile(trip.getId(), null);
        AuditCase c = cases.get(0);
        caseService.charge(c.getId(), c.getLines().iterator().next().getId(), "Owner: charged, delivery not proven");
        verify(postingService).auditLoss(eq(c), eq("U-WH-000103"), any(), eq(jean.getId()), any());
        assertThat(c.getLines().iterator().next().getOutcome()).isEqualTo(AuditLineOutcome.CHARGED);
        assertThat(c.getStatus()).isEqualTo(AuditCaseStatus.RESOLVED);
    }

    @Test
    void theCashIsDeclaredOnceAndTheFloatIsClearedBeforeTheTripCloses() {   // MPOS-07, SRS 5.1
        returnService.scanBack(trip.getId(), "U-WH-000101 U-WH-000102 U-WH-000103");
        returnService.reconcile(trip.getId(), null);
        when(floatService.cashOf(trip.getId())).thenReturn(new BigDecimal("295008"));
        assertThatThrownBy(() -> closeService.close(trip.getId()))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "trip.close.notDeclared");
        // On the phone only the trip's own driver declares
        assertThatThrownBy(() -> closeService.declareCash(trip.getId(), new BigDecimal("295008"), TripCloseService.VIA_PHONE))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "trip.cash.notYourTrip");
        signIn(21L, "jdriver");
        assertThatThrownBy(() -> closeService.declareCash(trip.getId(), new BigDecimal("295008.5"), TripCloseService.VIA_PHONE))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "trip.cash.decimals");
        closeService.declareCash(trip.getId(), new BigDecimal("295008"), TripCloseService.VIA_PHONE);
        assertThat(trip.getCashDeclaredVia()).isEqualTo("PHONE");
        assertThatThrownBy(() -> closeService.declareCash(trip.getId(), new BigDecimal("1"), TripCloseService.VIA_PHONE))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "trip.cash.declared");
        signIn(7L, "supervisor1");
        assertThatThrownBy(() -> closeService.close(trip.getId()))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "trip.close.floatNotCleared");
        when(floatService.ofTrip(trip.getId())).thenReturn(Optional.of(new FloatClearance()));
        assertThat(closeService.settlement(trip).canClose()).isTrue();
        closeService.close(trip.getId());
        assertThat(trip.getClosedBy()).isEqualTo("supervisor1");
    }

    @Test
    void aSettledTripIsClearedThenCountersignedByAnotherPersonWhichClosesIt() {   // F-11, N.15
        when(settings.getBoolean(SettingKey.FLEET_CLEARANCE_COUNTERSIGN)).thenReturn(true);
        returnService.scanBack(trip.getId(), "U-WH-000101 U-WH-000102 U-WH-000103");
        returnService.reconcile(trip.getId(), null);
        when(floatService.cashOf(trip.getId())).thenReturn(BigDecimal.ZERO);
        closeService.declareCash(trip.getId(), BigDecimal.ZERO, TripCloseService.VIA_OFFICE);
        // Nothing to countersign yet, and the trip no longer closes in one step
        assertThatThrownBy(() -> closeService.countersign(trip.getId()))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "trip.clearance.notCleared");
        assertThatThrownBy(() -> closeService.close(trip.getId()))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "trip.close.byClearance");

        closeService.clear(trip.getId());
        assertThat(trip.getClearedBy()).isEqualTo("supervisor1");
        assertThat(trip.getStatus()).isEqualTo(TripStatus.RETURNED);
        verify(notifier).holders(eq("COUNTERSIGN_TRIP_CLEARANCE"), eq(7L), eq(NotificationKind.EOD), eq("notify.trip.cleared"),
                eq("notify.trip.clearedText"), any(), eq(trip.getNumber()), eq("supervisor1"));
        assertThatThrownBy(() -> closeService.clear(trip.getId()))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "trip.clearance.cleared");
        // Whoever cleared it does not countersign it
        assertThatThrownBy(() -> closeService.countersign(trip.getId()))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "trip.clearance.own");

        signIn(8L, "dof1");
        closeService.countersign(trip.getId());
        assertThat(trip.getStatus()).isEqualTo(TripStatus.CLOSED);
        assertThat(trip.getClosedBy()).isEqualTo("dof1");
    }

    private StockUnit unit(String code) {
        return units.stream().filter(u -> u.getCode().equals(code)).findFirst().orElseThrow();
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

    private static AppUserPrincipal principal(long id, String username) {
        return new AppUserPrincipal(id, username, username, "x", true, true, List.of());
    }

    private static void signIn(long id, String username) {
        AppUserPrincipal principal = principal(id, username);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
