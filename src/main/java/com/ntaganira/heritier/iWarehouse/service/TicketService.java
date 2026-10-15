package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.StockMovement;
import com.ntaganira.heritier.iWarehouse.entity.StockUnit;
import com.ntaganira.heritier.iWarehouse.entity.TransactionTicket;
import com.ntaganira.heritier.iWarehouse.enums.*;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.StockMovementRepository;
import com.ntaganira.heritier.iWarehouse.repository.TransactionTicketRepository;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : TicketService.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : Transaction Tickets (BP 7.4, F-05, Annex I; decision D6). Every stock movement document but sales gets one:
 *               raised from its movements (receipts, transfers, site dispatches and arrivals, loads, returns from the road,
 *               adjustments and losses, customer returns) by whoever moved the glass, on a schedule and when the tickets
 *               list opens, for movements from the Settings' start day. Supply Chain verifies it against the glass (not who
 *               raised it), Commercial countersigns it (neither of them), Finance posts it (TicketPostingService). With the
 *               Settings' gate a goods receipt waits for its ticket (raised before it posts) and posts when Finance posts it.
 *               A ticket may be rejected with a reason; a rejected receipt ticket is raised again once the receipt is right.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class TicketService {

    public static final String SOURCE_GOODS_RECEIPT = StockService.REF_GOODS_RECEIPT;

    /** The movements each kind of ticket covers. */
    static final Map<TicketKind, List<MovementType>> MOVEMENTS = new EnumMap<>(Map.of(
            TicketKind.RECEIPT, List.of(MovementType.RECEIPT),
            TicketKind.TRANSFER, List.of(MovementType.TRANSFER),
            TicketKind.SITE_DISPATCH, List.of(MovementType.DISPATCH),
            TicketKind.SITE_ARRIVAL, List.of(MovementType.ARRIVAL),
            TicketKind.LOAD, List.of(MovementType.LOAD),
            TicketKind.RETURN, List.of(MovementType.UNLOAD, MovementType.BREAKAGE),
            TicketKind.ADJUSTMENT, List.of(MovementType.ADJUSTMENT, MovementType.LOSS),
            TicketKind.CUSTOMER_RETURN, List.of(MovementType.RETURN)));

    static final List<TicketStatus> OPEN = List.of(TicketStatus.RAISED, TicketStatus.VERIFIED, TicketStatus.COUNTERSIGNED);

    private final TransactionTicketRepository repo;
    private final StockMovementRepository movementRepo;
    private final DocumentNumberService numbers;
    private final SettingService settingService;
    private final Notifier notifier;
    private final Clock clock;

    public TicketService(TransactionTicketRepository repo, StockMovementRepository movementRepo, DocumentNumberService numbers,
                         SettingService settingService, Notifier notifier, Clock clock) {
        this.repo = repo;
        this.movementRepo = movementRepo;
        this.numbers = numbers;
        this.settingService = settingService;
        this.notifier = notifier;
        this.clock = clock;
    }

    public boolean enabled() {
        return settingService.getBoolean(SettingKey.TICKET_ENABLED);
    }

    /** Whether a goods receipt posts only from its countersigned ticket (Settings). */
    public boolean gateReceipts() {
        return enabled() && settingService.getBoolean(SettingKey.TICKET_GATE_RECEIPTS);
    }

    // ---------------------------------------------------------------- reading

    public Page<TransactionTicket> findPage(String status, String kind, String search, int page, int size) {
        Specification<TransactionTicket> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if ("open".equals(status)) {
                p.add(root.get("status").in(OPEN));
            } else if (StringUtils.hasText(status)) {
                try {
                    p.add(cb.equal(root.get("status"), TicketStatus.valueOf(status)));
                } catch (IllegalArgumentException ignored) {
                    // an unknown status filters nothing
                }
            }
            if (StringUtils.hasText(kind)) {
                try {
                    p.add(cb.equal(root.get("kind"), TicketKind.valueOf(kind)));
                } catch (IllegalArgumentException ignored) {
                    // an unknown kind filters nothing
                }
            }
            if (StringUtils.hasText(search)) {
                String term = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                p.add(cb.or(cb.like(cb.lower(root.get("number")), term), cb.like(cb.lower(root.get("sourceNumber")), term),
                        cb.like(cb.lower(root.get("raisedBy")), term)));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
        return repo.findAll(spec, PageRequest.of(page, size, Sort.by(Sort.Order.desc("raisedAt"), Sort.Order.desc("number"))));
    }

    public TransactionTicket find(UUID id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException("TransactionTicket", id));
    }

    public List<TransactionTicket> ofSource(UUID sourceId) {
        return repo.findBySourceIdOrderByRaisedAtAsc(sourceId);
    }

    /** One unit a ticket covers: what moved, from where to where, when. */
    public record Moved(UUID unitId, String unitCode, String productCode, int widthMm, int heightMm, BigDecimal areaM2,
                        MovementType type, UUID fromLocationId, UUID toLocationId, LocalDateTime at, String username) {
    }

    /** The movements a ticket covers, by label (none yet for a goods receipt waiting for its ticket). */
    public List<Moved> movementsOf(TransactionTicket t) {
        List<Moved> moved = new ArrayList<>();
        for (Object[] r : movementRepo.ofDocument(t.getSourceId(), MOVEMENTS.get(t.getKind()))) {
            StockMovement m = (StockMovement) r[0];
            StockUnit u = (StockUnit) r[1];
            moved.add(new Moved(u.getId(), u.getCode(), u.getProduct().getCode(), u.getWidthMm(), u.getHeightMm(), u.getAreaM2(),
                    m.getType(), m.getFromLocationId(), m.getToLocationId(), m.getMovedAt(), m.getUsername()));
        }
        return moved;
    }

    public Optional<TransactionTicket> receiptTicket(UUID receiptId) {
        return repo.findBySourceTypeAndSourceIdAndKind(SOURCE_GOODS_RECEIPT, receiptId, TicketKind.RECEIPT);
    }

    public long openCount() {
        return repo.countByStatusIn(OPEN);
    }

    public static boolean isRaiser(TransactionTicket t) {
        return is(t.getRaisedById());
    }

    public static boolean isVerifier(TransactionTicket t) {
        return is(t.getVerifiedById());
    }

    public static boolean isCountersigner(TransactionTicket t) {
        return is(t.getCountersignedById());
    }

    private static boolean is(Long userId) {
        return userId != null && AppUserPrincipal.current().map(u -> u.getId().equals(userId)).orElse(false);
    }

    // ---------------------------------------------------------------- raising

    /**
     * Raises the tickets of the movement documents without one (from the Settings' start day), each by whoever moved the
     * glass, with its units, m² and cost; Supply Chain is told how many wait. How many were raised.
     */
    @Scheduled(fixedDelayString = "${app.tickets.raise-every:PT10M}", initialDelayString = "${app.alerts.first-check:PT1M}")
    @Transactional
    public int raiseDue() {
        if (!enabled()) {
            return 0;
        }
        LocalDateTime since = since();
        Set<String> seen = new HashSet<>();
        int count = 0;
        List<MovementType> types = MOVEMENTS.values().stream().flatMap(List::stream).toList();
        for (Object[] row : movementRepo.documentsSince(since, types)) {
            String refType = (String) row[0];
            UUID refId = (UUID) row[1];
            MovementType type = (MovementType) row[3];
            TicketKind kind = kindOf(type);
            if (kind == null || !seen.add(refType + refId + kind) || repo.existsBySourceTypeAndSourceIdAndKind(refType, refId, kind)) {
                continue;
            }
            List<Object[]> totals = movementRepo.totalsOf(refId, MOVEMENTS.get(kind));
            Object[] t = totals.isEmpty() ? new Object[]{0L, BigDecimal.ZERO, BigDecimal.ZERO} : totals.get(0);
            LocalDateTime at = (LocalDateTime) row[4];
            TransactionTicket ticket = new TransactionTicket();
            ticket.setNumber(numbers.next(DocumentType.TRANSACTION_TICKET));
            ticket.setKind(kind);
            ticket.setSourceType(refType);
            ticket.setSourceId(refId);
            ticket.setSourceNumber(row[2] == null ? refId.toString() : (String) row[2]);
            ticket.setSummary(refType + " " + ticket.getSourceNumber());
            ticket.setPieces(((Number) t[0]).intValue());
            ticket.setAreaM2(((BigDecimal) t[1]).setScale(4, RoundingMode.HALF_UP));
            ticket.setValue(((BigDecimal) t[2]).setScale(2, RoundingMode.HALF_UP));
            ticket.setMovementPosted(true);
            ticket.setStatus(TicketStatus.RAISED);
            ticket.setRaisedBy(row[5] == null ? "system" : (String) row[5]);
            ticket.setRaisedById(row[6] == null ? null : ((Number) row[6]).longValue());
            ticket.setRaisedAt(at);
            repo.save(ticket);
            count++;
        }
        if (count > 0) {
            notifier.holders("VERIFY_TICKET", null, NotificationKind.APPROVAL, "notify.ticket.raised", "notify.ticket.raisedText",
                    "/tickets?status=RAISED", String.valueOf(count));
        }
        return count;
    }

    /**
     * Raises (or raises again, once rejected) the ticket of a goods receipt that waits for it to post (Settings' gate): its
     * good sheets and m²; the cost is known only once it posts at the day's rate.
     */
    @Transactional
    public TransactionTicket raiseReceipt(UUID receiptId, String receiptNumber, int sheets, BigDecimal areaM2) {
        Optional<TransactionTicket> existing = receiptTicket(receiptId);
        Optional<AppUserPrincipal> user = AppUserPrincipal.current();
        LocalDateTime now = LocalDateTime.now(clock);
        TransactionTicket ticket;
        if (existing.isPresent()) {
            ticket = existing.get();
            if (ticket.getStatus() != TicketStatus.REJECTED) {
                return ticket;                                   // already waiting, or posted
            }
            ticket.setVerifiedBy(null);
            ticket.setVerifiedById(null);
            ticket.setVerifiedAt(null);
            ticket.setVerifyNote(null);
            ticket.setCountersignedBy(null);
            ticket.setCountersignedById(null);
            ticket.setCountersignedAt(null);
            ticket.setCountersignNote(null);
            ticket.setRejectedBy(null);
            ticket.setRejectedAt(null);
            ticket.setRejectReason(null);
            ticket.setStatus(TicketStatus.RAISED);
        } else {
            ticket = new TransactionTicket();
            ticket.setNumber(numbers.next(DocumentType.TRANSACTION_TICKET));
            ticket.setKind(TicketKind.RECEIPT);
            ticket.setSourceType(SOURCE_GOODS_RECEIPT);
            ticket.setSourceId(receiptId);
            ticket.setSourceNumber(receiptNumber);
            ticket.setSummary(SOURCE_GOODS_RECEIPT + " " + receiptNumber);
            ticket.setPieces(sheets);
            ticket.setAreaM2(areaM2.setScale(4, RoundingMode.HALF_UP));
            ticket.setMovementPosted(false);
            ticket.setStatus(TicketStatus.RAISED);
            ticket.setRaisedBy(user.map(AppUserPrincipal::getUsername).orElse("system"));
            ticket.setRaisedById(user.map(AppUserPrincipal::getId).orElse(null));
            ticket.setRaisedAt(now);
            repo.save(ticket);
        }
        notifier.holders("VERIFY_TICKET", ticket.getRaisedById(), NotificationKind.APPROVAL, "notify.ticket.receipt", "notify.ticket.receiptText",
                "/tickets/" + ticket.getId(), ticket.getNumber(), receiptNumber);
        return ticket;
    }

    // ---------------------------------------------------------------- signing

    /** Supply Chain verifies it against the glass that moved (not who raised it). */
    @Transactional
    public TransactionTicket verify(UUID id, String note) {
        TransactionTicket t = lock(id);
        if (t.getStatus() != TicketStatus.RAISED) {
            throw BusinessException.of("ticket.notForVerify", t.getNumber());
        }
        if (isRaiser(t)) {
            throw BusinessException.of("ticket.ownVerify", t.getNumber());
        }
        Optional<AppUserPrincipal> user = AppUserPrincipal.current();
        t.setVerifiedBy(user.map(AppUserPrincipal::getUsername).orElse("system"));
        t.setVerifiedById(user.map(AppUserPrincipal::getId).orElse(null));
        t.setVerifiedAt(LocalDateTime.now(clock));
        t.setVerifyNote(PartyRules.clean(note));
        t.setStatus(TicketStatus.VERIFIED);
        return t;
    }

    /** Commercial countersigns it: the movement answers to an order, a request or a return (not who raised or verified it). */
    @Transactional
    public TransactionTicket countersign(UUID id, String note) {
        TransactionTicket t = lock(id);
        if (t.getStatus() != TicketStatus.VERIFIED) {
            throw BusinessException.of("ticket.notForCountersign", t.getNumber());
        }
        if (isRaiser(t) || isVerifier(t)) {
            throw BusinessException.of("ticket.ownCountersign", t.getNumber());
        }
        Optional<AppUserPrincipal> user = AppUserPrincipal.current();
        t.setCountersignedBy(user.map(AppUserPrincipal::getUsername).orElse("system"));
        t.setCountersignedById(user.map(AppUserPrincipal::getId).orElse(null));
        t.setCountersignedAt(LocalDateTime.now(clock));
        t.setCountersignNote(PartyRules.clean(note));
        t.setStatus(TicketStatus.COUNTERSIGNED);
        notifier.holders("POST_TICKET", t.getCountersignedById(), NotificationKind.APPROVAL, "notify.ticket.toPost", "notify.ticket.toPostText",
                "/tickets/" + t.getId(), t.getNumber(), t.getSourceNumber());
        return t;
    }

    /** A signer rejects it with the reason (not who raised it): whoever raised it is told. */
    @Transactional
    public TransactionTicket reject(UUID id, String reason) {
        TransactionTicket t = lock(id);
        if (!t.isOpen()) {
            throw BusinessException.of("ticket.notOpen", t.getNumber());
        }
        if (isRaiser(t)) {
            throw BusinessException.of("ticket.ownReject", t.getNumber());
        }
        String why = PartyRules.clean(reason);
        if (why == null || why.length() > 255) {
            throw BusinessException.of("po.reason.required");
        }
        t.setRejectedBy(AppUserPrincipal.currentUsername());
        t.setRejectedAt(LocalDateTime.now(clock));
        t.setRejectReason(why);
        t.setStatus(TicketStatus.REJECTED);
        notifier.user(t.getRaisedById(), NotificationKind.DECISION, "notify.ticket.rejected", "notify.decidedBy",
                "/tickets/" + t.getId(), t.getNumber(), t.getRejectedBy());
        return t;
    }

    // ---------------------------------------------------------------- helpers

    TransactionTicket lock(UUID id) {
        return repo.lockById(id).orElseThrow(() -> new NotFoundException("TransactionTicket", id));
    }

    static TicketKind kindOf(MovementType type) {
        for (Map.Entry<TicketKind, List<MovementType>> e : MOVEMENTS.entrySet()) {
            if (e.getValue().contains(type)) {
                return e.getKey();
            }
        }
        return null;
    }

    private LocalDateTime since() {
        String day = settingService.get(SettingKey.TICKET_SINCE);
        try {
            return day == null ? LocalDate.now(clock).atStartOfDay() : LocalDate.parse(day.trim()).atStartOfDay();
        } catch (RuntimeException e) {
            return LocalDate.now(clock).atStartOfDay();
        }
    }
}
