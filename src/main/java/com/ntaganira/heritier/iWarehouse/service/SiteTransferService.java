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
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : SiteTransferService.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Transfers between sites (IMP 11, TRF-007, TCN-008, PF-04): the receiving site asks for glass (so many
 *               sheets of a glass, of a size if it matters) with the reason and the day it should leave; another person
 *               approves it when Settings ask; the sending site dispatches it by scanning the labels (available units of
 *               that site, held by nothing, within what was asked) with the vehicle, driver and expected arrival, and the
 *               units go in transit (still on hand, no journal: one Inventory account, D12); the receiving site scans each
 *               label on arrival onto one of its racks (its limits, no full sheet on an off-cut rack). When every unit has
 *               arrived it is received; finishing short needs a note and tells the Internal Controller and the Head of
 *               Inventory, as does a transfer still in transit the Settings' hours after its expected arrival. Its Transaction
 *               Tickets are raised from the dispatch and arrival movements (TicketService).
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class SiteTransferService {

    static final String ALERT_PREFIX = "SITE_TRANSFER:";
    static final List<SiteTransferStatus> WAITING = List.of(SiteTransferStatus.REQUESTED);

    private final SiteTransferRepository repo;
    private final SiteTransferLineRepository lineRepo;
    private final SiteTransferUnitRepository unitLinkRepo;
    private final StockUnitRepository unitRepo;
    private final ProductRepository productRepo;
    private final StockService stockService;
    private final DocumentNumberService numbers;
    private final SettingService settingService;
    private final AlertStateRepository alertRepo;
    private final Notifier notifier;
    private final Clock clock;

    public SiteTransferService(SiteTransferRepository repo, SiteTransferLineRepository lineRepo, SiteTransferUnitRepository unitLinkRepo,
                               StockUnitRepository unitRepo, ProductRepository productRepo, StockService stockService,
                               DocumentNumberService numbers, SettingService settingService, AlertStateRepository alertRepo,
                               Notifier notifier, Clock clock) {
        this.repo = repo;
        this.lineRepo = lineRepo;
        this.unitLinkRepo = unitLinkRepo;
        this.unitRepo = unitRepo;
        this.productRepo = productRepo;
        this.stockService = stockService;
        this.numbers = numbers;
        this.settingService = settingService;
        this.alertRepo = alertRepo;
        this.notifier = notifier;
        this.clock = clock;
    }

    /** What is asked: from and to which site, why, when it should leave, and the glass. */
    public record Form(UUID fromSiteId, UUID toSiteId, String reason, LocalDate expectedDispatch, List<Line> lines) {
    }

    /** So many sheets of a glass, of a size when it matters (both sizes or neither). */
    public record Line(UUID productId, Integer widthMm, Integer heightMm, Integer quantity) {
    }

    /** How the dispatch goes: the labels loaded, the vehicle, the driver and the expected arrival. */
    public record Dispatch(List<String> codes, String vehiclePlate, String driverName, LocalDateTime expectedArrival) {
    }

    // ---------------------------------------------------------------- reading

    public Page<SiteTransfer> findPage(String status, String search, int page, int size) {
        Specification<SiteTransfer> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if ("open".equals(status)) {
                p.add(root.get("status").in(SiteTransferStatus.REQUESTED, SiteTransferStatus.APPROVED, SiteTransferStatus.IN_TRANSIT));
            } else if (StringUtils.hasText(status)) {
                try {
                    p.add(cb.equal(root.get("status"), SiteTransferStatus.valueOf(status)));
                } catch (IllegalArgumentException ignored) {
                    // an unknown status filters nothing
                }
            }
            if (StringUtils.hasText(search)) {
                String term = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                p.add(cb.or(cb.like(cb.lower(root.get("number")), term), cb.like(cb.lower(root.get("reason")), term),
                        cb.like(cb.lower(root.get("requestedBy")), term), cb.like(cb.lower(cb.coalesce(root.get("vehiclePlate"), "")), term)));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
        return repo.findAll(spec, PageRequest.of(page, size, Sort.by(Sort.Order.desc("requestedAt"), Sort.Order.desc("number"))));
    }

    public SiteTransfer findDetailed(UUID id) {
        return repo.findDetailedById(id).orElseThrow(() -> new NotFoundException("SiteTransfer", id));
    }

    public List<SiteTransferLine> lines(UUID id) {
        return lineRepo.findByTransferIdOrderByLineNo(id);
    }

    public List<SiteTransferUnit> units(UUID id) {
        return unitLinkRepo.findByTransferIdOrderByUnitCode(id);
    }

    public long waitingCount() {
        return repo.countByStatusIn(List.of(SiteTransferStatus.REQUESTED, SiteTransferStatus.IN_TRANSIT));
    }

    /** Active sites, by code. */
    public List<Location> sites() {
        return stockService.locationsById().values().stream().filter(l -> l.getType() == LocationType.SITE && l.isEnabled())
                .sorted(Comparator.comparing(Location::getCode)).toList();
    }

    /** The racks and slots of a site a unit can be put on. */
    public List<Location> placesOf(UUID siteId) {
        Map<UUID, Location> byId = stockService.locationsById();
        return stockService.storagePlaces(byId).stream().filter(l -> siteId.equals(siteOf(l, byId))).toList();
    }

    /** Every location by id (a page names the places units left from and went to). */
    public Map<UUID, Location> locations() {
        return stockService.locationsById();
    }

    public boolean approvalRequired() {
        return settingService.getBoolean(SettingKey.TRANSFER_APPROVAL);
    }

    public static boolean isRequester(SiteTransfer t) {
        return t.getRequestedById() != null && AppUserPrincipal.current().map(u -> u.getId().equals(t.getRequestedById())).orElse(false);
    }

    // ---------------------------------------------------------------- asking and deciding

    /** The receiving site asks: two different active sites, a reason, a day not past, at least one line. */
    @Transactional
    public SiteTransfer request(Form form) {
        Map<UUID, Location> byId = stockService.locationsById();
        Location from = site(form.fromSiteId(), byId, "fromSiteId");
        Location to = site(form.toSiteId(), byId, "toSiteId");
        if (from.getId().equals(to.getId())) {
            throw BusinessException.onField("toSiteId", "siteTransfer.sameSite");
        }
        String reason = PartyRules.clean(form.reason());
        if (reason == null || reason.length() > 255) {
            throw BusinessException.onField("reason", reason == null ? "siteTransfer.reason.required" : "siteTransfer.reason.size");
        }
        LocalDate today = LocalDate.now(clock);
        if (form.expectedDispatch() == null || form.expectedDispatch().isBefore(today)) {
            throw BusinessException.onField("expectedDispatch", "siteTransfer.dispatchDate.invalid");
        }
        List<SiteTransferLine> lines = new ArrayList<>();
        int no = 0;
        for (Line l : form.lines()) {
            String field = "lines[" + no + "]";
            no++;
            if (l.productId() == null || l.quantity() == null || l.quantity() <= 0) {
                throw BusinessException.onField(field + ".quantity", "siteTransfer.line.invalid");
            }
            if ((l.widthMm() == null) != (l.heightMm() == null)) {
                throw BusinessException.onField(field + ".widthMm", "siteTransfer.line.size");
            }
            Product product = productRepo.findById(l.productId()).filter(Product::isEnabled)
                    .orElseThrow(() -> BusinessException.onField(field + ".productId", "siteTransfer.line.product"));
            SiteTransferLine line = new SiteTransferLine();
            line.setLineNo(lines.size() + 1);
            line.setProduct(product);
            line.setWidthMm(l.widthMm());
            line.setHeightMm(l.heightMm());
            line.setQuantity(l.quantity());
            lines.add(line);
        }
        if (lines.isEmpty()) {
            throw BusinessException.onField("lines", "siteTransfer.lines.required");
        }
        Optional<AppUserPrincipal> user = AppUserPrincipal.current();
        LocalDateTime now = LocalDateTime.now(clock);
        SiteTransfer t = new SiteTransfer();
        t.setNumber(numbers.next(DocumentType.SITE_TRANSFER));
        t.setFromSite(from);
        t.setToSite(to);
        t.setReason(reason);
        t.setExpectedDispatch(form.expectedDispatch());
        t.setRequestedBy(user.map(AppUserPrincipal::getUsername).orElse("system"));
        t.setRequestedById(user.map(AppUserPrincipal::getId).orElse(null));
        t.setRequestedAt(now);
        t.setStatus(approvalRequired() ? SiteTransferStatus.REQUESTED : SiteTransferStatus.APPROVED);
        repo.save(t);
        for (SiteTransferLine line : lines) {
            line.setTransferId(t.getId());
            lineRepo.save(line);
        }
        if (t.getStatus() == SiteTransferStatus.REQUESTED) {
            notifier.holders("APPROVE_SITE_TRANSFER", t.getRequestedById(), NotificationKind.TRANSFER, "notify.siteTransfer.waiting",
                    "notify.siteTransfer.waitingText", "/site-transfers/" + t.getId(), t.getNumber(), from.getName(), to.getName());
        } else {
            tellDispatchers(t);
        }
        return t;
    }

    /** Another person approves it: the sending site may dispatch it. */
    @Transactional
    public SiteTransfer approve(UUID id, String note) {
        SiteTransfer t = lock(id);
        if (t.getStatus() != SiteTransferStatus.REQUESTED) {
            throw BusinessException.of("siteTransfer.notRequested", t.getNumber());
        }
        if (isRequester(t)) {
            throw BusinessException.of("siteTransfer.ownApproval", t.getNumber());
        }
        decide(t, PartyRules.clean(note));
        t.setStatus(SiteTransferStatus.APPROVED);
        notifier.user(t.getRequestedById(), NotificationKind.DECISION, "notify.siteTransfer.approved", "notify.decidedBy",
                "/site-transfers/" + t.getId(), t.getNumber(), t.getDecidedBy());
        tellDispatchers(t);
        return t;
    }

    /** Another person rejects it, with the reason. */
    @Transactional
    public SiteTransfer reject(UUID id, String reason) {
        SiteTransfer t = lock(id);
        if (t.getStatus() != SiteTransferStatus.REQUESTED) {
            throw BusinessException.of("siteTransfer.notRequested", t.getNumber());
        }
        if (isRequester(t)) {
            throw BusinessException.of("siteTransfer.ownReject", t.getNumber());
        }
        decide(t, reasonOf(reason));
        t.setStatus(SiteTransferStatus.REJECTED);
        notifier.user(t.getRequestedById(), NotificationKind.DECISION, "notify.siteTransfer.rejected", "notify.decidedBy",
                "/site-transfers/" + t.getId(), t.getNumber(), t.getDecidedBy());
        return t;
    }

    /** It is called off before it leaves, with the reason. */
    @Transactional
    public SiteTransfer cancel(UUID id, String reason) {
        SiteTransfer t = lock(id);
        if (t.getStatus() != SiteTransferStatus.REQUESTED && t.getStatus() != SiteTransferStatus.APPROVED) {
            throw BusinessException.of("siteTransfer.notCancellable", t.getNumber());
        }
        decide(t, reasonOf(reason));
        t.setStatus(SiteTransferStatus.CANCELLED);
        return t;
    }

    // ---------------------------------------------------------------- dispatching and receiving

    /**
     * The sending site dispatches it: each label an available unit of that site, held by nothing, of a glass (and size)
     * asked, never more than asked per line; the vehicle, driver and expected arrival. The units go in transit.
     */
    @Transactional
    public SiteTransfer dispatch(UUID id, Dispatch d) {
        SiteTransfer t = lock(id);
        if (t.getStatus() != SiteTransferStatus.APPROVED) {
            throw BusinessException.of("siteTransfer.notApproved", t.getNumber());
        }
        String plate = PartyRules.clean(d.vehiclePlate());
        if (plate == null || plate.length() > 20) {
            throw BusinessException.onField("vehiclePlate", "siteTransfer.vehicle.required");
        }
        String driver = PartyRules.clean(d.driverName());
        if (driver == null || driver.length() > 100) {
            throw BusinessException.onField("driverName", "siteTransfer.driver.required");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (d.expectedArrival() == null || !d.expectedArrival().isAfter(now)) {
            throw BusinessException.onField("expectedArrival", "siteTransfer.arrival.invalid");
        }
        List<String> codes = d.codes().stream().map(c -> c.trim().toUpperCase(Locale.ROOT)).filter(c -> !c.isEmpty()).distinct().toList();
        if (codes.isEmpty()) {
            throw BusinessException.onField("codes", "siteTransfer.codes.required");
        }
        Map<UUID, Location> byId = stockService.locationsById();
        List<SiteTransferLine> lines = lines(id);
        Map<Integer, Integer> taken = new HashMap<>();
        List<StockUnit> units = new ArrayList<>();
        for (String code : codes) {
            StockUnit unit = stockService.findByCode(code).orElseThrow(() -> BusinessException.onField("codes", "siteTransfer.unit.unknown", code));
            if (unit.getStatus() != StockStatus.AVAILABLE) {
                throw BusinessException.onField("codes", "siteTransfer.unit.notAvailable", code);
            }
            if (unit.getLocation() == null || !t.getFromSite().getId().equals(siteOf(unit.getLocation(), byId))) {
                throw BusinessException.onField("codes", "siteTransfer.unit.otherSite", code, t.getFromSite().getName());
            }
            SiteTransferLine line = lines.stream().filter(l -> fits(l, unit) && taken.getOrDefault(l.getLineNo(), 0) < l.getQuantity())
                    .findFirst().orElseThrow(() -> BusinessException.onField("codes", "siteTransfer.unit.notAsked", code));
            taken.merge(line.getLineNo(), 1, Integer::sum);
            units.add(unit);
        }
        stockService.requireNotHeld(units, null);
        for (StockUnit unit : units) {
            SiteTransferUnit link = new SiteTransferUnit();
            link.setTransferId(t.getId());
            link.setStockUnitId(unit.getId());
            link.setUnitCode(unit.getCode());
            link.setFromLocationId(unit.getLocation().getId());
            link.setDispatchedAt(now);
            unitLinkRepo.save(link);
            stockService.dispatch(unit, t.getId(), t.getNumber());
        }
        // Status and the fields its check needs, together, after the queries
        t.setVehiclePlate(plate.toUpperCase(Locale.ROOT));
        t.setDriverName(driver);
        t.setExpectedArrival(d.expectedArrival());
        t.setDispatchedBy(AppUserPrincipal.currentUsername());
        t.setDispatchedAt(now);
        t.setStatus(SiteTransferStatus.IN_TRANSIT);
        notifier.holders("RECEIVE_SITE_TRANSFER", null, NotificationKind.TRANSFER, "notify.siteTransfer.dispatched",
                "notify.siteTransfer.dispatchedText", "/site-transfers/" + t.getId(), t.getNumber(), t.getToSite().getName(),
                String.valueOf(units.size()));
        return t;
    }

    /**
     * A label scanned at the receiving site: a unit of this transfer not received yet, put on one of that site's racks or
     * slots (its limits; no full sheet on an off-cut rack). When the last one arrives, the transfer is received. A unit
     * missing from a transfer received short may still arrive late; its difference note stays.
     */
    @Transactional
    public SiteTransferUnit receive(UUID id, String code, UUID locationId) {
        SiteTransfer t = lock(id);
        if (t.getStatus() != SiteTransferStatus.IN_TRANSIT && t.getStatus() != SiteTransferStatus.RECEIVED_SHORT) {
            throw BusinessException.of("siteTransfer.notInTransit", t.getNumber());
        }
        String clean = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        StockUnit unit = stockService.findByCode(clean).orElseThrow(() -> BusinessException.onField("code", "siteTransfer.unit.unknown", clean));
        SiteTransferUnit link = unitLinkRepo.findByTransferIdAndStockUnitId(id, unit.getId())
                .orElseThrow(() -> BusinessException.onField("code", "siteTransfer.unit.notOnTransfer", clean, t.getNumber()));
        if (link.isReceived()) {
            throw BusinessException.onField("code", "siteTransfer.unit.received", clean);
        }
        Map<UUID, Location> byId = stockService.locationsById();
        Location place = locationId == null ? null : byId.get(locationId);
        if (place == null || !stockService.storagePlaces(byId).contains(place) || !t.getToSite().getId().equals(siteOf(place, byId))) {
            throw BusinessException.onField("locationId", "siteTransfer.place.invalid", t.getToSite().getName());
        }
        Location rack = StockService.rackOf(place, byId);
        if (rack.isOffcut() && unit.getKind() == UnitKind.SHEET) {
            throw BusinessException.onField("locationId", "trip.return.sheetToOffcut", unit.getCode(), rack.getCode());
        }
        RackLoad now = stockService.rackLoads(byId).getOrDefault(rack.getId(), RackLoad.EMPTY);
        BigDecimal kg = unit.getWeightKg() == null ? BigDecimal.ZERO : unit.getWeightKg();
        RackLoad after = now.plus(1, kg);
        if (after.exceedsPieces(rack.getMaxPieces())) {
            throw BusinessException.onField("locationId", "receipt.rack.pieces", rack.getCode(), now.pieces(), rack.getMaxPieces(), 1);
        }
        if (after.exceedsKg(rack.getMaxWeightKg())) {
            throw BusinessException.onField("locationId", "receipt.rack.weight", rack.getCode(), now.kg(), rack.getMaxWeightKg(), kg);
        }
        stockService.arrive(unit, place, t.getId(), t.getNumber());
        LocalDateTime at = LocalDateTime.now(clock);
        link.setReceivedAt(at);
        link.setReceivedBy(AppUserPrincipal.currentUsername());
        link.setToLocationId(place.getId());
        if (units(id).stream().allMatch(SiteTransferUnit::isReceived)) {
            finish(t, at, t.getDifferenceNote(), SiteTransferStatus.RECEIVED);
        }
        return link;
    }

    /**
     * Receiving finished with labels missing: received short, with the note on what did not arrive; the Internal Controller
     * and the Head of Inventory are told. The missing units stay in transit until found or written off by an adjustment.
     */
    @Transactional
    public SiteTransfer finishShort(UUID id, String note) {
        SiteTransfer t = lock(id);
        if (t.getStatus() != SiteTransferStatus.IN_TRANSIT) {
            throw BusinessException.of("siteTransfer.notInTransit", t.getNumber());
        }
        String clean = PartyRules.clean(note);
        if (clean == null || clean.length() > 500) {
            throw BusinessException.onField("note", "siteTransfer.difference.required");
        }
        long missing = units(id).stream().filter(u -> !u.isReceived()).count();
        finish(t, LocalDateTime.now(clock), clean, SiteTransferStatus.RECEIVED_SHORT);
        notifier.holders("ALERT_SITE_TRANSFER", null, NotificationKind.TRANSFER, "notify.siteTransfer.short", "notify.siteTransfer.shortText",
                "/site-transfers/" + t.getId(), t.getNumber(), String.valueOf(missing), clean);
        return t;
    }

    /**
     * Tells the Internal Controller and the Head of Inventory once per transfer still in transit the Settings' hours after
     * its expected arrival; how many were raised. Cleared once it is received.
     */
    @Scheduled(fixedDelayString = "${app.alerts.transfer-every:PT1H}", initialDelayString = "${app.alerts.first-check:PT1M}")
    @Transactional
    public int checkOverdue() {
        LocalDateTime now = LocalDateTime.now(clock);
        int hours = settingService.getInt(SettingKey.TRANSFER_DIFFERENCE_HOURS);
        Map<String, AlertState> raised = new HashMap<>();
        alertRepo.findByKeyStartingWithAndClearedAtIsNull(ALERT_PREFIX).forEach(s -> raised.put(s.getKey(), s));
        int count = 0;
        for (SiteTransfer t : repo.findByStatusAndDispatchedAtBefore(SiteTransferStatus.IN_TRANSIT, now)) {
            if (t.getExpectedArrival() == null || t.getExpectedArrival().plusHours(hours).isAfter(now)) {
                continue;
            }
            String key = ALERT_PREFIX + t.getId();
            if (raised.remove(key) != null) {
                continue;
            }
            AlertState state = alertRepo.findById(key).orElseGet(AlertState::new);
            state.setKey(key);
            state.setRaisedAt(now);
            state.setClearedAt(null);
            alertRepo.save(state);
            notifier.holders("ALERT_SITE_TRANSFER", null, NotificationKind.TRANSFER, "notify.siteTransfer.late", "notify.siteTransfer.lateText",
                    "/site-transfers/" + t.getId(), t.getNumber(), t.getToSite().getName(), String.valueOf(hours));
            count++;
        }
        raised.values().forEach(s -> s.setClearedAt(now));
        return count;
    }

    // ---------------------------------------------------------------- helpers

    /** The site a location belongs to (its root). */
    static UUID siteOf(Location location, Map<UUID, Location> byId) {
        Location l = location;
        int guard = 0;
        while (l != null && l.getParentId() != null && guard++ < 20) {
            l = byId.get(l.getParentId());
        }
        return l == null ? null : l.getId();
    }

    /** A unit answers to a line when its glass is the line's and, for a sized line, its size either way round. */
    static boolean fits(SiteTransferLine line, StockUnit unit) {
        if (!line.getProduct().getId().equals(unit.getProduct().getId())) {
            return false;
        }
        if (!line.isSized()) {
            return true;
        }
        return (line.getWidthMm() == unit.getWidthMm() && line.getHeightMm() == unit.getHeightMm())
                || (line.getWidthMm() == unit.getHeightMm() && line.getHeightMm() == unit.getWidthMm());
    }

    private void finish(SiteTransfer t, LocalDateTime at, String note, SiteTransferStatus status) {
        t.setReceivedBy(AppUserPrincipal.currentUsername());
        t.setReceivedAt(at);
        t.setDifferenceNote(note);
        t.setStatus(status);
        alertRepo.findById(ALERT_PREFIX + t.getId()).filter(s -> s.getClearedAt() == null).ifPresent(s -> s.setClearedAt(at));
    }

    private void tellDispatchers(SiteTransfer t) {
        notifier.holders("DISPATCH_SITE_TRANSFER", t.getRequestedById(), NotificationKind.TRANSFER, "notify.siteTransfer.toDispatch",
                "notify.siteTransfer.waitingText", "/site-transfers/" + t.getId(), t.getNumber(), t.getFromSite().getName(), t.getToSite().getName());
    }

    private void decide(SiteTransfer t, String note) {
        Optional<AppUserPrincipal> user = AppUserPrincipal.current();
        t.setDecidedBy(user.map(AppUserPrincipal::getUsername).orElse("system"));
        t.setDecidedById(user.map(AppUserPrincipal::getId).orElse(null));
        t.setDecidedAt(LocalDateTime.now(clock));
        t.setDecisionNote(note);
    }

    private static String reasonOf(String reason) {
        String why = PartyRules.clean(reason);
        if (why == null || why.length() > 255) {
            throw BusinessException.of("po.reason.required");
        }
        return why;
    }

    private static Location site(UUID id, Map<UUID, Location> byId, String field) {
        Location l = id == null ? null : byId.get(id);
        if (l == null || l.getType() != LocationType.SITE || !l.isEnabled()) {
            throw BusinessException.onField(field, "siteTransfer.site.invalid");
        }
        return l;
    }

    private SiteTransfer lock(UUID id) {
        repo.lockById(id).orElseThrow(() -> new NotFoundException("SiteTransfer", id));
        return findDetailed(id);
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    /** How many units went against each line (by line number), matched as the dispatch matched them. */
    public Map<Integer, Integer> sentByLine(UUID id) {
        List<SiteTransferLine> lines = lines(id);
        Map<UUID, StockUnit> byId = unitsById(id);
        Map<Integer, Integer> taken = new HashMap<>();
        for (SiteTransferUnit link : units(id)) {
            StockUnit unit = byId.get(link.getStockUnitId());
            if (unit == null) {
                continue;
            }
            lines.stream().filter(l -> fits(l, unit) && taken.getOrDefault(l.getLineNo(), 0) < l.getQuantity()).findFirst()
                    .ifPresent(l -> taken.merge(l.getLineNo(), 1, Integer::sum));
        }
        return taken;
    }

    /** Units of a transfer by id, with their product and location (its page). */
    public Map<UUID, StockUnit> unitsById(UUID id) {
        List<UUID> ids = units(id).stream().map(SiteTransferUnit::getStockUnitId).toList();
        return ids.isEmpty() ? Map.of() : unitRepo.findByIdIn(ids).stream().collect(Collectors.toMap(StockUnit::getId, u -> u));
    }
}
