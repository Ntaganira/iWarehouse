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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The delivery note and goods release authority (F-04, DN-005, IMP 8 and 9; decision D3): prepared with what the paid
 * invoice still has to release, a short delivery explained, released by another person once the EBM receipt is signed
 * or with a logged override, the signed copy recorded, overdue copies reported once, delivery accuracy.
 */
class DeliveryNoteServiceTest {

    // A Thursday morning in Kigali
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-15T08:00:00Z"), ZoneId.of("Africa/Kigali"));
    private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);

    private final Product clear6 = new Product();
    private final List<DeliveryNoteLine> lines = new ArrayList<>();
    private final List<DeliveryNote> notes = new ArrayList<>();
    private DeliveryNoteRepository repo;
    private SalesService sales;
    private EbmService ebm;
    private SettingService settings;
    private UUID siteId;
    private Notifier notifier;
    private AlertStateRepository alerts;
    private DeliveryNoteService service;
    private SalesInvoice invoice;
    private StockUnit sheet;
    private StockUnit piece;

    @BeforeEach
    void setUp() {
        clear6.setId(UUID.randomUUID());
        clear6.setCode("CLR-6");
        repo = mock(DeliveryNoteRepository.class);
        when(repo.save(any())).thenAnswer(a -> {
            DeliveryNote n = a.getArgument(0);
            n.setId(UUID.randomUUID());
            notes.add(n);
            when(repo.lockById(n.getId())).thenReturn(Optional.of(n));
            when(repo.findDetailedById(n.getId())).thenReturn(Optional.of(n));
            return n;
        });
        DeliveryNoteLineRepository lineRepo = mock(DeliveryNoteLineRepository.class);
        when(lineRepo.save(any())).thenAnswer(a -> {
            lines.add(a.getArgument(0));
            return a.getArgument(0);
        });
        when(lineRepo.findByDeliveryNoteIdOrderByLineNo(any())).thenAnswer(a -> lines.stream()
                .filter(l -> l.getDeliveryNoteId().equals(a.getArgument(0))).toList());
        when(lineRepo.ofInvoice(any(), any())).thenAnswer(a -> {
            Collection<DeliveryNoteStatus> statuses = a.getArgument(1);
            return lines.stream().filter(l -> notes.stream().anyMatch(n -> n.getId().equals(l.getDeliveryNoteId())
                    && statuses.contains(n.getStatus()))).toList();
        });

        Location site = location("WH", LocationType.SITE, null);
        Location rack = location("WH-A-R01", LocationType.RACK, site.getId());
        sheet = unit("U-WH-000038", 3210, 2250, null, StockStatus.SOLD);                 // sold at payment: no place any more
        piece = unit("U-WH-000090", 600, 400, rack, StockStatus.RESERVED);
        invoice = new SalesInvoice();
        invoice.setId(UUID.randomUUID());
        invoice.setNumber("INV-WH-2026-000031");
        invoice.setStatus(SalesInvoiceStatus.POSTED);
        invoice.setBalanceDue(BigDecimal.ZERO);
        invoice.setTotalAmount(new BigDecimal("412500"));
        Customer customer = new Customer();
        customer.setId(UUID.randomUUID());
        customer.setName("Umucyo Builders Ltd");
        invoice.setCustomer(customer);
        SalesInvoiceLine sheetLine = line(SaleLineKind.STOCK_UNIT, sheet);
        SalesInvoiceLine sizeLine = line(SaleLineKind.CUSTOM_PIECE, null);
        invoice.getLines().addAll(List.of(sheetLine, sizeLine));

        SalesInvoiceRepository invoiceRepo = mock(SalesInvoiceRepository.class);
        when(invoiceRepo.lockById(invoice.getId())).thenReturn(Optional.of(invoice));
        StockUnitRepository unitRepo = mock(StockUnitRepository.class);
        when(unitRepo.findByIdIn(any())).thenReturn(List.of(sheet));
        sales = mock(SalesService.class);
        when(sales.findDetailed(invoice.getId())).thenReturn(invoice);
        when(sales.readyPieces(invoice)).thenReturn(List.of(piece));
        when(sales.piecesOf(invoice)).thenReturn(Map.of(piece.getId(), sizeLine.getId()));
        StockService stock = mock(StockService.class);
        when(stock.locationsById()).thenReturn(Map.of(site.getId(), site, rack.getId(), rack));
        when(stock.soldFrom(any())).thenReturn(Map.of(sheet.getId(), rack.getId()));           // the rack it left from
        ebm = mock(EbmService.class);
        when(ebm.ofInvoice(invoice.getId())).thenReturn(Optional.empty());
        DocumentNumberService numbers = mock(DocumentNumberService.class);
        when(numbers.next(DocumentType.DELIVERY_NOTE)).thenReturn("DN-WH-2026-000001", "DN-WH-2026-000002");
        settings = mock(SettingService.class);
        when(settings.getBoolean(SettingKey.EBM_BEFORE_RELEASE)).thenReturn(true);
        when(settings.getBoolean(SettingKey.SALES_RELEASE_BY_NOTE)).thenReturn(true);
        when(settings.getInt(SettingKey.DELIVERY_SIGNED_COPY_DAYS)).thenReturn(3);
        siteId = site.getId();
        notifier = mock(Notifier.class);
        alerts = mock(AlertStateRepository.class);
        when(alerts.findById(any())).thenReturn(Optional.empty());
        service = new DeliveryNoteService(repo, lineRepo, invoiceRepo, unitRepo, sales, stock, ebm, numbers, settings, alerts, notifier, CLOCK);
        signIn(4L, "dispatch1");
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- preparing

    @Test
    void dispatchPreparesTheSheetsAndPiecesStillToReleaseAndTheReleasersAreTold() {
        DeliveryNote n = prepareAll();
        assertThat(n.getStatus()).isEqualTo(DeliveryNoteStatus.DRAFT);
        assertThat(n.getVehiclePlate()).isEqualTo("RAD 123 A");
        assertThat(n.getShortReason()).isNull();
        assertThat(lines).extracting(DeliveryNoteLine::getKind).containsExactlyInAnyOrder(DeliveryLineKind.SHEET, DeliveryLineKind.PIECE);
        assertThat(n.getSiteId()).isEqualTo(siteId);                                             // the store the sheet left from
        verify(notifier).holders("RELEASE_GOODS", 4L, NotificationKind.DELIVERY, "notify.delivery.toRelease", "notify.delivery.toReleaseText",
                "/delivery-notes/" + n.getId(), "DN-WH-2026-000001", "INV-WH-2026-000031", "2");
        // Units on a note are not the invoice's to release again
        assertThat(service.releasable(invoice).isEmpty()).isTrue();
    }

    @Test
    void sheetsSoldBeforeReleaseByNoteLeftAtTheCounter() {
        invoice.setPostedAt(LocalDateTime.of(2026, 10, 1, 9, 0));
        when(settings.changedAt(SettingKey.SALES_RELEASE_BY_NOTE)).thenReturn(Optional.of(LocalDateTime.of(2026, 10, 15, 8, 0)));
        DeliveryNoteService.Releasable ready = service.releasable(invoice);
        assertThat(ready.sheets()).isEmpty();
        assertThat(ready.pieces()).containsExactly(piece);
        // Without release by note, the counter hands sheets over at payment
        invoice.setPostedAt(LocalDateTime.of(2026, 10, 16, 9, 0));
        assertThat(service.releasable(invoice).sheets()).containsExactly(sheet);
        when(settings.getBoolean(SettingKey.SALES_RELEASE_BY_NOTE)).thenReturn(false);
        assertThat(service.releasable(invoice).sheets()).isEmpty();
    }

    @Test
    void leavingSomethingReadyBehindNeedsAReasonAndTransportOrCollection() {
        assertKey(() -> service.prepare(invoice.getId(), new DeliveryNoteService.Form(List.of(sheet.getId()), true, null, null, null, null)),
                "delivery.short.reason");
        assertKey(() -> service.prepare(invoice.getId(), new DeliveryNoteService.Form(List.of(sheet.getId(), piece.getId()), false,
                null, "Jean", null, null)), "delivery.vehicle.required");
        assertKey(() -> service.prepare(invoice.getId(), new DeliveryNoteService.Form(List.of(UUID.randomUUID()), true, null, null, null,
                null)), "delivery.units.notReady");
        DeliveryNote n = service.prepare(invoice.getId(), new DeliveryNoteService.Form(List.of(sheet.getId()), true, null, null, null,
                "The piece is collected tomorrow"));
        assertThat(n.isCollected()).isTrue();
        assertThat(n.getShortReason()).isEqualTo("The piece is collected tomorrow");
    }

    @Test
    void anInvoiceWithABalanceDueReleasesNothing() {
        invoice.setBalanceDue(new BigDecimal("6500"));
        assertKey(this::prepareAll, "sale.deliver.balanceDue");
    }

    // ---------------------------------------------------------------- releasing

    @Test
    void anotherPersonReleasesItAndThePiecesAreHandedOver() {
        DeliveryNote n = prepareAll();
        assertKey(() -> service.release(n.getId(), null), "delivery.ownRelease");
        EbmReceipt signed = receipt(EbmReceiptStatus.SIGNED);
        signed.setRcptNo(1042L);
        when(ebm.ofInvoice(invoice.getId())).thenReturn(Optional.of(signed));
        signIn(7L, "manager1");
        service.release(n.getId(), null);
        assertThat(n.getStatus()).isEqualTo(DeliveryNoteStatus.RELEASED);
        assertThat(n.getReleasedBy()).isEqualTo("manager1");
        assertThat(n.getEbmReceipt()).isEqualTo("1042");
        assertThat(n.getEbmOverrideReason()).isNull();
        verify(sales).deliver(invoice.getId(), List.of(piece.getId()), null);
        assertKey(() -> service.release(n.getId(), null), "delivery.notDraft");
    }

    @Test
    void noGoodsBeforeTheEbmSignatureUnlessSomeoneAllowedGivesTheReason() {
        DeliveryNote n = prepareAll();
        when(ebm.ofInvoice(invoice.getId())).thenReturn(Optional.of(receipt(EbmReceiptStatus.QUEUED)));
        signIn(7L, "manager1");
        assertKey(() -> service.release(n.getId(), null), "delivery.ebm.notSigned");
        assertKey(() -> service.release(n.getId(), "VSDC down since 10:00"), "delivery.ebm.noOverride");
        verify(sales, never()).deliver(any(), any(), any());

        signIn(8L, "coo1", "PERM_OVERRIDE_EBM_RELEASE");
        service.release(n.getId(), "VSDC down since 10:00");
        assertThat(n.getStatus()).isEqualTo(DeliveryNoteStatus.RELEASED);
        assertThat(n.getEbmOverrideReason()).isEqualTo("VSDC down since 10:00");
        assertThat(n.getEbmOverrideBy()).isEqualTo("coo1");
    }

    @Test
    void withoutTheRuleTheReleaseDoesNotWaitForTheSignature() {
        when(settings.getBoolean(SettingKey.EBM_BEFORE_RELEASE)).thenReturn(false);
        DeliveryNote n = prepareAll();
        when(ebm.ofInvoice(invoice.getId())).thenReturn(Optional.of(receipt(EbmReceiptStatus.QUEUED)));
        signIn(7L, "manager1");
        service.release(n.getId(), null);
        assertThat(n.getStatus()).isEqualTo(DeliveryNoteStatus.RELEASED);
        assertThat(n.getEbmReceipt()).isNull();
    }

    @Test
    void aShortReleaseTellsTheOrderDesk() {
        DeliveryNote n = service.prepare(invoice.getId(), new DeliveryNoteService.Form(List.of(sheet.getId()), true, null, null, null,
                "Piece collected tomorrow"));
        signIn(7L, "manager1");
        service.release(n.getId(), null);
        verify(sales, never()).deliver(any(), any(), any());
        verify(notifier).holders("CREATE_SALES_ORDER", null, NotificationKind.ORDER, "notify.delivery.short", "notify.delivery.shortText",
                "/delivery-notes/" + n.getId(), "DN-WH-2026-000001", "INV-WH-2026-000031", "Piece collected tomorrow");
    }

    // ---------------------------------------------------------------- the signed copy

    @Test
    void theSignedCopyIsRecordedOnceWithTheCustomersRemarks() {
        DeliveryNote n = released();
        LocalDate today = LocalDate.now(CLOCK);
        assertKey(() -> service.recordCopy(n.getId(), new DeliveryNoteService.Copy(" ", today, null, false)), "delivery.copy.name");
        assertKey(() -> service.recordCopy(n.getId(), new DeliveryNoteService.Copy("Eric", today.plusDays(1), null, false)), "delivery.copy.date");
        assertKey(() -> service.recordCopy(n.getId(), new DeliveryNoteService.Copy("Eric", today, null, true)), "delivery.copy.issueRemarks");
        service.recordCopy(n.getId(), new DeliveryNoteService.Copy("Eric Mugisha", today, "One sheet chipped", true));
        assertThat(n.isCopyBack()).isTrue();
        assertThat(n.isIssue()).isTrue();
        assertThat(n.getReceivedByName()).isEqualTo("Eric Mugisha");
        verify(notifier).holders(eq("ALERT_DELIVERY"), isNull(), eq(NotificationKind.DELIVERY), eq("notify.delivery.issue"),
                eq("notify.delivery.issueText"), eq("/delivery-notes/" + n.getId()), eq("DN-WH-2026-000001"), eq("INV-WH-2026-000031"),
                eq("One sheet chipped"));
        assertKey(() -> service.recordCopy(n.getId(), new DeliveryNoteService.Copy("Eric", today, null, false)), "delivery.copy.done");
    }

    @Test
    void aCopyNotBackAfterThreeWorkingDaysIsReportedOnce() {
        DeliveryNote late = released();
        late.setReleasedAt(LocalDateTime.of(2026, 10, 9, 16, 0));     // Friday: Monday, Tuesday, Wednesday have passed
        DeliveryNote recent = new DeliveryNote();
        recent.setId(UUID.randomUUID());
        recent.setStatus(DeliveryNoteStatus.RELEASED);
        recent.setReleasedAt(LocalDateTime.of(2026, 10, 13, 9, 0));   // Tuesday
        recent.setInvoice(invoice);
        when(repo.findByStatusAndCopyRecordedAtIsNullOrderByReleasedAtAsc(DeliveryNoteStatus.RELEASED)).thenReturn(List.of(late, recent));
        when(alerts.findByKeyStartingWithAndClearedAtIsNull(DeliveryNoteService.ALERT_PREFIX)).thenReturn(List.of());
        assertThat(service.checkCopies()).isEqualTo(1);
        AlertState raised = new AlertState();
        raised.setKey(DeliveryNoteService.ALERT_PREFIX + late.getId());
        when(alerts.findByKeyStartingWithAndClearedAtIsNull(DeliveryNoteService.ALERT_PREFIX)).thenReturn(List.of(raised));
        assertThat(service.checkCopies()).isZero();
    }

    @Test
    void workingDaysSkipTheWeekend() {
        assertThat(DeliveryNoteService.calendarDaysFor(LocalDate.of(2026, 10, 15), 3)).isEqualTo(3);   // Thursday back to Monday
        assertThat(DeliveryNoteService.calendarDaysFor(LocalDate.of(2026, 10, 12), 3)).isEqualTo(5);   // Monday back to Wednesday
    }

    @Test
    void deliveryAccuracyCountsTheSignedCopiesWithoutAProblem() {
        DeliveryNote ok = noteBack(false);
        DeliveryNote bad = noteBack(true);
        DeliveryNote waiting = new DeliveryNote();
        waiting.setStatus(DeliveryNoteStatus.RELEASED);
        waiting.setReleasedAt(NOW.minusHours(2));
        when(repo.findByStatusAndReleasedAtBetween(eq(DeliveryNoteStatus.RELEASED), any(), any())).thenReturn(List.of(ok, bad, waiting));
        DeliveryNoteService.Accuracy a = service.accuracy(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 15));
        assertThat(a.released()).isEqualTo(3);
        assertThat(a.copiesBack()).isEqualTo(2);
        assertThat(a.withIssue()).isEqualTo(1);
        assertThat(a.getPercent()).isEqualByComparingTo("50.0");
    }

    // ---------------------------------------------------------------- helpers

    private DeliveryNote prepareAll() {
        return service.prepare(invoice.getId(), new DeliveryNoteService.Form(List.of(sheet.getId(), piece.getId()), false, "rad 123 a",
                "Jean Bosco", "Kicukiro site", null));
    }

    private DeliveryNote released() {
        DeliveryNote n = prepareAll();
        signIn(7L, "manager1");
        service.release(n.getId(), null);
        signIn(4L, "dispatch1");
        return n;
    }

    private DeliveryNote noteBack(boolean issue) {
        DeliveryNote n = new DeliveryNote();
        n.setStatus(DeliveryNoteStatus.RELEASED);
        n.setReleasedAt(NOW.minusDays(5));
        n.setCopyRecordedAt(NOW.minusDays(1));
        n.setIssue(issue);
        return n;
    }

    private EbmReceipt receipt(EbmReceiptStatus status) {
        EbmReceipt r = new EbmReceipt();
        r.setInvcNo(77);
        r.setStatus(status);
        return r;
    }

    private SalesInvoiceLine line(SaleLineKind kind, StockUnit unit) {
        SalesInvoiceLine l = new SalesInvoiceLine();
        l.setId(UUID.randomUUID());
        l.setInvoice(invoice);
        l.setKind(kind);
        l.setProduct(clear6);
        l.setStockUnitId(unit == null ? null : unit.getId());
        l.setQuantity(1);
        return l;
    }

    private StockUnit unit(String code, int w, int h, Location place, StockStatus status) {
        StockUnit u = new StockUnit();
        u.setId(UUID.randomUUID());
        u.setCode(code);
        u.setProduct(clear6);
        u.setWidthMm(w);
        u.setHeightMm(h);
        u.setLocation(place);
        u.setStatus(status);
        return u;
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

    private static void assertKey(Runnable call, String key) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo(key));
    }

    private static void signIn(long id, String username, String... authorities) {
        AppUserPrincipal principal = new AppUserPrincipal(id, username, username, "x", true, true,
                Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
