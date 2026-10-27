package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.Customer;
import com.ntaganira.heritier.iWarehouse.entity.JournalEntry;
import com.ntaganira.heritier.iWarehouse.entity.ReceivableWriteOff;
import com.ntaganira.heritier.iWarehouse.entity.SalesInvoice;
import com.ntaganira.heritier.iWarehouse.enums.*;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.CustomerRepository;
import com.ntaganira.heritier.iWarehouse.repository.ReceivableWriteOffRepository;
import com.ntaganira.heritier.iWarehouse.repository.SalesInvoiceRepository;
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
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : ReceivableWriteOffService.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Receivable write-offs (F-14; Board Paper Table 6, 11.2): Credit Control prepares one for a customer, at most
 *               what they owe, with the reason and the recovery tried; Finance reviews it when Settings ask for it; another
 *               person approves it within the Delegation of Authority (the Board above the COO's limit, with its resolution),
 *               which posts bad debts against the customer's receivable on that day. Neither the preparer nor the reviewer
 *               decides. Approving locks the customer, then the write-off, and reads what they owe again.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class ReceivableWriteOffService {

    private final ReceivableWriteOffRepository repo;
    private final CustomerRepository customerRepo;
    private final SalesInvoiceRepository invoiceRepo;
    private final JournalService journalService;
    private final CustomerAgeing ageing;
    private final PostingService postingService;
    private final DocumentNumberService numbers;
    private final DelegationOfAuthority authority;
    private final SettingService settingService;
    private final Notifier notifier;
    private final Clock clock;

    public ReceivableWriteOffService(ReceivableWriteOffRepository repo, CustomerRepository customerRepo, SalesInvoiceRepository invoiceRepo,
                                     JournalService journalService, CustomerAgeing ageing, PostingService postingService,
                                     DocumentNumberService numbers, DelegationOfAuthority authority, SettingService settingService,
                                     Notifier notifier, Clock clock) {
        this.repo = repo;
        this.customerRepo = customerRepo;
        this.invoiceRepo = invoiceRepo;
        this.journalService = journalService;
        this.ageing = ageing;
        this.postingService = postingService;
        this.numbers = numbers;
        this.authority = authority;
        this.settingService = settingService;
        this.notifier = notifier;
        this.clock = clock;
    }

    /** What is asked: the amount, the invoice it is mainly about (if one), the reason and the recovery tried. */
    public record Form(BigDecimal amount, UUID invoiceId, String reason, String recoveryAction) {
    }

    // ---------------------------------------------------------------- reading

    public Page<ReceivableWriteOff> findPage(String status, String search, int page, int size) {
        Specification<ReceivableWriteOff> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (StringUtils.hasText(status)) {
                try {
                    p.add(cb.equal(root.get("status"), WriteOffStatus.valueOf(status)));
                } catch (IllegalArgumentException ignored) {
                    // an unknown status filters nothing
                }
            }
            if (StringUtils.hasText(search)) {
                String term = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                p.add(cb.or(cb.like(cb.lower(root.get("number")), term), cb.like(cb.lower(root.get("customer").get("name")), term),
                        cb.like(cb.lower(root.get("reason")), term), cb.like(cb.lower(root.get("requestedBy")), term)));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
        return repo.findAll(spec, PageRequest.of(page, size, Sort.by(Sort.Order.desc("requestedAt"), Sort.Order.desc("number"))));
    }

    public ReceivableWriteOff findDetailed(UUID id) {
        return repo.findDetailedById(id).orElseThrow(() -> new NotFoundException("ReceivableWriteOff", id));
    }

    public long waitingCount() {
        return repo.countByStatusIn(List.of(WriteOffStatus.PENDING_REVIEW, WriteOffStatus.PENDING_APPROVAL));
    }

    public List<ReceivableWriteOff> ofCustomer(UUID customerId) {
        return repo.findByCustomer_IdOrderByRequestedAtDesc(customerId);
    }

    public DelegationOfAuthority.Check authorityOf(ReceivableWriteOff writeOff) {
        return authority.check(AuthorityDecision.RECEIVABLE_WRITE_OFF, writeOff.getAmount());
    }

    public boolean reviewRequired() {
        return settingService.getBoolean(SettingKey.APPROVAL_WRITE_OFF_REVIEW);
    }

    /** What the customer owes now. */
    public BigDecimal owed(UUID customerId) {
        return journalService.receivable(customerId);
    }

    /** The customer's issued invoices, newest first: one may be named on the write-off. */
    public List<SalesInvoice> invoicesOf(UUID customerId) {
        return invoiceRepo.findByCustomer_IdAndStatusOrderByInvoiceDateDesc(customerId, SalesInvoiceStatus.POSTED);
    }

    public static boolean isRequester(ReceivableWriteOff w) {
        return AppUserPrincipal.current().map(u -> u.getId().equals(w.getRequestedById())).orElse(false);
    }

    public static boolean isReviewer(ReceivableWriteOff w) {
        return w.getReviewedById() != null && AppUserPrincipal.current().map(u -> u.getId().equals(w.getReviewedById())).orElse(false);
    }

    // ---------------------------------------------------------------- deciding

    /** Credit Control asks: at most what the customer owes, with the reason; Finance reviews it first when Settings ask. */
    @Transactional
    public ReceivableWriteOff prepare(UUID customerId, Form form) {
        Customer customer = customerRepo.findById(customerId).orElseThrow(() -> new NotFoundException("Customer", customerId));
        BigDecimal owed = owed(customerId);
        BigDecimal amount = form.amount();
        if (amount == null || amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 2) {
            throw BusinessException.onField("amount", "writeOff.amount.invalid");
        }
        if (amount.compareTo(owed) > 0) {
            throw BusinessException.onField("amount", "writeOff.amount.moreThanOwed", owed);
        }
        String reason = PartyRules.clean(form.reason());
        if (reason == null || reason.length() > 255) {
            throw BusinessException.onField("reason", reason == null ? "writeOff.reason.required" : "writeOff.reason.size");
        }
        String recovery = PartyRules.clean(form.recoveryAction());
        if (recovery != null && recovery.length() > 500) {
            throw BusinessException.onField("recoveryAction", "writeOff.recovery.size");
        }
        SalesInvoice invoice = null;
        if (form.invoiceId() != null) {
            invoice = invoiceRepo.findById(form.invoiceId()).filter(i -> i.getCustomer().getId().equals(customerId)
                    && i.getStatus() == SalesInvoiceStatus.POSTED).orElseThrow(() -> BusinessException.onField("invoiceId", "writeOff.invoice.invalid"));
        }
        Optional<AppUserPrincipal> user = AppUserPrincipal.current();
        ReceivableWriteOff w = new ReceivableWriteOff();
        w.setNumber(numbers.next(DocumentType.RECEIVABLE_WRITE_OFF));
        w.setCustomer(customer);
        w.setInvoice(invoice);
        w.setAmount(amount.setScale(2, RoundingMode.HALF_UP));
        w.setOwedWhenAsked(owed);
        CustomerAgeing.Overdue overdue = ageing.overdue(customer, 0);
        w.setOldestDays(overdue.any() ? (int) Math.min(overdue.oldestDays(), Integer.MAX_VALUE) : null);
        w.setReason(reason);
        w.setRecoveryAction(recovery);
        w.setAuthorityLevel(authority.required(AuthorityDecision.RECEIVABLE_WRITE_OFF, w.getAmount()));
        w.setStatus(reviewRequired() ? WriteOffStatus.PENDING_REVIEW : WriteOffStatus.PENDING_APPROVAL);
        w.setRequestedBy(user.map(AppUserPrincipal::getUsername).orElse("system"));
        w.setRequestedById(user.map(AppUserPrincipal::getId).orElse(null));
        w.setRequestedAt(LocalDateTime.now(clock));
        repo.save(w);
        tell(w);
        return w;
    }

    /** Finance reviews it (not who asked); then it waits for the approver. */
    @Transactional
    public ReceivableWriteOff review(UUID id, String note) {
        ReceivableWriteOff w = lock(id);
        if (w.getStatus() != WriteOffStatus.PENDING_REVIEW) {
            throw BusinessException.of("writeOff.notForReview", w.getNumber());
        }
        if (isRequester(w)) {
            throw BusinessException.of("writeOff.ownReview", w.getNumber());
        }
        Optional<AppUserPrincipal> user = AppUserPrincipal.current();
        w.setReviewedBy(user.map(AppUserPrincipal::getUsername).orElse("system"));
        w.setReviewedById(user.map(AppUserPrincipal::getId).orElse(null));
        w.setReviewedAt(LocalDateTime.now(clock));
        w.setReviewNote(PartyRules.clean(note));
        w.setStatus(WriteOffStatus.PENDING_APPROVAL);
        tell(w);
        return w;
    }

    /**
     * Another person approves it within the Delegation of Authority: bad debts are posted against the customer's receivable
     * today, never more than they owe now.
     */
    @Transactional
    public ReceivableWriteOff approve(UUID id, String note) {
        ReceivableWriteOff found = findDetailed(id);
        customerRepo.lockById(found.getCustomer().getId());
        ReceivableWriteOff w = lock(id);
        if (w.getStatus() != WriteOffStatus.PENDING_APPROVAL) {
            throw BusinessException.of(w.getStatus() == WriteOffStatus.PENDING_REVIEW ? "writeOff.reviewFirst" : "writeOff.notPending", w.getNumber());
        }
        if (isRequester(w)) {
            throw BusinessException.of("writeOff.ownApproval", w.getNumber());
        }
        if (isReviewer(w)) {
            throw BusinessException.of("writeOff.reviewerApproval", w.getNumber());
        }
        String clean = PartyRules.clean(note);
        authority.require(AuthorityDecision.RECEIVABLE_WRITE_OFF, w.getAmount(), clean);
        BigDecimal owed = owed(w.getCustomer().getId());
        if (w.getAmount().compareTo(owed) > 0) {
            throw BusinessException.of("writeOff.owedNow", w.getNumber(), owed);
        }
        LocalDate today = LocalDate.now(clock);
        JournalEntry journal = postingService.receivableWriteOff(w, today);
        Optional<AppUserPrincipal> user = AppUserPrincipal.current();
        LocalDateTime now = LocalDateTime.now(clock);
        // Status and the fields its check needs, together, after the queries
        w.setDecidedBy(user.map(AppUserPrincipal::getUsername).orElse("system"));
        w.setDecidedById(user.map(AppUserPrincipal::getId).orElse(null));
        w.setDecidedAt(now);
        w.setDecisionNote(clean);
        w.setJournalId(journal.getId());
        w.setPostedAt(now);
        w.setStatus(WriteOffStatus.POSTED);
        notifier.user(w.getRequestedById(), NotificationKind.DECISION, "notify.writeOff.approved", "notify.decidedBy",
                "/accounting/write-offs/" + w.getId(), w.getNumber(), w.getDecidedBy());
        return w;
    }

    /** A reviewer or an approver rejects it (not who asked), with a reason. */
    @Transactional
    public ReceivableWriteOff reject(UUID id, String reason) {
        ReceivableWriteOff w = lock(id);
        if (!w.isWaiting()) {
            throw BusinessException.of("writeOff.notPending", w.getNumber());
        }
        if (isRequester(w)) {
            throw BusinessException.of("writeOff.ownReject", w.getNumber());
        }
        close(w, WriteOffStatus.REJECTED, reason);
        notifier.user(w.getRequestedById(), NotificationKind.DECISION, "notify.writeOff.rejected", "notify.decidedBy",
                "/accounting/write-offs/" + w.getId(), w.getNumber(), w.getDecidedBy());
        return w;
    }

    /** Whoever asked takes it back, with a reason. */
    @Transactional
    public ReceivableWriteOff withdraw(UUID id, String reason) {
        ReceivableWriteOff w = lock(id);
        if (!w.isWaiting()) {
            throw BusinessException.of("writeOff.notPending", w.getNumber());
        }
        if (!isRequester(w)) {
            throw BusinessException.of("writeOff.notYours", w.getNumber());
        }
        close(w, WriteOffStatus.WITHDRAWN, reason);
        return w;
    }

    private void close(ReceivableWriteOff w, WriteOffStatus status, String reason) {
        String why = PartyRules.clean(reason);
        if (why == null || why.length() > 255) {
            throw BusinessException.of("po.reason.required");
        }
        Optional<AppUserPrincipal> user = AppUserPrincipal.current();
        w.setDecidedBy(user.map(AppUserPrincipal::getUsername).orElse("system"));
        w.setDecidedById(user.map(AppUserPrincipal::getId).orElse(null));
        w.setDecidedAt(LocalDateTime.now(clock));
        w.setDecisionNote(why);
        w.setStatus(status);
    }

    /** The reviewers, or the approvers, are told it waits for them. */
    private void tell(ReceivableWriteOff w) {
        boolean review = w.getStatus() == WriteOffStatus.PENDING_REVIEW;
        notifier.holders(review ? "REVIEW_CREDIT_CONTROL" : "APPROVE_WRITE_OFF", w.getRequestedById(), NotificationKind.APPROVAL,
                review ? "notify.writeOff.review" : "notify.writeOff.waiting", "notify.writeOff.waitingText",
                "/accounting/write-offs/" + w.getId(), w.getNumber(), w.getCustomer().getName(), w.getAmount().setScale(0, RoundingMode.HALF_UP).toPlainString());
    }

    private ReceivableWriteOff lock(UUID id) {
        repo.lockById(id).orElseThrow(() -> new NotFoundException("ReceivableWriteOff", id));
        return findDetailed(id);
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }
}
