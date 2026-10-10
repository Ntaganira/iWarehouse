package com.ntaganira.heritier.iWarehouse.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * - File      : MobileSaleServiceTest.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : Syncing sales made on a driver's phone, with the real stock service. AT-04: three sales made offline reach
 *               the server once each, the units leave the vehicle, cash goes to the driver's float and each receipt is
 *               queued for EBM. AT-05: the same sale sent twice is stored once. A sale the server cannot take as it was
 *               made (a unit gone, a number not the phone's or used, a price below the limit, other amounts or payments,
 *               no trip) is kept for the supervisor, never dropped (SYNC-05).
 * </pre>
 */
class MobileSaleServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-10T12:00:00Z"), ZoneId.of("Africa/Kigali"));
    private static final OffsetDateTime MADE = OffsetDateTime.parse("2026-10-10T09:15:00Z");

    private final Location truckLocation = location("VEH-RAC123A");
    private final Location rack = location("WH-A-R04");
    private final Product clear6 = product();
    private final UUID retail = UUID.randomUUID();
    private final List<StockUnit> units = new ArrayList<>();
    private final Map<UUID, SalesInvoice> invoices = new HashMap<>();
    private final Map<UUID, SyncConflict> conflicts = new HashMap<>();
    private final Map<String, TripInvoiceNumber> numbers = new HashMap<>();

    private Trip trip;
    private Driver driver;
    private ApiDevice phone;
    private ApiDevice otherPhone;
    private Customer walkIn;
    private AppUserPrincipal jean;
    private BigDecimal limit = BigDecimal.ZERO;
    private PostingService postingService;
    private EbmService ebmService;
    private Notifier notifier;
    private StockMovementRepository movementRepo;
    private TripRepository tripRepo;
    private MobileSaleService service;

    @BeforeEach
    void setUp() {
        StockUnitRepository unitRepo = mock(StockUnitRepository.class);
        movementRepo = mock(StockMovementRepository.class);
        SalesInvoiceRepository invoiceRepo = mock(SalesInvoiceRepository.class);
        SalesPaymentRepository paymentRepo = mock(SalesPaymentRepository.class);
        SyncConflictRepository conflictRepo = mock(SyncConflictRepository.class);
        TripInvoiceNumberRepository numberRepo = mock(TripInvoiceNumberRepository.class);
        tripRepo = mock(TripRepository.class);
        DriverRepository driverRepo = mock(DriverRepository.class);
        ApiDeviceRepository deviceRepo = mock(ApiDeviceRepository.class);
        ProductRepository productRepo = mock(ProductRepository.class);
        CustomerRepository customerRepo = mock(CustomerRepository.class);
        PriceListRepository priceListRepo = mock(PriceListRepository.class);
        MobileTripService tripService = mock(MobileTripService.class);
        LinePricing pricing = mock(LinePricing.class);
        postingService = mock(PostingService.class);
        ebmService = mock(EbmService.class);
        notifier = mock(Notifier.class);

        Vehicle truck = new Vehicle();
        truck.setId(UUID.randomUUID());
        truck.setPlate("RAC123A");
        truck.setLocation(truckLocation);
        User user = User.builder().id(21L).username("driver-m7").fullName("Jean Driver").email("j@x").password("x").build();
        driver = new Driver();
        driver.setId(UUID.randomUUID());
        driver.setUser(user);
        driver.setUsername("driver-m7");
        trip = new Trip();
        trip.setId(UUID.randomUUID());
        trip.setNumber("TRP-WH-2026-000002");
        trip.setVehicle(truck);
        trip.setDriver(driver);
        trip.setStatus(TripStatus.DEPARTED);
        trip.setDepartedAt(LocalDateTime.of(2026, 10, 10, 7, 0));
        phone = device("Jean's phone");
        otherPhone = device("Spare phone");
        walkIn = new Customer();
        walkIn.setId(UUID.randomUUID());
        walkIn.setName("Walk-in customer");
        walkIn.setType(CustomerType.WALK_IN);
        walkIn.setDefaultCustomer(true);
        walkIn.setEnabled(true);
        jean = new AppUserPrincipal(21L, "Jean Driver", "driver-m7", "x", true, true, List.of());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(jean, null, List.of()));

        for (int i = 1; i <= 5; i++) {
            StockUnit u = new StockUnit();
            u.setId(UUID.randomUUID());
            u.setCode(String.format("U-WH-%06d", 100 + i));
            u.setProduct(clear6);
            u.setKind(UnitKind.CUT_PIECE);
            u.setWidthMm(1200);
            u.setHeightMm(800);
            u.setAreaM2(new BigDecimal("0.9600"));
            u.setWeightKg(new BigDecimal("14.40"));
            u.setUnitCost(new BigDecimal("4800.00"));
            u.setStatus(StockStatus.ON_VEHICLE);
            u.setLocation(truckLocation);
            units.add(u);
            TripInvoiceNumber n = new TripInvoiceNumber();
            n.setId(UUID.randomUUID());
            n.setTripId(trip.getId());
            n.setDeviceId(phone.getId());
            n.setNumber(String.format("MINV-WH-2026-%06d", i));
            numbers.put(n.getNumber(), n);
        }
        TripInvoiceNumber spare = new TripInvoiceNumber();
        spare.setTripId(trip.getId());
        spare.setDeviceId(otherPhone.getId());
        spare.setNumber("MINV-WH-2026-000099");
        numbers.put(spare.getNumber(), spare);

        when(unitRepo.findByIdIn(any())).thenAnswer(a -> {
            Collection<UUID> ids = a.getArgument(0);
            return units.stream().filter(u -> ids.contains(u.getId())).toList();
        });
        when(invoiceRepo.save(any())).thenAnswer(a -> {
            SalesInvoice s = a.getArgument(0);
            s.setId(UUID.randomUUID());
            invoices.put(s.getClientId(), s);
            return s;
        });
        when(invoiceRepo.findByClientId(any())).thenAnswer(a -> Optional.ofNullable(invoices.get(a.<UUID>getArgument(0))));
        when(paymentRepo.save(any())).thenAnswer(a -> a.getArgument(0));
        when(conflictRepo.save(any())).thenAnswer(a -> {
            SyncConflict c = a.getArgument(0);
            c.setId(UUID.randomUUID());
            conflicts.put(c.getClientId(), c);
            return c;
        });
        when(conflictRepo.findByClientId(any())).thenAnswer(a -> Optional.ofNullable(conflicts.get(a.<UUID>getArgument(0))));
        when(numberRepo.lockByNumber(any())).thenAnswer(a -> Optional.ofNullable(numbers.get(a.<String>getArgument(0))));
        when(tripRepo.findFirstByDriver_IdAndStatus(driver.getId(), TripStatus.DEPARTED)).thenAnswer(a -> Optional.of(trip));
        when(tripRepo.lockById(trip.getId())).thenReturn(Optional.of(trip));
        when(tripRepo.findDetailedById(trip.getId())).thenReturn(Optional.of(trip));
        when(driverRepo.findByUser_Id(21L)).thenReturn(Optional.of(driver));
        when(deviceRepo.findById(phone.getId())).thenReturn(Optional.of(phone));
        when(deviceRepo.findById(otherPhone.getId())).thenReturn(Optional.of(otherPhone));
        when(productRepo.lockAllById(any())).thenReturn(List.of(clear6));
        when(customerRepo.findById(any())).thenReturn(Optional.empty());
        when(customerRepo.findByEnabledTrueOrderByNameAsc()).thenReturn(List.of(walkIn));
        when(priceListRepo.getReferenceById(any())).thenAnswer(a -> {
            PriceList list = new PriceList();
            list.setId(a.getArgument(0));
            return list;
        });
        MobileSales.ListTerms retailTerms = new MobileSales.ListTerms(retail, true, new BigDecimal("0.2500"));
        when(tripService.snapshot(trip.getId())).thenReturn(new MobileSales.Snapshot(retail, Map.of(retail, retailTerms),
                Map.of(retail, Map.of(clear6.getId(), new BigDecimal("27000.00")))));
        when(tripService.discountLimit(21L)).thenAnswer(a -> limit);
        when(pricing.baseDecimals()).thenReturn(0);

        StockService stockService = new StockService(unitRepo, movementRepo, mock(StockCostEntryRepository.class), mock(StockAdjustmentLineRepository.class),
                mock(StockCountRepository.class), mock(SalesInvoiceLineRepository.class), tripRepo, mock(LocationRepository.class),
                mock(DocumentNumberService.class), CLOCK);
        service = new MobileSaleService(invoiceRepo, paymentRepo, conflictRepo, numberRepo, tripRepo, driverRepo, deviceRepo, unitRepo,
                productRepo, customerRepo, priceListRepo, tripService, stockService, postingService, ebmService, pricing, notifier,
                new ObjectMapper().findAndRegisterModules(), CLOCK);
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- AT-04, AT-05

    @Test
    void at04ThreeSalesMadeOfflineAreTakenOnceEachOnTheDriversFloat() {
        List<MobileSaleService.Result> results = List.of(
                service.submit(jean, phone.getId(), sale(1, cash("25920", "30000"), units.get(0))),
                service.submit(jean, phone.getId(), sale(2, momo("25920", "MP-778812"), units.get(1))),
                service.submit(jean, phone.getId(), sale(3, List.of(pay(PaymentMethod.CASH, "30000", null), pay(PaymentMethod.MOBILE_MONEY, "21840", "MP-9")),
                        units.get(2), units.get(3))));

        assertThat(results).extracting(MobileSaleService.Result::outcome).containsOnly(MobileSaleService.Outcome.ACCEPTED);
        assertThat(invoices).hasSize(3);
        SalesInvoice first = results.get(0).invoice();
        assertThat(first.getChannel()).isEqualTo(SaleChannel.MOBILE);
        assertThat(first.getNumber()).isEqualTo("MINV-WH-2026-000001");             // the number the phone printed
        assertThat(first.getTrip()).isSameAs(trip);
        assertThat(first.getDevice()).isSameAs(phone);
        assertThat(first.getStatus()).isEqualTo(SalesInvoiceStatus.POSTED);
        assertThat(first.getTillSession()).isNull();
        assertThat(first.getInvoiceDate()).isEqualTo(LocalDate.of(2026, 10, 10));
        assertThat(first.getClientCreatedAt()).isEqualTo(LocalDateTime.of(2026, 10, 10, 11, 15));   // Kigali time
        assertThat(first.getTotalAmount()).isEqualByComparingTo("25920");
        assertThat(first.getVatAmount()).isEqualByComparingTo("3953.90");
        assertThat(first.getCashTendered()).isEqualByComparingTo("30000");
        assertThat(first.getChangeGiven()).isEqualByComparingTo("4080");
        assertThat(first.getBalanceDue()).isEqualByComparingTo("0");
        assertThat(results.get(2).invoice().getLines()).hasSize(2);
        assertThat(results.get(2).invoice().getTotalAmount()).isEqualByComparingTo("51840");
        // Units off the vehicle, numbers used
        assertThat(units.subList(0, 4)).allMatch(u -> u.getStatus() == StockStatus.SOLD && u.getLocation() == null);
        assertThat(units.get(4).getStatus()).isEqualTo(StockStatus.ON_VEHICLE);
        assertThat(numbers.get("MINV-WH-2026-000003").getInvoiceId()).isEqualTo(results.get(2).invoice().getId());
        ArgumentCaptor<StockMovement> moves = ArgumentCaptor.forClass(StockMovement.class);
        verify(movementRepo, times(4)).save(moves.capture());
        assertThat(moves.getAllValues()).allSatisfy(m -> {
            assertThat(m.getType()).isEqualTo(MovementType.SALE);
            assertThat(m.getFromLocationId()).isEqualTo(truckLocation.getId());
            assertThat(m.getFromStatus()).isEqualTo(StockStatus.ON_VEHICLE);
        });
        // Journals to the driver's float, and each receipt queued for EBM (SYNC-06)
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<List<SalesPayment>> payments = ArgumentCaptor.forClass((Class) List.class);
        verify(postingService, times(3)).mobileSale(any(), payments.capture(), any(), eq(driver.getId()));
        assertThat(payments.getAllValues().get(0)).singleElement().satisfies(p -> {
            assertThat(p.getMethod()).isEqualTo(PaymentMethod.CASH);
            assertThat(p.getTripId()).isEqualTo(trip.getId());
            assertThat(p.getTillSessionId()).isNull();
            assertThat(p.getCashTendered()).isEqualByComparingTo("30000");
            assertThat(p.getUsername()).isEqualTo("driver-m7");
        });
        assertThat(payments.getAllValues().get(1)).singleElement().satisfies(p -> assertThat(p.getReference()).isEqualTo("MP-778812"));
        verify(ebmService, times(3)).queueSale(any());
        verifyNoInteractions(notifier);
    }

    @Test
    void at05TheSameSaleSentTwiceIsStoredOnce() {
        MobileSaleService.SaleRequest req = sale(1, cash("25920", null), units.get(0));
        MobileSaleService.Result first = service.submit(jean, phone.getId(), req);
        MobileSaleService.Result again = service.submit(jean, phone.getId(), req);

        assertThat(first.outcome()).isEqualTo(MobileSaleService.Outcome.ACCEPTED);
        assertThat(again.outcome()).isEqualTo(MobileSaleService.Outcome.ALREADY_ACCEPTED);
        assertThat(again.invoice()).isSameAs(first.invoice());
        assertThat(invoices).hasSize(1);
        verify(postingService, times(1)).mobileSale(any(), any(), any(), any());
        verify(ebmService, times(1)).queueSale(any());
    }

    // ---------------------------------------------------------------- conflicts (SYNC-05)

    @Test
    void aUnitNoLongerOnTheVehicleKeepsTheSaleForTheSupervisor() {
        units.get(0).setStatus(StockStatus.SOLD);
        units.get(0).setLocation(null);
        MobileSaleService.SaleRequest req = sale(1, cash("25920", null), units.get(0));
        MobileSaleService.Result r = service.submit(jean, phone.getId(), req);

        assertThat(r.outcome()).isEqualTo(MobileSaleService.Outcome.CONFLICT);
        assertThat(r.conflict().getReason()).isEqualTo(SyncConflictReason.UNIT_NOT_ON_VEHICLE);
        assertThat(r.conflict().getDetail()).contains("U-WH-000101").contains("RAC123A");
        assertThat(r.conflict().getNumber()).isEqualTo("MINV-WH-2026-000001");
        assertThat(r.conflict().getTrip()).isSameAs(trip);
        assertThat(r.conflict().getPayload()).contains(req.clientId().toString());
        assertThat(r.conflict().getTotalAmount()).isEqualByComparingTo("25920");
        assertThat(r.conflict().getClientCreatedAt()).isEqualTo(LocalDateTime.of(2026, 10, 10, 11, 15));
        assertThat(invoices).isEmpty();
        assertThat(numbers.get("MINV-WH-2026-000001").isUsed()).isFalse();
        verify(notifier).holders(eq("REVIEW_SYNC_CONFLICT"), isNull(), eq(NotificationKind.SYNC), eq("notify.sync.conflict.title"),
                eq("notify.sync.conflict.message"), eq("/sync-conflicts/" + r.conflict().getId()), eq("MINV-WH-2026-000001"), eq("Jean Driver"),
                eq("UNIT_NOT_ON_VEHICLE"));
        verifyNoInteractions(postingService, ebmService);
        // Sent again: found as it was kept, not kept twice
        assertThat(service.submit(jean, phone.getId(), req).outcome()).isEqualTo(MobileSaleService.Outcome.ALREADY_CONFLICT);
        assertThat(conflicts).hasSize(1);
    }

    @Test
    void anInvoiceNumberMustBeThisPhonesAndUnused() {
        MobileSaleService.SaleRequest otherPhones = new MobileSaleService.SaleRequest(UUID.randomUUID(), "MINV-WH-2026-000099", trip.getId(),
                null, null, null, MADE, List.of(line(units.get(0), "27000", "25920")), cash("25920", null), new BigDecimal("25920"), null);
        assertThat(service.submit(jean, phone.getId(), otherPhones).conflict().getReason()).isEqualTo(SyncConflictReason.NUMBER_NOT_ISSUED);
        assertThat(service.submit(jean, phone.getId(), sale(7, cash("25920", null), units.get(0))).conflict().getReason())
                .isEqualTo(SyncConflictReason.NUMBER_NOT_ISSUED);                       // never issued

        service.submit(jean, phone.getId(), sale(1, cash("25920", null), units.get(0)));
        MobileSaleService.SaleRequest reused = new MobileSaleService.SaleRequest(UUID.randomUUID(), "MINV-WH-2026-000001", trip.getId(),
                null, null, null, MADE, List.of(line(units.get(1), "27000", "25920")), cash("25920", null), new BigDecimal("25920"), null);
        assertThat(service.submit(jean, phone.getId(), reused).conflict().getReason()).isEqualTo(SyncConflictReason.NUMBER_USED);
    }

    @Test
    void aLowerPriceStaysWithinTheDriversLimitAndKeepsItsReason() {
        MobileSaleService.SaleRequest tooLow = new MobileSaleService.SaleRequest(UUID.randomUUID(), "MINV-WH-2026-000001", trip.getId(),
                null, null, null, MADE, List.of(new MobileSaleService.LineRequest(units.get(0).getId(), new BigDecimal("24300"), "Regular buyer",
                new BigDecimal("23328"))), cash("23328", null), new BigDecimal("23328"), null);
        assertThat(service.submit(jean, phone.getId(), tooLow).conflict().getReason()).isEqualTo(SyncConflictReason.PRICE_BELOW_LIMIT);

        limit = new BigDecimal("10");
        MobileSaleService.SaleRequest within = new MobileSaleService.SaleRequest(UUID.randomUUID(), "MINV-WH-2026-000002", trip.getId(),
                null, null, null, MADE, List.of(new MobileSaleService.LineRequest(units.get(1).getId(), new BigDecimal("24300"), "Regular buyer",
                new BigDecimal("23328"))), cash("23328", null), new BigDecimal("23328"), null);
        MobileSaleService.Result r = service.submit(jean, phone.getId(), within);
        assertThat(r.outcome()).isEqualTo(MobileSaleService.Outcome.ACCEPTED);
        SalesInvoiceLine line = r.invoice().getLines().get(0);
        assertThat(line.getPricePerM2()).isEqualByComparingTo("24300");
        assertThat(line.getListPrice()).isEqualByComparingTo("27000");
        assertThat(line.getPriceReason()).isEqualTo("Regular buyer");
    }

    @Test
    void amountsAndPaymentsMustBeThoseOfTheTripsPrices() {
        MobileSaleService.SaleRequest wrongAmount = new MobileSaleService.SaleRequest(UUID.randomUUID(), "MINV-WH-2026-000001", trip.getId(),
                null, null, null, MADE, List.of(line(units.get(0), "27000", "25000")), cash("25000", null), new BigDecimal("25000"), null);
        MobileSaleService.Result r = service.submit(jean, phone.getId(), wrongAmount);
        assertThat(r.conflict().getReason()).isEqualTo(SyncConflictReason.TOTAL_MISMATCH);
        assertThat(r.conflict().getDetail()).contains("25920");

        MobileSaleService.SaleRequest shortPaid = new MobileSaleService.SaleRequest(UUID.randomUUID(), "MINV-WH-2026-000002", trip.getId(),
                null, null, null, MADE, List.of(line(units.get(1), "27000", "25920")), cash("20000", null), new BigDecimal("25920"), null);
        assertThat(service.submit(jean, phone.getId(), shortPaid).conflict().getReason()).isEqualTo(SyncConflictReason.PAYMENT_MISMATCH);

        MobileSaleService.SaleRequest noReference = new MobileSaleService.SaleRequest(UUID.randomUUID(), "MINV-WH-2026-000003", trip.getId(),
                null, null, null, MADE, List.of(line(units.get(2), "27000", "25920")), momo("25920", null), new BigDecimal("25920"), null);
        assertThat(service.submit(jean, phone.getId(), noReference).conflict().getReason()).isEqualTo(SyncConflictReason.PAYMENT_MISMATCH);
        assertThat(invoices).isEmpty();
    }

    @Test
    void aSaleWithoutATripOnTheRoadIsKeptToo() {
        when(tripRepo.findFirstByDriver_IdAndStatus(driver.getId(), TripStatus.DEPARTED)).thenReturn(Optional.empty());
        MobileSaleService.Result r = service.submit(jean, phone.getId(), sale(1, cash("25920", null), units.get(0)));
        assertThat(r.conflict().getReason()).isEqualTo(SyncConflictReason.NO_TRIP);
        assertThat(r.conflict().getTrip()).isNull();
        assertThatThrownBy(() -> service.submit(jean, phone.getId(), new MobileSaleService.SaleRequest(null, null, null, null, null, null,
                null, null, null, null, null))).isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "mobile.sale.noClientId");
    }

    // ---------------------------------------------------------------- helpers

    private MobileSaleService.SaleRequest sale(int number, List<MobileSaleService.PaymentRequest> payments, StockUnit... sold) {
        List<MobileSaleService.LineRequest> lines = Arrays.stream(sold).map(u -> line(u, "27000", "25920")).toList();
        BigDecimal total = new BigDecimal("25920").multiply(BigDecimal.valueOf(sold.length));
        BigDecimal tendered = payments.stream().filter(p -> p.method() == PaymentMethod.CASH && p.reference() != null)
                .map(p -> new BigDecimal(p.reference())).findFirst().orElse(null);
        List<MobileSaleService.PaymentRequest> clean = payments.stream()
                .map(p -> p.method() == PaymentMethod.CASH ? new MobileSaleService.PaymentRequest(PaymentMethod.CASH, p.amount(), null) : p).toList();
        return new MobileSaleService.SaleRequest(UUID.randomUUID(), String.format("MINV-WH-2026-%06d", number), trip.getId(), null, null,
                null, MADE, lines, clean, total, tendered);
    }

    private static MobileSaleService.LineRequest line(StockUnit unit, String price, String amount) {
        return new MobileSaleService.LineRequest(unit.getId(), new BigDecimal(price), null, new BigDecimal(amount));
    }

    /** Cash, with what was handed over carried in the reference for the helper (dropped before sending). */
    private static List<MobileSaleService.PaymentRequest> cash(String amount, String tendered) {
        return List.of(new MobileSaleService.PaymentRequest(PaymentMethod.CASH, new BigDecimal(amount), tendered));
    }

    private static List<MobileSaleService.PaymentRequest> momo(String amount, String reference) {
        return List.of(new MobileSaleService.PaymentRequest(PaymentMethod.MOBILE_MONEY, new BigDecimal(amount), reference));
    }

    private static MobileSaleService.PaymentRequest pay(PaymentMethod method, String amount, String reference) {
        return new MobileSaleService.PaymentRequest(method, new BigDecimal(amount), reference);
    }

    private ApiDevice device(String name) {
        ApiDevice d = new ApiDevice();
        d.setId(UUID.randomUUID());
        d.setName(name);
        d.setUsername("driver-m7");
        d.setDeviceKey(UUID.randomUUID().toString());
        return d;
    }

    private static Location location(String code) {
        Location l = new Location();
        l.setId(UUID.randomUUID());
        l.setCode(code);
        l.setType(code.startsWith("VEH") ? LocationType.VEHICLE : LocationType.RACK);
        l.setEnabled(true);
        return l;
    }

    private static Product product() {
        TaxCategory standard = new TaxCategory();
        standard.setId(UUID.randomUUID());
        standard.setEbmCode("B");
        standard.setRate(new BigDecimal("18.00"));
        Product p = new Product();
        p.setId(UUID.randomUUID());
        p.setCode("CLR-6");
        p.setGlassType(GlassType.CLEAR);
        p.setThicknessMm(new BigDecimal("6.00"));
        p.setTaxCategory(standard);
        p.setEnabled(true);
        return p;
    }
}
