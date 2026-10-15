package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.*;
import com.ntaganira.heritier.iWarehouse.enums.*;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.*;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : DeliveryNoteService.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : The delivery note and goods release authority (F-04, DN-005, IMP 8 and 9; decisions D3, D4). Dispatch
 *               prepares the release of a paid invoice with nothing left to pay: its whole sheets (sold at payment, still in
 *               the warehouse) and its cut pieces ready (verified when Settings ask), the vehicle and driver or the customer
 *               collecting; leaving out something ready needs a reason (a short delivery). Another person releases it: with
 *               the Settings' rule the invoice's EBM receipt must be signed first, or the release carries a logged override
 *               (VSDC down) by someone allowed. Releasing hands the pieces over (SalesService.deliver: sold, posted at MAC);
 *               the sheets left at payment. The copy signed by the customer is recorded when it comes back, with their
 *               remarks; copies not back after the Settings' working days are reported once. Delivery accuracy: released
 *               notes with the copy back and no shortage or damage.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class DeliveryNoteService {

    static final String ALERT_PREFIX = "DELIVERY_COPY:";
    static final List<DeliveryNoteStatus> TAKEN = List.of(DeliveryNoteStatus.DRAFT, DeliveryNoteStatus.RELEASED);

    private final DeliveryNoteRepository repo;
    private final DeliveryNoteLineRepository lineRepo;
    private final SalesInvoiceRepository invoiceRepo;
    private final StockUnitRepository unitRepo;
    private final SalesService salesService;
    private final StockService stockService;
    private final EbmService ebmService;
    private final DocumentNumberService numbers;
    private final SettingService settingService;
    private final AlertStateRepository alertRepo;
    private final Notifier notifier;
    private final Clock clock;

    public DeliveryNoteService(DeliveryNoteRepository repo, DeliveryNoteLineRepository lineRepo, SalesInvoiceRepository invoiceRepo,
                               StockUnitRepository unitRepo, SalesService salesService, StockService stockService, EbmService ebmService,
                               DocumentNumberService numbers, SettingService settingService, AlertStateRepository alertRepo,
                               Notifier notifier, Clock clock) {
        this.repo = repo;
        this.lineRepo = lineRepo;
        this.invoiceRepo = invoiceRepo;
        this.unitRepo = unitRepo;
        this.salesService = salesService;
        this.stockService = stockService;
        this.ebmService = ebmService;
        this.numbers = numbers;
        this.settingService = settingService;
        this.alertRepo = alertRepo;
        this.notifier = notifier;
        this.clock = clock;
    }

    /** What is prepared: the units chosen, and the transport or the customer collecting; why less than ready, if so. */
    public record Form(Collection<UUID> unitIds, boolean collected, String vehiclePlate, String driverName, String destination,
                       String shortReason) {
    }

    /** The signed copy: who signed for the customer, when, and what they noted. */
    public record Copy(String receivedByName, LocalDate receivedOn, String remarks, boolean issue) {
    }

    /** What an invoice still has to release: its sheets not on a note, its pieces ready and not on a note. */
    public record Releasable(List<StockUnit> sheets, List<StockUnit> pieces, Map<UUID, UUID> lineOf, Map<UUID, UUID> soldFrom) {

        public boolean isEmpty() {
            return sheets.isEmpty() && pieces.isEmpty();
        }

        /** Where a unit is: its place, or for a sheet sold at payment the place it left from. */
        public UUID placeOf(StockUnit unit) {
            return unit.getLocation() != null ? unit.getLocation().getId() : soldFrom.get(unit.getId());
        }

        public int size() {
            return sheets.size() + pieces.size();
        }
    }

    /** Released notes of a period: how many came back signed, how many of those with a shortage or damage. */
    public record Accuracy(long released, long copiesBack, long withIssue, long overdue) {

        /** % of signed copies with no shortage or damage (IMP 19: at least 98 %), or null with none back. */
        public BigDecimal getPercent() {
            return copiesBack == 0 ? null : BigDecimal.valueOf(copiesBack - withIssue).multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(copiesBack), 1, RoundingMode.HALF_UP);
        }
    }

    // ---------------------------------------------------------------- reading

    public boolean releaseByNote() {
        return settingService.getBoolean(SettingKey.SALES_RELEASE_BY_NOTE);
    }

    public Page<DeliveryNote> findPage(String status, String search, int page, int size) {
        LocalDateTime overdueBefore = overdueBefore();
        Specification<DeliveryNote> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if ("copy".equals(status)) {
                p.add(cb.equal(root.get("status"), DeliveryNoteStatus.RELEASED));
                p.add(cb.isNull(root.get("copyRecordedAt")));
            } else if ("overdue".equals(status)) {
                p.add(cb.equal(root.get("status"), DeliveryNoteStatus.RELEASED));
                p.add(cb.isNull(root.get("copyRecordedAt")));
                p.add(cb.lessThan(root.get("releasedAt"), overdueBefore));
            } else if (StringUtils.hasText(status)) {
                try {
                    p.add(cb.equal(root.get("status"), DeliveryNoteStatus.valueOf(status)));
                } catch (IllegalArgumentException ignored) {
                    // an unknown status filters nothing
                }
            }
            if (StringUtils.hasText(search)) {
                String term = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                p.add(cb.or(cb.like(cb.lower(root.get("number")), term), cb.like(cb.lower(root.get("invoice").get("number")), term),
                        cb.like(cb.lower(root.get("invoice").get("customer").get("name")), term),
                        cb.like(cb.lower(cb.coalesce(root.get("vehiclePlate"), "")), term)));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
        return repo.findAll(spec, PageRequest.of(page, size, Sort.by(Sort.Order.desc("preparedAt"), Sort.Order.desc("number"))));
    }

    public DeliveryNote findDetailed(UUID id) {
        return repo.findDetailedById(id).orElseThrow(() -> new NotFoundException("DeliveryNote", id));
    }

    public List<DeliveryNoteLine> lines(UUID id) {
        return lineRepo.findByDeliveryNoteIdOrderByLineNo(id);
    }

    public List<DeliveryNote> ofInvoice(UUID invoiceId) {
        return repo.findByInvoice_IdOrderByNumberAsc(invoiceId);
    }

    public long waitingRelease() {
        return repo.countByStatus(DeliveryNoteStatus.DRAFT);
    }

    /** Released notes whose signed copy is overdue. */
    public long overdueCopies() {
        LocalDateTime before = overdueBefore();
        return repo.findByStatusAndCopyRecordedAtIsNullOrderByReleasedAtAsc(DeliveryNoteStatus.RELEASED).stream()
                .filter(n -> n.getReleasedAt().isBefore(before)).count();
    }

    /** What an invoice still has to release. */
    public Releasable releasable(SalesInvoice invoice) {
        Set<UUID> onNotes = lineRepo.ofInvoice(invoice.getId(), TAKEN).stream().map(DeliveryNoteLine::getStockUnitId).collect(Collectors.toSet());
        Map<UUID, UUID> lineOf = new HashMap<>();
        List<UUID> sheetIds = new ArrayList<>();
        // Sheets leave on a note only for sales paid since release by note is on: before, they left at the counter
        boolean sheetsByNote = sheetsByNote(invoice);
        for (SalesInvoiceLine line : invoice.getLines()) {
            if (!sheetsByNote) {
                break;
            }
            if (line.isStockUnit() && line.getStockUnitId() != null && !onNotes.contains(line.getStockUnitId())) {
                sheetIds.add(line.getStockUnitId());
                lineOf.put(line.getStockUnitId(), line.getId());
            }
        }
        List<StockUnit> sheets = sheetIds.isEmpty() ? List.of()
                : unitRepo.findByIdIn(sheetIds).stream().sorted(Comparator.comparing(StockUnit::getCode)).toList();
        Map<UUID, UUID> pieceLines = salesService.piecesOf(invoice);
        List<StockUnit> pieces = salesService.readyPieces(invoice).stream().filter(u -> !onNotes.contains(u.getId())).toList();
        pieces.forEach(u -> lineOf.put(u.getId(), pieceLines.get(u.getId())));
        return new Releasable(sheets, pieces, lineOf, stockService.soldFrom(sheetIds));
    }

    /** Whether an invoice's sheets leave on a delivery note: release by note on, and the invoice paid since it was turned on. */
    boolean sheetsByNote(SalesInvoice invoice) {
        if (!settingService.getBoolean(SettingKey.SALES_RELEASE_BY_NOTE)) {
            return false;
        }
        LocalDateTime since = settingService.changedAt(SettingKey.SALES_RELEASE_BY_NOTE).orElse(null);
        return since == null || invoice.getPostedAt() == null || !invoice.getPostedAt().isBefore(since);
    }

    /** The signed EBM receipt of an invoice, the one waiting, or none (issued before the EBM connection). */
    public Optional<EbmReceipt> receiptOf(SalesInvoice invoice) {
        return ebmService.ofInvoice(invoice.getId());
    }

    public boolean ebmBeforeRelease() {
        return settingService.getBoolean(SettingKey.EBM_BEFORE_RELEASE);
    }

    public static boolean isPreparer(DeliveryNote n) {
        return n.getPreparedById() != null && AppUserPrincipal.current().map(u -> u.getId().equals(n.getPreparedById())).orElse(false);
    }

    // ---------------------------------------------------------------- preparing and releasing

    /**
     * Dispatch prepares a release of a paid invoice with nothing left to pay: units of what it still has to release, at
     * least one; leaving out something ready needs the reason; the vehicle and driver unless the customer collects.
     */
    @Transactional
    public DeliveryNote prepare(UUID invoiceId, Form form) {
        invoiceRepo.lockById(invoiceId).orElseThrow(() -> new NotFoundException("SalesInvoice", invoiceId));
        SalesInvoice invoice = salesService.findDetailed(invoiceId);
        if (invoice.getStatus() != SalesInvoiceStatus.POSTED) {
            throw BusinessException.of("sale.deliver.notIssued");
        }
        if (invoice.hasBalanceDue()) {
            throw BusinessException.of("sale.deliver.balanceDue", invoice.getNumber(), invoice.getBalanceDue());
        }
        Releasable ready = releasable(invoice);
        Set<UUID> chosen = form.unitIds() == null ? Set.of() : new LinkedHashSet<>(form.unitIds());
        if (chosen.isEmpty()) {
            throw BusinessException.onField("unitIds", "delivery.units.required");
        }
        Map<UUID, StockUnit> byId = new LinkedHashMap<>();
        ready.sheets().forEach(u -> byId.put(u.getId(), u));
        ready.pieces().forEach(u -> byId.put(u.getId(), u));
        if (!byId.keySet().containsAll(chosen)) {
            throw BusinessException.onField("unitIds", "delivery.units.notReady", invoice.getNumber());
        }
        String shortReason = PartyRules.clean(form.shortReason());
        if (chosen.size() < byId.size() && (shortReason == null || shortReason.length() > 255)) {
            throw BusinessException.onField("shortReason", "delivery.short.reason", byId.size() - chosen.size());
        }
        String plate = PartyRules.clean(form.vehiclePlate());
        String driver = PartyRules.clean(form.driverName());
        if (!form.collected()) {
            if (plate == null || plate.length() > 20) {
                throw BusinessException.onField("vehiclePlate", "delivery.vehicle.required");
            }
            if (driver == null || driver.length() > 100) {
                throw BusinessException.onField("driverName", "delivery.driver.required");
            }
        }
        String destination = PartyRules.clean(form.destination());
        if (destination != null && destination.length() > 150) {
            throw BusinessException.onField("destination", "delivery.destination.size");
        }
        Optional<AppUserPrincipal> user = AppUserPrincipal.current();
        DeliveryNote note = new DeliveryNote();
        note.setNumber(numbers.next(DocumentType.DELIVERY_NOTE));
        note.setInvoice(invoice);
        note.setSiteId(siteOf(ready.placeOf(byId.get(chosen.iterator().next()))));
        note.setCollected(form.collected());
        note.setVehiclePlate(form.collected() ? null : plate.toUpperCase(Locale.ROOT));
        note.setDriverName(form.collected() ? null : driver);
        note.setDestination(destination);
        note.setShortReason(chosen.size() < byId.size() ? shortReason : null);
        note.setPreparedBy(user.map(AppUserPrincipal::getUsername).orElse("system"));
        note.setPreparedById(user.map(AppUserPrincipal::getId).orElse(null));
        note.setPreparedAt(LocalDateTime.now(clock));
        note.setStatus(DeliveryNoteStatus.DRAFT);
        repo.save(note);
        Set<UUID> sheetIds = ready.sheets().stream().map(StockUnit::getId).collect(Collectors.toSet());
        int no = 0;
        for (UUID id : chosen) {
            StockUnit unit = byId.get(id);
            DeliveryNoteLine line = new DeliveryNoteLine();
            line.setDeliveryNoteId(note.getId());
            line.setLineNo(++no);
            line.setInvoiceLineId(ready.lineOf().get(id));
            line.setStockUnitId(id);
            line.setUnitCode(unit.getCode());
            line.setKind(sheetIds.contains(id) ? DeliveryLineKind.SHEET : DeliveryLineKind.PIECE);
            line.setWidthMm(unit.getWidthMm());
            line.setHeightMm(unit.getHeightMm());
            lineRepo.save(line);
        }
        notifier.holders("RELEASE_GOODS", note.getPreparedById(), NotificationKind.DELIVERY, "notify.delivery.toRelease",
                "notify.delivery.toReleaseText", "/delivery-notes/" + note.getId(), note.getNumber(), invoice.getNumber(),
                String.valueOf(chosen.size()));
        return note;
    }

    /**
     * Another person releases it (F-04): with the Settings' rule the invoice's EBM receipt must be signed, or the release
     * names its reason and is made by someone allowed to override (decision D3). Its pieces are handed over (sold, posted
     * at MAC); each unit must still be the invoice's to release. A short delivery tells the Order Desk.
     */
    @Transactional
    public DeliveryNote release(UUID id, String overrideReason) {
        DeliveryNote note = lock(id);
        if (!note.isDraft()) {
            throw BusinessException.of("delivery.notDraft", note.getNumber());
        }
        if (isPreparer(note)) {
            throw BusinessException.of("delivery.ownRelease", note.getNumber());
        }
        SalesInvoice invoice = note.getInvoice();
        Optional<EbmReceipt> receipt = receiptOf(invoice);
        String override = PartyRules.clean(overrideReason);
        if (ebmBeforeRelease() && receipt.isPresent() && !receipt.get().isSigned()) {
            if (override == null || override.length() > 255) {
                throw BusinessException.onField("overrideReason", "delivery.ebm.notSigned", invoice.getNumber(),
                        receipt.get().getStatus().name());
            }
            if (!AppUserPrincipal.currentHas("PERM_OVERRIDE_EBM_RELEASE")) {
                throw BusinessException.onField("overrideReason", "delivery.ebm.noOverride");
            }
            note.setEbmOverrideReason(override);
            note.setEbmOverrideBy(AppUserPrincipal.currentUsername());
        }
        salesService.requireTransfersConfirmed(invoice);
        receipt.filter(EbmReceipt::isSigned).ifPresent(r -> note.setEbmReceipt(r.getRcptNo() != null
                ? String.valueOf(r.getRcptNo()) : String.valueOf(r.getInvcNo())));
        List<DeliveryNoteLine> lines = lines(id);
        // Every unit still the invoice's to release: sheets on no other released note, pieces still ready
        Set<UUID> releasedElsewhere = lineRepo.ofInvoice(invoice.getId(), List.of(DeliveryNoteStatus.RELEASED)).stream()
                .map(DeliveryNoteLine::getStockUnitId).collect(Collectors.toSet());
        SalesInvoice detailed = salesService.findDetailed(invoice.getId());
        Set<UUID> ready = salesService.readyPieces(detailed).stream().map(StockUnit::getId).collect(Collectors.toSet());
        List<UUID> pieces = new ArrayList<>();
        for (DeliveryNoteLine line : lines) {
            if (releasedElsewhere.contains(line.getStockUnitId())) {
                throw BusinessException.of("delivery.unit.released", line.getUnitCode());
            }
            if (line.getKind() == DeliveryLineKind.PIECE) {
                if (!ready.contains(line.getStockUnitId())) {
                    throw BusinessException.of("delivery.unit.notReady", line.getUnitCode());
                }
                pieces.add(line.getStockUnitId());
            }
        }
        if (!pieces.isEmpty()) {
            salesService.deliver(invoice.getId(), pieces, null);
        }
        Optional<AppUserPrincipal> user = AppUserPrincipal.current();
        LocalDateTime now = LocalDateTime.now(clock);
        // Status and the fields its check needs, together, after the queries
        note.setReleasedBy(user.map(AppUserPrincipal::getUsername).orElse("system"));
        note.setReleasedById(user.map(AppUserPrincipal::getId).orElse(null));
        note.setReleasedAt(now);
        note.setStatus(DeliveryNoteStatus.RELEASED);
        if (note.getShortReason() != null) {
            notifier.holders("CREATE_SALES_ORDER", null, NotificationKind.ORDER, "notify.delivery.short", "notify.delivery.shortText",
                    "/delivery-notes/" + note.getId(), note.getNumber(), invoice.getNumber(), note.getShortReason());
        }
        return note;
    }

    /** A note not released yet is cancelled with the reason: its units are the invoice's to release again. */
    @Transactional
    public DeliveryNote cancel(UUID id, String reason) {
        DeliveryNote note = lock(id);
        if (!note.isDraft()) {
            throw BusinessException.of("delivery.notDraft", note.getNumber());
        }
        String why = PartyRules.clean(reason);
        if (why == null || why.length() > 255) {
            throw BusinessException.of("po.reason.required");
        }
        note.setCancelReason(why);
        note.setCancelledBy(AppUserPrincipal.currentUsername());
        note.setCancelledAt(LocalDateTime.now(clock));
        note.setStatus(DeliveryNoteStatus.CANCELLED);
        return note;
    }

    /**
     * The copy signed by the customer is back (IMP 9.2, 9.3): who signed, on which day (from the release to today), what
     * they noted and whether it was a shortage or damage (a remark is then required). Once.
     */
    @Transactional
    public DeliveryNote recordCopy(UUID id, Copy copy) {
        DeliveryNote note = lock(id);
        if (!note.isReleased()) {
            throw BusinessException.of("delivery.notReleased", note.getNumber());
        }
        if (note.isCopyBack()) {
            throw BusinessException.of("delivery.copy.done", note.getNumber());
        }
        String name = PartyRules.clean(copy.receivedByName());
        if (name == null || name.length() > 100) {
            throw BusinessException.onField("receivedByName", "delivery.copy.name");
        }
        LocalDate today = LocalDate.now(clock);
        if (copy.receivedOn() == null || copy.receivedOn().isAfter(today) || copy.receivedOn().isBefore(note.getReleasedAt().toLocalDate())) {
            throw BusinessException.onField("receivedOn", "delivery.copy.date");
        }
        String remarks = PartyRules.clean(copy.remarks());
        if (remarks != null && remarks.length() > 500) {
            throw BusinessException.onField("remarks", "delivery.copy.remarksSize");
        }
        if (copy.issue() && remarks == null) {
            throw BusinessException.onField("remarks", "delivery.copy.issueRemarks");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        note.setReceivedByName(name);
        note.setReceivedOn(copy.receivedOn());
        note.setRemarks(remarks);
        note.setIssue(copy.issue());
        note.setCopyRecordedBy(AppUserPrincipal.currentUsername());
        note.setCopyRecordedAt(now);
        alertRepo.findById(ALERT_PREFIX + note.getId()).filter(s -> s.getClearedAt() == null).ifPresent(s -> s.setClearedAt(now));
        if (copy.issue()) {
            notifier.holders("ALERT_DELIVERY", null, NotificationKind.DELIVERY, "notify.delivery.issue", "notify.delivery.issueText",
                    "/delivery-notes/" + note.getId(), note.getNumber(), note.getInvoice().getNumber(), remarks);
        }
        return note;
    }

    /**
     * Tells ALERT_DELIVERY holders once per note released and its signed copy not back after the Settings' working days
     * (IMP 9.3); how many were raised. Cleared when the copy is recorded.
     */
    @Scheduled(fixedDelayString = "${app.alerts.delivery-every:PT1H}", initialDelayString = "${app.alerts.first-check:PT1M}")
    @Transactional
    public int checkCopies() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime before = overdueBefore();
        Set<String> raised = alertRepo.findByKeyStartingWithAndClearedAtIsNull(ALERT_PREFIX).stream().map(AlertState::getKey)
                .collect(Collectors.toSet());
        int count = 0;
        for (DeliveryNote note : repo.findByStatusAndCopyRecordedAtIsNullOrderByReleasedAtAsc(DeliveryNoteStatus.RELEASED)) {
            String key = ALERT_PREFIX + note.getId();
            if (!note.getReleasedAt().isBefore(before) || raised.contains(key)) {
                continue;
            }
            AlertState state = alertRepo.findById(key).orElseGet(AlertState::new);
            state.setKey(key);
            state.setRaisedAt(now);
            state.setClearedAt(null);
            alertRepo.save(state);
            notifier.holders("ALERT_DELIVERY", null, NotificationKind.DELIVERY, "notify.delivery.copyLate", "notify.delivery.copyLateText",
                    "/delivery-notes/" + note.getId(), note.getNumber(), note.getInvoice().getCustomer().getName(),
                    String.valueOf(settingService.getInt(SettingKey.DELIVERY_SIGNED_COPY_DAYS)));
            count++;
        }
        return count;
    }

    /** Delivery accuracy of the notes released in a period (IMP 19). */
    public Accuracy accuracy(LocalDate from, LocalDate to) {
        List<DeliveryNote> released = repo.findByStatusAndReleasedAtBetween(DeliveryNoteStatus.RELEASED, from.atStartOfDay(),
                to.plusDays(1).atStartOfDay().minusNanos(1));
        LocalDateTime before = overdueBefore();
        long back = released.stream().filter(DeliveryNote::isCopyBack).count();
        long issue = released.stream().filter(n -> n.isCopyBack() && n.isIssue()).count();
        long overdue = released.stream().filter(n -> !n.isCopyBack() && n.getReleasedAt().isBefore(before)).count();
        return new Accuracy(released.size(), back, issue, overdue);
    }

    // ---------------------------------------------------------------- helpers

    /** The moment before which a released note's copy is overdue: the Settings' working days back from now. */
    LocalDateTime overdueBefore() {
        LocalDateTime now = LocalDateTime.now(clock);
        return now.minusDays(calendarDaysFor(now.toLocalDate(), settingService.getInt(SettingKey.DELIVERY_SIGNED_COPY_DAYS)));
    }

    /** Calendar days back from a day that hold that many working days (Monday to Friday). */
    static int calendarDaysFor(LocalDate day, int workingDays) {
        int calendar = 0;
        int working = 0;
        LocalDate d = day;
        while (working < workingDays) {
            d = d.minusDays(1);
            calendar++;
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                working++;
            }
        }
        return calendar;
    }

    private UUID siteOf(UUID placeId) {
        if (placeId == null) {
            return null;
        }
        Map<UUID, Location> places = stockService.locationsById();
        Location place = places.get(placeId);
        return place == null ? null : SiteTransferService.siteOf(place, places);
    }

    private DeliveryNote lock(UUID id) {
        repo.lockById(id).orElseThrow(() -> new NotFoundException("DeliveryNote", id));
        return findDetailed(id);
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }
}
