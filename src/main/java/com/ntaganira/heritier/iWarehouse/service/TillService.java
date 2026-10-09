package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.SalesInvoice;
import com.ntaganira.heritier.iWarehouse.entity.TillSession;
import com.ntaganira.heritier.iWarehouse.enums.DocumentType;
import com.ntaganira.heritier.iWarehouse.enums.PaymentMethod;
import com.ntaganira.heritier.iWarehouse.enums.SalesInvoiceStatus;
import com.ntaganira.heritier.iWarehouse.enums.TillStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.CreditNoteRepository;
import com.ntaganira.heritier.iWarehouse.repository.SalesInvoiceRepository;
import com.ntaganira.heritier.iWarehouse.repository.SalesPaymentRepository;
import com.ntaganira.heritier.iWarehouse.repository.TillSessionRepository;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : TillService.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : Till sessions (POS-10). A cashier opens one with a float (it leaves the main cash vault) and
 *               sells in it; closing compares the cash counted with the cash expected (float + cash kept from
 *               sales). A difference needs a note and goes to Cash Over/Short; the cash goes back to the vault.
 *               A till does not close while a sale is being rung up in it.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class TillService {

    private final TillSessionRepository repo;
    private final SalesInvoiceRepository invoiceRepo;
    private final SalesPaymentRepository paymentRepo;
    private final CreditNoteRepository creditNoteRepo;
    private final PostingService postingService;
    private final DocumentNumberService numbers;
    private final Clock clock;

    public TillService(TillSessionRepository repo, SalesInvoiceRepository invoiceRepo, SalesPaymentRepository paymentRepo,
                       CreditNoteRepository creditNoteRepo, PostingService postingService, DocumentNumberService numbers, Clock clock) {
        this.repo = repo;
        this.invoiceRepo = invoiceRepo;
        this.paymentRepo = paymentRepo;
        this.creditNoteRepo = creditNoteRepo;
        this.postingService = postingService;
        this.numbers = numbers;
        this.clock = clock;
    }

    /**
     * What a till took so far: per payment method, the cash kept from sales, the cash refunded on credit notes (POS-09)
     * and the cash expected in the drawer.
     */
    public record Summary(TillSession session, Map<PaymentMethod, BigDecimal> byMethod, long invoices, BigDecimal cashRefunds) {

        public BigDecimal getCashSales() {
            return byMethod.getOrDefault(PaymentMethod.CASH, BigDecimal.ZERO);
        }

        public BigDecimal getCashRefunds() {
            return cashRefunds;
        }

        public BigDecimal getExpectedCash() {
            return session.getOpeningFloat().add(getCashSales()).subtract(cashRefunds);
        }

        public BigDecimal getTotal() {
            return byMethod.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    // ---------------------------------------------------------------- reading

    /** The signed-in cashier's open till, if any. */
    public Optional<TillSession> current() {
        return AppUserPrincipal.current().flatMap(u -> repo.findByCashierIdAndStatus(u.getId(), TillStatus.OPEN));
    }

    public TillSession findById(UUID id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException("TillSession", id));
    }

    public Summary summary(TillSession session) {
        Map<PaymentMethod, BigDecimal> byMethod = new EnumMap<>(PaymentMethod.class);
        for (Object[] row : paymentRepo.totalsOfSession(session.getId())) {
            byMethod.put((PaymentMethod) row[0], (BigDecimal) row[1]);
        }
        BigDecimal refunds = creditNoteRepo.cashRefundsOfSession(session.getId());
        return new Summary(session, byMethod, invoiceRepo.countByTillSession_IdAndStatus(session.getId(), SalesInvoiceStatus.POSTED),
                refunds == null ? BigDecimal.ZERO : refunds);
    }

    public Page<TillSession> findPage(String search, String status, int page, int size) {
        Specification<TillSession> spec = (root, query, cb) -> {
            Predicate p = cb.conjunction();
            if (StringUtils.hasText(search)) {
                String term = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                p = cb.and(p, cb.or(cb.like(cb.lower(root.get("number")), term), cb.like(cb.lower(root.get("cashierUsername")), term)));
            }
            if (StringUtils.hasText(status)) {
                try {
                    p = cb.and(p, cb.equal(root.get("status"), TillStatus.valueOf(status)));
                } catch (IllegalArgumentException e) {
                    // unknown status: no filter
                }
            }
            return p;
        };
        return repo.findAll(spec, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "openedAt")));
    }

    // ---------------------------------------------------------------- open and close

    /** Opens the signed-in cashier's till with a float taken from the main cash vault. */
    @Transactional
    public TillSession open(BigDecimal openingFloat) {
        AppUserPrincipal user = AppUserPrincipal.current().orElseThrow(() -> BusinessException.of("till.noUser"));
        if (openingFloat == null || openingFloat.signum() < 0 || openingFloat.stripTrailingZeros().scale() > 2) {
            throw BusinessException.onField("openingFloat", "till.float.invalid");
        }
        Optional<TillSession> open = repo.findByCashierIdAndStatus(user.getId(), TillStatus.OPEN);
        if (open.isPresent()) {
            throw BusinessException.of("till.alreadyOpen", open.get().getNumber());
        }
        TillSession session = new TillSession();
        session.setNumber(numbers.next(DocumentType.TILL_SESSION));
        session.setCashierId(user.getId());
        session.setCashierUsername(user.getUsername());
        session.setOpenedAt(LocalDateTime.now(clock));
        session.setOpeningFloat(openingFloat.setScale(2));
        repo.save(session);
        postingService.tillOpened(session);
        return session;
    }

    /**
     * Closes the signed-in cashier's till with the cash counted. Refused while a sale is being rung up in it
     * (an empty one is cancelled); a difference needs a note.
     */
    @Transactional
    public TillSession close(UUID sessionId, BigDecimal counted, String note) {
        TillSession session = repo.lockById(sessionId).orElseThrow(() -> new NotFoundException("TillSession", sessionId));
        requireOwnOpen(session);
        if (counted == null || counted.signum() < 0 || counted.stripTrailingZeros().scale() > 2) {
            throw BusinessException.onField("countedCash", "till.counted.invalid");
        }
        Optional<SalesInvoice> draft = invoiceRepo.findDraftOfTill(sessionId);
        if (draft.isPresent()) {
            if (!draft.get().getLines().isEmpty()) {
                throw BusinessException.of("till.close.saleOpen", draft.get().getLines().size());
            }
            draft.get().setStatus(SalesInvoiceStatus.CANCELLED);
        }
        Summary summary = summary(session);
        BigDecimal expected = summary.getExpectedCash();
        BigDecimal difference = counted.subtract(expected);
        String cleanNote = PartyRules.clean(note);
        if (difference.signum() != 0 && cleanNote == null) {
            throw BusinessException.onField("note", "till.close.noteRequired");
        }
        if (cleanNote != null && cleanNote.length() > 255) {
            throw BusinessException.onField("note", "till.close.noteSize");
        }
        // Status and the amounts its check needs, together, after the queries
        session.setCashSales(summary.getCashSales());
        session.setCashRefunds(summary.getCashRefunds());
        session.setExpectedCash(expected);
        session.setCountedCash(counted.setScale(2));
        session.setDifference(difference.setScale(2));
        session.setCloseNote(cleanNote);
        session.setClosedAt(LocalDateTime.now(clock));
        session.setStatus(TillStatus.CLOSED);
        postingService.tillClosed(session);
        return session;
    }

    /** The open till of the signed-in cashier; a sale or a close in someone else's till is refused. */
    TillSession requireOwnOpen(TillSession session) {
        Long me = AppUserPrincipal.current().map(AppUserPrincipal::getId).orElse(null);
        if (!session.isOpen()) {
            throw BusinessException.of("till.closed", session.getNumber());
        }
        if (!session.getCashierId().equals(me)) {
            throw BusinessException.of("till.notYours", session.getNumber(), session.getCashierUsername());
        }
        return session;
    }

    /** The signed-in cashier's open till, locked (selling and closing take it one at a time). */
    @Transactional
    public TillSession lockCurrent() {
        TillSession session = current().orElseThrow(() -> BusinessException.of("till.notOpen"));
        return repo.lockById(session.getId()).orElseThrow(() -> BusinessException.of("till.notOpen"));
    }
}
