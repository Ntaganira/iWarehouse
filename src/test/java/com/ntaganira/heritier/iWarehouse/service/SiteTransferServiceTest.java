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
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Transfers between sites (IMP 11, TRF-007): asked by one person and approved by another, dispatched with units of the
 * sending site within what was asked, received label by label onto the receiving site's racks, finished short with a
 * note, and reported once when still in transit after the Settings' hours.
 */
class SiteTransferServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-15T08:00:00Z"), ZoneId.of("Africa/Kigali"));
    private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);

    private final Location kigali = location("WH", LocationType.SITE, null);
    private final Location kigaliRack = location("WH-A-R01", LocationType.RACK, kigali.getId());
    private final Location musanze = location("MSZ", LocationType.SITE, null);
    private final Location musanzeRack = location("MSZ-A-R01", LocationType.RACK, musanze.getId());
    private final Location musanzeOffcut = location("MSZ-A-OC", LocationType.RACK, musanze.getId());
    private final Product clear6 = product("CLR-6");
    private final Product clear8 = product("CLR-8");
    private final Map<UUID, Location> byId = new HashMap<>();
    private final List<SiteTransferLine> lines = new ArrayList<>();
    private final List<SiteTransferUnit> links = new ArrayList<>();
    private SiteTransferRepository repo;
    private StockService stock;
    private SettingService settings;
    private Notifier notifier;
    private AlertStateRepository alerts;
    private SiteTransferService service;

    @BeforeEach
    void setUp() {
        musanzeOffcut.setOffcut(true);
        for (Location l : List.of(kigali, kigaliRack, musanze, musanzeRack, musanzeOffcut)) {
            byId.put(l.getId(), l);
        }
        repo = mock(SiteTransferRepository.class);
        when(repo.save(any())).thenAnswer(a -> {
            SiteTransfer t = a.getArgument(0);
            t.setId(UUID.randomUUID());
            when(repo.lockById(t.getId())).thenReturn(Optional.of(t));
            when(repo.findDetailedById(t.getId())).thenReturn(Optional.of(t));
            return t;
        });
        SiteTransferLineRepository lineRepo = mock(SiteTransferLineRepository.class);
        when(lineRepo.save(any())).thenAnswer(a -> {
            lines.add(a.getArgument(0));
            return a.getArgument(0);
        });
        when(lineRepo.findByTransferIdOrderByLineNo(any())).thenAnswer(a -> lines);
        SiteTransferUnitRepository linkRepo = mock(SiteTransferUnitRepository.class);
        when(linkRepo.save(any())).thenAnswer(a -> {
            links.add(a.getArgument(0));
            return a.getArgument(0);
        });
        when(linkRepo.findByTransferIdOrderByUnitCode(any())).thenAnswer(a -> links);
        when(linkRepo.findByTransferIdAndStockUnitId(any(), any())).thenAnswer(a ->
                links.stream().filter(l -> l.getStockUnitId().equals(a.getArgument(1))).findFirst());
        ProductRepository productRepo = mock(ProductRepository.class);
        when(productRepo.findById(clear6.getId())).thenReturn(Optional.of(clear6));
        when(productRepo.findById(clear8.getId())).thenReturn(Optional.of(clear8));
        stock = mock(StockService.class);
        when(stock.locationsById()).thenReturn(byId);
        when(stock.storagePlaces(any())).thenReturn(List.of(kigaliRack, musanzeRack, musanzeOffcut));
        when(stock.rackLoads(any())).thenReturn(new HashMap<>());
        DocumentNumberService numbers = mock(DocumentNumberService.class);
        when(numbers.next(DocumentType.SITE_TRANSFER)).thenReturn("STR-WH-2026-000001");
        settings = mock(SettingService.class);
        when(settings.getBoolean(SettingKey.TRANSFER_APPROVAL)).thenReturn(true);
        when(settings.getInt(SettingKey.TRANSFER_DIFFERENCE_HOURS)).thenReturn(24);
        notifier = mock(Notifier.class);
        alerts = mock(AlertStateRepository.class);
        when(alerts.findById(any())).thenReturn(Optional.empty());
        service = new SiteTransferService(repo, lineRepo, linkRepo, mock(StockUnitRepository.class), productRepo, stock, numbers,
                settings, alerts, notifier, CLOCK);
        signIn(3L, "store2");
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- asking and deciding

    @Test
    void theAskingSiteAsksAnotherSiteAndApproversAreTold() {
        SiteTransfer t = ask(line(clear6, 3210, 2250, 2));
        assertThat(t.getStatus()).isEqualTo(SiteTransferStatus.REQUESTED);
        assertThat(t.getRequestedBy()).isEqualTo("store2");
        assertThat(lines).singleElement().satisfies(l -> {
            assertThat(l.getTransferId()).isEqualTo(t.getId());
            assertThat(l.getQuantity()).isEqualTo(2);
        });
        verify(notifier).holders("APPROVE_SITE_TRANSFER", 3L, NotificationKind.TRANSFER, "notify.siteTransfer.waiting",
                "notify.siteTransfer.waitingText", "/site-transfers/" + t.getId(), "STR-WH-2026-000001", "WH", "MSZ");
    }

    @Test
    void withoutApprovalItGoesStraightToTheSendingSite() {
        when(settings.getBoolean(SettingKey.TRANSFER_APPROVAL)).thenReturn(false);
        SiteTransfer t = ask(line(clear6, null, null, 2));
        assertThat(t.getStatus()).isEqualTo(SiteTransferStatus.APPROVED);
        verify(notifier).holders("DISPATCH_SITE_TRANSFER", 3L, NotificationKind.TRANSFER, "notify.siteTransfer.toDispatch",
                "notify.siteTransfer.waitingText", "/site-transfers/" + t.getId(), "STR-WH-2026-000001", "WH", "MSZ");
    }

    @Test
    void twoDifferentSitesASizeGivenWholeOrNotAndADayNotPast() {
        assertKey(() -> service.request(new SiteTransferService.Form(musanze.getId(), musanze.getId(), "stock", LocalDate.now(CLOCK),
                List.of(line(clear6, null, null, 1)))), "siteTransfer.sameSite");
        assertKey(() -> service.request(new SiteTransferService.Form(kigali.getId(), musanze.getId(), "stock", LocalDate.now(CLOCK),
                List.of(line(clear6, 3210, null, 1)))), "siteTransfer.line.size");
        assertKey(() -> service.request(new SiteTransferService.Form(kigali.getId(), musanze.getId(), "stock", LocalDate.now(CLOCK).minusDays(1),
                List.of(line(clear6, null, null, 1)))), "siteTransfer.dispatchDate.invalid");
        assertKey(() -> service.request(new SiteTransferService.Form(kigali.getId(), kigaliRack.getId(), "stock", LocalDate.now(CLOCK),
                List.of(line(clear6, null, null, 1)))), "siteTransfer.site.invalid");
    }

    @Test
    void whoAskedNeitherApprovesNorRejectsIt() {
        SiteTransfer t = ask(line(clear6, null, null, 2));
        assertKey(() -> service.approve(t.getId(), null), "siteTransfer.ownApproval");
        assertKey(() -> service.reject(t.getId(), "no"), "siteTransfer.ownReject");
        signIn(9L, "coo");
        service.approve(t.getId(), "ok");
        assertThat(t.getStatus()).isEqualTo(SiteTransferStatus.APPROVED);
        assertThat(t.getDecidedBy()).isEqualTo("coo");
        assertKey(() -> service.approve(t.getId(), null), "siteTransfer.notRequested");
    }

    // ---------------------------------------------------------------- dispatching

    @Test
    void dispatchTakesAvailableUnitsOfTheSendingSiteWithinWhatWasAsked() {
        SiteTransfer t = approved(line(clear6, 3210, 2250, 2));
        StockUnit a = unit("U-WH-000001", clear6, 3210, 2250, kigaliRack);
        StockUnit b = unit("U-WH-000002", clear6, 2250, 3210, kigaliRack);       // either way round
        SiteTransfer sent = service.dispatch(t.getId(), new SiteTransferService.Dispatch(List.of("u-wh-000001", "U-WH-000002"), "rad 123 a",
                "Jean Bosco", NOW.plusHours(6)));
        assertThat(sent.getStatus()).isEqualTo(SiteTransferStatus.IN_TRANSIT);
        assertThat(sent.getVehiclePlate()).isEqualTo("RAD 123 A");
        assertThat(links).extracting(SiteTransferUnit::getUnitCode).containsExactly("U-WH-000001", "U-WH-000002");
        assertThat(links).allSatisfy(l -> assertThat(l.getFromLocationId()).isEqualTo(kigaliRack.getId()));
        verify(stock).requireNotHeld(List.of(a, b), null);
        verify(stock).dispatch(a, t.getId(), "STR-WH-2026-000001");
        verify(stock).dispatch(b, t.getId(), "STR-WH-2026-000001");
    }

    @Test
    void dispatchRefusesUnitsOfAnotherSiteOrGlassOrMoreThanAsked() {
        SiteTransfer t = approved(line(clear6, 3210, 2250, 1));
        unit("U-WH-000001", clear6, 3210, 2250, kigaliRack);
        unit("U-WH-000002", clear6, 3210, 2250, kigaliRack);
        unit("U-WH-000003", clear6, 3210, 2250, musanzeRack);
        unit("U-WH-000004", clear8, 3210, 2250, kigaliRack);
        unit("U-WH-000005", clear6, 1200, 800, kigaliRack);
        assertKey(() -> dispatch(t, "U-WH-000003"), "siteTransfer.unit.otherSite");
        assertKey(() -> dispatch(t, "U-WH-000004"), "siteTransfer.unit.notAsked");
        assertKey(() -> dispatch(t, "U-WH-000005"), "siteTransfer.unit.notAsked");
        assertKey(() -> dispatch(t, "U-WH-000001", "U-WH-000002"), "siteTransfer.unit.notAsked");
        assertKey(() -> service.dispatch(t.getId(), new SiteTransferService.Dispatch(List.of("U-WH-000001"), "RAD 123 A", "Jean",
                NOW.minusMinutes(1))), "siteTransfer.arrival.invalid");
        verify(stock, never()).dispatch(any(), any(), any());
        assertThat(t.getStatus()).isEqualTo(SiteTransferStatus.APPROVED);
    }

    // ---------------------------------------------------------------- receiving

    @Test
    void eachLabelGoesOnARackOfTheReceivingSiteAndTheLastOneReceivesIt() {
        SiteTransfer t = inTransit("U-WH-000001", "U-WH-000002");
        assertKey(() -> service.receive(t.getId(), "U-WH-000001", kigaliRack.getId()), "siteTransfer.place.invalid");
        assertKey(() -> service.receive(t.getId(), "U-WH-000001", musanzeOffcut.getId()), "trip.return.sheetToOffcut");
        service.receive(t.getId(), "u-wh-000001", musanzeRack.getId());
        assertThat(t.getStatus()).isEqualTo(SiteTransferStatus.IN_TRANSIT);
        assertKey(() -> service.receive(t.getId(), "U-WH-000001", musanzeRack.getId()), "siteTransfer.unit.received");
        service.receive(t.getId(), "U-WH-000002", musanzeRack.getId());
        assertThat(t.getStatus()).isEqualTo(SiteTransferStatus.RECEIVED);
        assertThat(t.getReceivedBy()).isEqualTo("store2");
        assertThat(links).allSatisfy(l -> assertThat(l.getToLocationId()).isEqualTo(musanzeRack.getId()));
        verify(stock, times(2)).arrive(any(), eq(musanzeRack), eq(t.getId()), eq("STR-WH-2026-000001"));
    }

    @Test
    void aLabelNotOnTheTransferIsRefused() {
        SiteTransfer t = inTransit("U-WH-000001");
        unit("U-WH-000009", clear6, 3210, 2250, kigaliRack);
        assertKey(() -> service.receive(t.getId(), "U-WH-000009", musanzeRack.getId()), "siteTransfer.unit.notOnTransfer");
    }

    @Test
    void finishingShortNeedsANoteAndTellsTheController() {
        SiteTransfer t = inTransit("U-WH-000001", "U-WH-000002");
        service.receive(t.getId(), "U-WH-000001", musanzeRack.getId());
        assertKey(() -> service.finishShort(t.getId(), " "), "siteTransfer.difference.required");
        service.finishShort(t.getId(), "One sheet left at the depot");
        assertThat(t.getStatus()).isEqualTo(SiteTransferStatus.RECEIVED_SHORT);
        assertThat(t.getDifferenceNote()).isEqualTo("One sheet left at the depot");
        verify(notifier).holders("ALERT_SITE_TRANSFER", null, NotificationKind.TRANSFER, "notify.siteTransfer.short",
                "notify.siteTransfer.shortText", "/site-transfers/" + t.getId(), "STR-WH-2026-000001", "1", "One sheet left at the depot");

        // The missing sheet turns up: received late, the note kept
        service.receive(t.getId(), "U-WH-000002", musanzeRack.getId());
        assertThat(t.getStatus()).isEqualTo(SiteTransferStatus.RECEIVED);
        assertThat(t.getDifferenceNote()).isEqualTo("One sheet left at the depot");
    }

    @Test
    void aTransferStillInTransitAfterTheHoursIsReportedOnce() {
        SiteTransfer late = inTransit("U-WH-000001");
        late.setExpectedArrival(NOW.minusHours(30));
        SiteTransfer onTime = new SiteTransfer();
        onTime.setId(UUID.randomUUID());
        onTime.setExpectedArrival(NOW.minusHours(2));
        when(repo.findByStatusAndDispatchedAtBefore(SiteTransferStatus.IN_TRANSIT, NOW)).thenReturn(List.of(late, onTime));
        when(alerts.findByKeyStartingWithAndClearedAtIsNull(SiteTransferService.ALERT_PREFIX)).thenReturn(List.of());
        assertThat(service.checkOverdue()).isEqualTo(1);
        verify(alerts).save(argThat(s -> s.getKey().equals(SiteTransferService.ALERT_PREFIX + late.getId())));

        AlertState raised = new AlertState();
        raised.setKey(SiteTransferService.ALERT_PREFIX + late.getId());
        when(alerts.findByKeyStartingWithAndClearedAtIsNull(SiteTransferService.ALERT_PREFIX)).thenReturn(List.of(raised));
        assertThat(service.checkOverdue()).isZero();
        assertThat(raised.getClearedAt()).isNull();
    }

    // ---------------------------------------------------------------- helpers

    private SiteTransfer ask(SiteTransferService.Line... asked) {
        return service.request(new SiteTransferService.Form(kigali.getId(), musanze.getId(), "Stock for the week", LocalDate.now(CLOCK),
                List.of(asked)));
    }

    private SiteTransfer approved(SiteTransferService.Line... asked) {
        SiteTransfer t = ask(asked);
        t.setStatus(SiteTransferStatus.APPROVED);
        return t;
    }

    private SiteTransfer inTransit(String... codes) {
        SiteTransfer t = approved(line(clear6, null, null, codes.length));
        for (String code : codes) {
            unit(code, clear6, 3210, 2250, kigaliRack);
        }
        dispatch(t, codes);
        return t;
    }

    private SiteTransfer dispatch(SiteTransfer t, String... codes) {
        return service.dispatch(t.getId(), new SiteTransferService.Dispatch(List.of(codes), "RAD 123 A", "Jean Bosco", NOW.plusHours(6)));
    }

    private StockUnit unit(String code, Product product, int w, int h, Location place) {
        StockUnit u = new StockUnit();
        u.setId(UUID.randomUUID());
        u.setCode(code);
        u.setProduct(product);
        u.setKind(UnitKind.SHEET);
        u.setWidthMm(w);
        u.setHeightMm(h);
        u.setAreaM2(Pricing.areaM2(w, h));
        u.setWeightKg(new BigDecimal("108.34"));
        u.setStatus(StockStatus.AVAILABLE);
        u.setLocation(place);
        when(stock.findByCode(code)).thenReturn(Optional.of(u));
        return u;
    }

    private static SiteTransferService.Line line(Product product, Integer w, Integer h, int quantity) {
        return new SiteTransferService.Line(product.getId(), w, h, quantity);
    }

    private static Location location(String code, LocationType type, UUID parentId) {
        Location l = new Location();
        l.setId(UUID.randomUUID());
        l.setCode(code);
        l.setName(code);
        l.setType(type);
        l.setParentId(parentId);
        return l;
    }

    private static Product product(String code) {
        Product p = new Product();
        p.setId(UUID.randomUUID());
        p.setCode(code);
        p.setEnabled(true);
        return p;
    }

    private static void assertKey(Runnable call, String key) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo(key));
    }

    private static void signIn(long id, String username) {
        AppUserPrincipal principal = new AppUserPrincipal(id, username, username, "x", true, true, List.of());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
