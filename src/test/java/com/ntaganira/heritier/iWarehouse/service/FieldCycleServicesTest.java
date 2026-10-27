package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.*;
import com.ntaganira.heritier.iWarehouse.enums.*;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.repository.*;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 * - File      : FieldCycleServicesTest.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : On the road (M15): collections banked or sent by mobile money during a trip (BP 7.3 rule 4: within the cash
 *               the driver holds, from the departure until the float is cleared, posted once, taken once per phone id) and
 *               the counts of what is on board (SDP 6: the driver's daily count from the phone, a spot check by someone
 *               else, missing units told to the office).
 * </pre>
 */
class FieldCycleServicesTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-14T16:00:00Z"), ZoneId.of("Africa/Kigali"));

    private final Location truckLocation = new Location();
    private final List<TripDeposit> deposits = new ArrayList<>();
    private final List<TripCount> counts = new ArrayList<>();
    private final List<TripCountLine> countLines = new ArrayList<>();
    private final List<StockUnit> onBoard = new ArrayList<>();
    private Trip trip;
    private FloatService floatService;
    private FloatClearanceRepository clearanceRepo;
    private PostingService postingService;
    private Notifier notifier;
    private TripDepositService depositService;
    private TripCountService countService;

    @BeforeEach
    void setUp() {
        truckLocation.setId(UUID.randomUUID());
        Vehicle truck = new Vehicle();
        truck.setId(UUID.randomUUID());
        truck.setPlate("RAC123A");
        truck.setLocation(truckLocation);
        User user = User.builder().id(21L).username("jdriver").fullName("Jean Driver").email("j@x").password("x").build();
        Driver jean = new Driver();
        jean.setId(UUID.randomUUID());
        jean.setUser(user);
        trip = new Trip();
        trip.setId(UUID.randomUUID());
        trip.setNumber("TRP-WH-2026-000011");
        trip.setVehicle(truck);
        trip.setDriver(jean);
        trip.setStatus(TripStatus.DEPARTED);
        trip.setDepartedAt(LocalDateTime.of(2026, 10, 13, 7, 30));
        for (int i = 1; i <= 3; i++) {
            StockUnit u = new StockUnit();
            u.setId(UUID.randomUUID());
            u.setCode("U-WH-00010" + i);
            u.setStatus(StockStatus.ON_VEHICLE);
            u.setLocation(truckLocation);
            onBoard.add(u);
        }

        TripRepository tripRepo = mock(TripRepository.class);
        when(tripRepo.lockById(trip.getId())).thenReturn(Optional.of(trip));
        when(tripRepo.findDetailedById(trip.getId())).thenReturn(Optional.of(trip));
        TripDepositRepository depositRepo = mock(TripDepositRepository.class);
        when(depositRepo.save(any())).thenAnswer(a -> {
            TripDeposit d = a.getArgument(0);
            d.setId(UUID.randomUUID());
            deposits.add(d);
            return d;
        });
        when(depositRepo.findByClientId(any())).thenAnswer(a -> deposits.stream().filter(d -> a.getArgument(0).equals(d.getClientId())).findFirst());
        clearanceRepo = mock(FloatClearanceRepository.class);
        floatService = mock(FloatService.class);
        when(floatService.expectedOf(trip.getId())).thenReturn(new BigDecimal("300000.00"));
        postingService = mock(PostingService.class);
        DocumentNumberService numbers = mock(DocumentNumberService.class);
        when(numbers.next(DocumentType.TRIP_DEPOSIT)).thenReturn("TDP-WH-2026-000001", "TDP-WH-2026-000002");
        depositService = new TripDepositService(depositRepo, tripRepo, clearanceRepo, floatService, postingService, numbers, CLOCK);

        TripCountRepository countRepo = mock(TripCountRepository.class);
        when(countRepo.save(any())).thenAnswer(a -> {
            TripCount c = a.getArgument(0);
            c.setId(UUID.randomUUID());
            counts.add(c);
            return c;
        });
        when(countRepo.findByClientId(any())).thenAnswer(a -> counts.stream().filter(c -> a.getArgument(0).equals(c.getClientId())).findFirst());
        TripCountLineRepository lineRepo = mock(TripCountLineRepository.class);
        when(lineRepo.save(any())).thenAnswer(a -> {
            countLines.add(a.getArgument(0));
            return a.getArgument(0);
        });
        when(lineRepo.findByCountIdOrderByOutcomeAscUnitCodeAsc(any())).thenAnswer(a -> countLines.stream()
                .filter(l -> a.getArgument(0).equals(l.getCountId())).toList());
        StockUnitRepository unitRepo = mock(StockUnitRepository.class);
        when(unitRepo.findByLocation_IdAndStatusOrderByCodeAsc(truckLocation.getId(), StockStatus.ON_VEHICLE)).thenAnswer(a -> onBoard);
        when(unitRepo.findByCodeIn(any())).thenReturn(List.of());
        notifier = mock(Notifier.class);
        countService = new TripCountService(countRepo, lineRepo, tripRepo, unitRepo, notifier, CLOCK);
        signIn(21L, "jdriver");
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- deposits (BP 7.3 rule 4)

    @Test
    void aDepositTakesCashOffTheDriversFloatOnceAndWithinWhatTheyHold() {
        UUID phoneId = UUID.randomUUID();
        TripDepositService.Form form = new TripDepositService.Form(PaymentMethod.BANK_TRANSFER, new BigDecimal("200000"), " bk-58812 ",
                LocalDate.of(2026, 10, 14));
        TripDeposit d = depositService.record(trip.getId(), form, phoneId, TripCloseService.VIA_PHONE);
        assertThat(d.getNumber()).isEqualTo("TDP-WH-2026-000001");
        assertThat(d.getReference()).isEqualTo("BK-58812");
        assertThat(d.getAmount()).isEqualByComparingTo("200000.00");
        assertThat(d.getDriverId()).isEqualTo(trip.getDriver().getId());
        assertThat(d.getRecordedVia()).isEqualTo("PHONE");
        verify(postingService).tripDeposit(d, trip);
        // AT-05 for deposits: the same deposit sent again is the same deposit, posted once
        assertThat(depositService.record(trip.getId(), form, phoneId, TripCloseService.VIA_PHONE)).isSameAs(d);
        verify(postingService, times(1)).tripDeposit(any(), any());

        // Never more than the cash still held (sales less deposits less costs from the float)
        when(floatService.expectedOf(trip.getId())).thenReturn(new BigDecimal("100000.00"));
        assertThatThrownBy(() -> depositService.record(trip.getId(), new TripDepositService.Form(PaymentMethod.MOBILE_MONEY,
                new BigDecimal("100000.01"), "MP-1", null), UUID.randomUUID(), TripCloseService.VIA_OFFICE))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "tripDeposit.tooMuch");
    }

    @Test
    void aDepositNeedsItsReferenceADayOfTheTripAndAFloatNotCleared() {
        assertThatThrownBy(() -> depositService.record(trip.getId(), new TripDepositService.Form(PaymentMethod.BANK_TRANSFER,
                new BigDecimal("1000"), " ", null), UUID.randomUUID(), TripCloseService.VIA_OFFICE))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "tripDeposit.reference");
        assertThatThrownBy(() -> depositService.record(trip.getId(), new TripDepositService.Form(PaymentMethod.CASH,
                new BigDecimal("1000"), "X", null), UUID.randomUUID(), TripCloseService.VIA_OFFICE))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "tripDeposit.method");
        assertThatThrownBy(() -> depositService.record(trip.getId(), new TripDepositService.Form(PaymentMethod.BANK_TRANSFER,
                new BigDecimal("0"), "X", null), UUID.randomUUID(), TripCloseService.VIA_OFFICE))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "tripDeposit.amount");
        // Before the departure or after today
        for (LocalDate day : List.of(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 15))) {
            assertThatThrownBy(() -> depositService.record(trip.getId(), new TripDepositService.Form(PaymentMethod.BANK_TRANSFER,
                    new BigDecimal("1000"), "X", day), UUID.randomUUID(), TripCloseService.VIA_OFFICE))
                    .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "tripDeposit.date");
        }
        when(clearanceRepo.existsByTrip_Id(trip.getId())).thenReturn(true);
        assertThatThrownBy(() -> depositService.record(trip.getId(), new TripDepositService.Form(PaymentMethod.BANK_TRANSFER,
                new BigDecimal("1000"), "X", null), UUID.randomUUID(), TripCloseService.VIA_OFFICE))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "tripDeposit.cleared");
        trip.setStatus(TripStatus.PLANNED);
        assertThatThrownBy(() -> depositService.record(trip.getId(), new TripDepositService.Form(PaymentMethod.BANK_TRANSFER,
                new BigDecimal("1000"), "X", null), UUID.randomUUID(), TripCloseService.VIA_OFFICE))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "tripDeposit.notAway");
        verify(postingService, never()).tripDeposit(any(), any());
    }

    // ---------------------------------------------------------------- counts (SDP 6)

    @Test
    void theDriversDailyCountComparesWithWhatIsOnBoardAndTellsTheOfficeOfAnythingMissing() {
        UUID phoneId = UUID.randomUUID();
        TripCountService.Counted c = countService.count(trip.getId(), TripCountKind.DAILY,
                List.of("u-wh-000101", "U-WH-000102", "U-WH-000999"), "Two under the tarpaulin", phoneId);
        assertThat(c.count().getExpected()).isEqualTo(3);
        assertThat(c.count().getFound()).isEqualTo(2);
        assertThat(c.count().getMissing()).isEqualTo(1);
        assertThat(c.count().getExtra()).isEqualTo(1);
        assertThat(c.result().missing()).containsExactly("U-WH-000103");
        assertThat(c.result().extra()).containsExactly("U-WH-000999");
        assertThat(countLines).extracting(TripCountLine::getOutcome)
                .containsExactlyInAnyOrder(TripCountOutcome.FOUND, TripCountOutcome.FOUND, TripCountOutcome.MISSING, TripCountOutcome.EXTRA);
        verify(notifier).holders(eq("ALERT_EOD"), eq(21L), eq(NotificationKind.EOD), eq("notify.tripCount.missing"),
                eq("notify.tripCount.missingText"), any(), eq(trip.getNumber()), eq("1"), eq("U-WH-000103"));
        // Sent again from the phone: the same count, nothing new
        TripCountService.Counted again = countService.count(trip.getId(), TripCountKind.DAILY, List.of("U-WH-000101"), null, phoneId);
        assertThat(again.count()).isSameAs(c.count());
        assertThat(again.result().missing()).containsExactly("U-WH-000103");
        assertThat(counts).hasSize(1);
    }

    @Test
    void theDriverCountsDailyAndSomeoneElseMakesTheSpotCheck() {
        assertThatThrownBy(() -> countService.count(trip.getId(), TripCountKind.SPOT, List.of("U-WH-000101"), null, null))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "tripCount.spot.driver");
        assertThatThrownBy(() -> countService.count(trip.getId(), TripCountKind.DAILY, List.of(" ", ""), null, null))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "tripCount.nothing");
        signIn(7L, "supervisor1");
        assertThatThrownBy(() -> countService.count(trip.getId(), TripCountKind.DAILY, List.of("U-WH-000101"), null, null))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "tripCount.daily.notDriver");
        TripCountService.Counted spot = countService.count(trip.getId(), TripCountKind.SPOT,
                List.of("U-WH-000101", "U-WH-000102", "U-WH-000103"), null, null);
        assertThat(spot.result().isClean()).isTrue();
        verifyNoInteractions(notifier);
        // Only a vehicle on the road is counted
        trip.setStatus(TripStatus.RETURNED);
        assertThatThrownBy(() -> countService.count(trip.getId(), TripCountKind.SPOT, List.of("U-WH-000101"), null, null))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "tripCount.notOnRoad");
    }

    private static void signIn(long id, String username) {
        AppUserPrincipal principal = new AppUserPrincipal(id, username, username, "x", true, true, List.of());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
