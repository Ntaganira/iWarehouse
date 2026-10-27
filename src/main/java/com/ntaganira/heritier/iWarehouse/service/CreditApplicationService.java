package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.AlertState;
import com.ntaganira.heritier.iWarehouse.entity.CreditApplication;
import com.ntaganira.heritier.iWarehouse.entity.Customer;
import com.ntaganira.heritier.iWarehouse.entity.PriceList;
import com.ntaganira.heritier.iWarehouse.enums.*;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.AlertStateRepository;
import com.ntaganira.heritier.iWarehouse.repository.CreditApplicationRepository;
import com.ntaganira.heritier.iWarehouse.repository.CustomerRepository;
import com.ntaganira.heritier.iWarehouse.repository.PriceListRepository;
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
 * - File      : CreditApplicationService.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Credit applications (F-02 C-D): a customer's credit limit, payment terms and price list asked for, with the
 *               reason, what they owe and how late; Credit Control assesses it (with its note), the Director of Finance
 *               reviews it when Settings ask, and another person approves it within the Delegation of Authority (by the
 *               limit asked), which sets the terms through CustomerService and the date of the next review. Nobody
 *               decides an application they asked, assessed or reviewed. Reviews (F-02 C): an approved limit is reviewed
 *               after the Settings' months, and at once when an invoice is the Settings' days overdue; Credit Control is
 *               told once per customer while it is due.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class CreditApplicationService {

    static final String ALERT_PREFIX = "CREDIT_REVIEW:";
    static final List<CreditApplicationStatus> WAITING = List.of(CreditApplicationStatus.PENDING_ASSESSMENT,
            CreditApplicationStatus.PENDING_REVIEW, CreditApplicationStatus.PENDING_APPROVAL);

    private final CreditApplicationRepository repo;
    private final CustomerRepository customerRepo;
    private final PriceListRepository priceListRepo;
    private final CustomerService customerService;
    private final JournalService journalService;
    private final CustomerAgeing ageing;
    private final DocumentNumberService numbers;
    private final DelegationOfAuthority authority;
    private final SettingService settingService;
    private final AlertStateRepository alertRepo;
    private final Notifier notifier;
    private final Clock clock;

    public CreditApplicationService(CreditApplicationRepository repo, CustomerRepository customerRepo, PriceListRepository priceListRepo,
                                    CustomerService customerService, JournalService journalService, CustomerAgeing ageing,
                                    DocumentNumberService numbers, DelegationOfAuthority authority, SettingService settingService,
                                    AlertStateRepository alertRepo, Notifier notifier, Clock clock) {
        this.repo = repo;
        this.customerRepo = customerRepo;
        this.priceListRepo = priceListRepo;
        this.customerService = customerService;
        this.journalService = journalService;
        this.ageing = ageing;
        this.numbers = numbers;
        this.authority = authority;
        this.settingService = settingService;
        this.alertRepo = alertRepo;
        this.notifier = notifier;
        this.clock = clock;
    }

    /** What is asked: the terms wanted (null price list = the default list) and why. */
    public record Form(BigDecimal creditLimit, Integer paymentTermsDays, UUID priceListId, String reason) {
    }

    /** Why a customer's credit is due for review: never reviewed, its date passed, or an invoice long overdue. */
    public enum DueReason { NEVER, DATE, OVERDUE }

    /** A customer whose credit limit is due for review, why, since when, how late its oldest invoice is, and any application waiting. */
    public record ReviewDue(Customer customer, DueReason reason, LocalDate dueOn, long oldestOverdueDays, CreditApplication waiting) {
    }

    // ---------------------------------------------------------------- reading

    public Page<CreditApplication> findPage(String status, String search, int page, int size) {
        Specification<CreditApplication> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (StringUtils.hasText(status)) {
                try {
                    p.add(cb.equal(root.get("status"), CreditApplicationStatus.valueOf(status)));
                } catch (IllegalArgumentException ignored) {
                    // an unknown status filters nothing
                }
            }
            if (StringUtils.hasText(search)) {
                String term = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                p.add(cb.or(cb.like(cb.lower(root.get("number")), term), cb.like(cb.lower(root.get("customer").get("name")), term),
                        cb.like(cb.lower(root.get("customer").get("code")), term), cb.like(cb.lower(root.get("requestedBy")), term)));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
        return repo.findAll(spec, PageRequest.of(page, size, Sort.by(Sort.Order.desc("requestedAt"), Sort.Order.desc("number"))));
    }

    public CreditApplication findDetailed(UUID id) {
        return repo.findDetailedById(id).orElseThrow(() -> new NotFoundException("CreditApplication", id));
    }

    public List<CreditApplication> ofCustomer(UUID customerId) {
        return repo.findByCustomer_IdOrderByRequestedAtDesc(customerId);
    }

    public Optional<CreditApplication> waitingOf(UUID customerId) {
        return repo.findByCustomer_IdAndStatusIn(customerId, WAITING).stream().findFirst();
    }

    public long waitingCount() {
        return repo.countByStatusIn(WAITING);
    }

    public DelegationOfAuthority.Check authorityOf(CreditApplication a) {
        return authority.check(AuthorityDecision.CREDIT_LIMIT, a.getCreditLimit());
    }

    public boolean reviewRequired() {
        return settingService.getBoolean(SettingKey.APPROVAL_CREDIT_APPLICATION_REVIEW);
    }

    /** Active price lists a customer may be put on. */
    public List<PriceList> priceLists() {
        return priceListRepo.findAll(Sort.by("name")).stream().filter(PriceList::isEnabled).toList();
    }

    public static boolean isRequester(CreditApplication a) {
        return is(a.getRequestedById());
    }

    public static boolean isAssessor(CreditApplication a) {
        return is(a.getAssessedById());
    }

    public static boolean isReviewer(CreditApplication a) {
        return is(a.getReviewedById());
    }

    private static boolean is(Long userId) {
        return userId != null && AppUserPrincipal.current().map(u -> u.getId().equals(userId)).orElse(false);
    }

    // ---------------------------------------------------------------- deciding

    /** Asks for a customer's terms: an account customer, one application at a time, the terms valid and a reason given. */
    @Transactional
    public CreditApplication request(UUID customerId, Form form) {
        Customer customer = customerRepo.findWithPriceListById(customerId).orElseThrow(() -> new NotFoundException("Customer", customerId));
        if (!customer.getType().isCreditAllowed() || customer.isDefaultCustomer()) {
            throw BusinessException.of("creditApp.walkIn", customer.getName());
        }
        if (!customer.isEnabled()) {
            throw BusinessException.of("creditApp.inactive", customer.getName());
        }
        waitingOf(customerId).ifPresent(w -> {
            throw BusinessException.of("creditApp.waiting", w.getNumber());
        });
        BigDecimal limit = form.creditLimit();
        if (limit == null || limit.signum() < 0 || limit.stripTrailingZeros().scale() > 0) {
            throw BusinessException.onField("creditLimit", "customer.credit.range");
        }
        Integer days = form.paymentTermsDays();
        if (days == null || days < 0 || days > 365) {
            throw BusinessException.onField("paymentTermsDays", "party.terms.range");
        }
        String reason = PartyRules.clean(form.reason());
        if (reason == null || reason.length() > 500) {
            throw BusinessException.onField("reason", reason == null ? "creditApp.reason.required" : "creditApp.reason.size");
        }
        PriceList list = null;
        if (form.priceListId() != null) {
            list = priceListRepo.findById(form.priceListId()).filter(PriceList::isEnabled)
                    .orElseThrow(() -> BusinessException.onField("priceListId", "customer.priceList.invalid"));
            if (list.isDefaultList()) {
                list = null;                                     // the default list is "no list of its own"
            }
        }
        BigDecimal owed = journalService.receivable(customerId);
        CustomerAgeing.Overdue overdue = ageing.overdue(customer, 0);
        Optional<AppUserPrincipal> user = AppUserPrincipal.current();
        CreditApplication a = new CreditApplication();
        a.setNumber(numbers.next(DocumentType.CREDIT_APPLICATION));
        a.setCustomer(customer);
        boolean same = limit.compareTo(customer.getCreditLimit()) == 0 && days == customer.getPaymentTermsDays()
                && Objects.equals(list == null ? null : list.getId(), customer.getPriceList() == null ? null : customer.getPriceList().getId());
        a.setKind(!customer.hasCredit() ? CreditApplicationKind.NEW : same ? CreditApplicationKind.REVIEW : CreditApplicationKind.CHANGE);
        a.setCreditLimit(limit.setScale(2, RoundingMode.HALF_UP));
        a.setPaymentTermsDays(days);
        a.setPriceList(list);
        a.setPreviousLimit(customer.getCreditLimit());
        a.setPreviousTermsDays(customer.getPaymentTermsDays());
        a.setPreviousPriceList(customer.getPriceList());
        a.setReason(reason);
        a.setOwedWhenAsked(owed);
        a.setOldestOverdueDays(overdue.any() ? (int) Math.min(overdue.oldestDays(), Integer.MAX_VALUE) : null);
        a.setAuthorityLevel(authority.required(AuthorityDecision.CREDIT_LIMIT, a.getCreditLimit()));
        a.setStatus(CreditApplicationStatus.PENDING_ASSESSMENT);
        a.setRequestedBy(user.map(AppUserPrincipal::getUsername).orElse("system"));
        a.setRequestedById(user.map(AppUserPrincipal::getId).orElse(null));
        a.setRequestedAt(LocalDateTime.now(clock));
        repo.save(a);
        tell(a);
        return a;
    }

    /** Credit Control assesses it, with its note (not who asked); then the Director of Finance reviews it, or it waits for approval. */
    @Transactional
    public CreditApplication assess(UUID id, String note) {
        CreditApplication a = lock(id);
        if (a.getStatus() != CreditApplicationStatus.PENDING_ASSESSMENT) {
            throw BusinessException.of("creditApp.notForAssessment", a.getNumber());
        }
        if (isRequester(a)) {
            throw BusinessException.of("creditApp.ownAssessment", a.getNumber());
        }
        String clean = PartyRules.clean(note);
        if (clean == null || clean.length() > 500) {
            throw BusinessException.of(clean == null ? "creditApp.assessment.required" : "creditApp.assessment.size");
        }
        Optional<AppUserPrincipal> user = AppUserPrincipal.current();
        a.setAssessedBy(user.map(AppUserPrincipal::getUsername).orElse("system"));
        a.setAssessedById(user.map(AppUserPrincipal::getId).orElse(null));
        a.setAssessedAt(LocalDateTime.now(clock));
        a.setAssessmentNote(clean);
        a.setStatus(reviewRequired() ? CreditApplicationStatus.PENDING_REVIEW : CreditApplicationStatus.PENDING_APPROVAL);
        tell(a);
        return a;
    }

    /** The Director of Finance reviews it (not who asked or assessed it); then it waits for approval. */
    @Transactional
    public CreditApplication review(UUID id, String note) {
        CreditApplication a = lock(id);
        if (a.getStatus() != CreditApplicationStatus.PENDING_REVIEW) {
            throw BusinessException.of("creditApp.notForReview", a.getNumber());
        }
        if (isRequester(a) || isAssessor(a)) {
            throw BusinessException.of("creditApp.ownReview", a.getNumber());
        }
        Optional<AppUserPrincipal> user = AppUserPrincipal.current();
        a.setReviewedBy(user.map(AppUserPrincipal::getUsername).orElse("system"));
        a.setReviewedById(user.map(AppUserPrincipal::getId).orElse(null));
        a.setReviewedAt(LocalDateTime.now(clock));
        a.setReviewNote(PartyRules.clean(note));
        a.setStatus(CreditApplicationStatus.PENDING_APPROVAL);
        tell(a);
        return a;
    }

    /**
     * Another person approves it within the Delegation of Authority (by the limit asked; not who asked, assessed or reviewed
     * it): the customer gets the terms, and its next review date. The customer first, then the application.
     */
    @Transactional
    public CreditApplication approve(UUID id, String note) {
        CreditApplication found = findDetailed(id);
        customerRepo.lockById(found.getCustomer().getId());
        CreditApplication a = lock(id);
        if (a.getStatus() != CreditApplicationStatus.PENDING_APPROVAL) {
            throw BusinessException.of(a.isWaiting() ? "creditApp.notYetForApproval" : "creditApp.notPending", a.getNumber());
        }
        if (isRequester(a) || isAssessor(a) || isReviewer(a)) {
            throw BusinessException.of("creditApp.ownApproval", a.getNumber());
        }
        String clean = PartyRules.clean(note);
        authority.require(AuthorityDecision.CREDIT_LIMIT, a.getCreditLimit(), clean);
        LocalDate today = LocalDate.now(clock);
        boolean credit = a.getCreditLimit().signum() > 0 || a.getPaymentTermsDays() > 0;
        customerService.applyApprovedTerms(a.getCustomer().getId(), a.getCreditLimit(), a.getPaymentTermsDays(),
                a.getPriceList() == null ? null : a.getPriceList().getId(), today, credit ? today.plusMonths(reviewMonths()) : null);
        Optional<AppUserPrincipal> user = AppUserPrincipal.current();
        a.setDecidedBy(user.map(AppUserPrincipal::getUsername).orElse("system"));
        a.setDecidedById(user.map(AppUserPrincipal::getId).orElse(null));
        a.setDecidedAt(LocalDateTime.now(clock));
        a.setDecisionNote(clean);
        a.setStatus(CreditApplicationStatus.APPROVED);
        alertRepo.findById(ALERT_PREFIX + a.getCustomer().getId()).filter(s -> s.getClearedAt() == null)
                .ifPresent(s -> s.setClearedAt(LocalDateTime.now(clock)));
        notifier.user(a.getRequestedById(), NotificationKind.DECISION, "notify.creditApp.approved", "notify.decidedBy",
                "/credit-applications/" + a.getId(), a.getNumber(), a.getDecidedBy());
        return a;
    }

    /** An assessor, reviewer or approver rejects it (not who asked), with a reason: the terms stay as they are. */
    @Transactional
    public CreditApplication reject(UUID id, String reason) {
        CreditApplication a = lock(id);
        if (!a.isWaiting()) {
            throw BusinessException.of("creditApp.notPending", a.getNumber());
        }
        if (isRequester(a)) {
            throw BusinessException.of("creditApp.ownReject", a.getNumber());
        }
        close(a, CreditApplicationStatus.REJECTED, reason);
        notifier.user(a.getRequestedById(), NotificationKind.DECISION, "notify.creditApp.rejected", "notify.decidedBy",
                "/credit-applications/" + a.getId(), a.getNumber(), a.getDecidedBy());
        return a;
    }

    /** Whoever asked takes it back, with a reason. */
    @Transactional
    public CreditApplication withdraw(UUID id, String reason) {
        CreditApplication a = lock(id);
        if (!a.isWaiting()) {
            throw BusinessException.of("creditApp.notPending", a.getNumber());
        }
        if (!isRequester(a)) {
            throw BusinessException.of("creditApp.notYours", a.getNumber());
        }
        close(a, CreditApplicationStatus.WITHDRAWN, reason);
        return a;
    }

    // ---------------------------------------------------------------- reviews (F-02 C)

    public int reviewMonths() {
        return settingService.getInt(SettingKey.CREDIT_REVIEW_MONTHS);
    }

    public int reviewOverdueDays() {
        return settingService.getInt(SettingKey.CREDIT_REVIEW_OVERDUE_DAYS);
    }

    /** Active account customers with credit whose limit is due for review, the most urgent first. */
    public List<ReviewDue> reviewsDue() {
        LocalDate today = LocalDate.now(clock);
        int overdueDays = reviewOverdueDays();
        List<ReviewDue> due = new ArrayList<>();
        for (Customer c : customerRepo.findAll()) {
            if (!c.isEnabled() || !c.getType().isCreditAllowed() || !c.hasCredit()) {
                continue;
            }
            CustomerAgeing.Overdue overdue = ageing.overdue(c, 0);
            DueReason reason = overdue.any() && overdue.oldestDays() >= overdueDays ? DueReason.OVERDUE
                    : c.getCreditReviewDue() == null ? DueReason.NEVER
                    : !c.getCreditReviewDue().isAfter(today) ? DueReason.DATE : null;
            if (reason != null) {
                due.add(new ReviewDue(c, reason, c.getCreditReviewDue(), overdue.any() ? overdue.oldestDays() : 0,
                        waitingOf(c.getId()).orElse(null)));
            }
        }
        due.sort(Comparator.comparing((ReviewDue d) -> d.reason().ordinal() == DueReason.OVERDUE.ordinal() ? 0 : 1)
                .thenComparing(d -> -d.oldestOverdueDays()).thenComparing(d -> d.customer().getName()));
        return due;
    }

    /** Tells Credit Control once per customer newly due for review, and clears the alerts of those reviewed; how many were raised. */
    @Scheduled(fixedDelayString = "${app.alerts.credit-review-every:PT6H}", initialDelayString = "${app.alerts.first-check:PT1M}")
    @Transactional
    public int checkReviews() {
        LocalDateTime now = LocalDateTime.now(clock);
        Map<String, AlertState> raised = new HashMap<>();
        alertRepo.findByKeyStartingWithAndClearedAtIsNull(ALERT_PREFIX).forEach(s -> raised.put(s.getKey(), s));
        int count = 0;
        for (ReviewDue d : reviewsDue()) {
            String key = ALERT_PREFIX + d.customer().getId();
            if (raised.remove(key) != null) {
                continue;                                        // already told
            }
            AlertState state = alertRepo.findById(key).orElseGet(AlertState::new);
            state.setKey(key);
            state.setRaisedAt(now);
            state.setClearedAt(null);
            alertRepo.save(state);
            notifier.holders("ALERT_CREDIT_REVIEW", null, NotificationKind.CREDIT, "notify.creditReview.title",
                    "notify.creditReview." + d.reason(), "/customers/" + d.customer().getId(), d.customer().getName(),
                    d.customer().getCode(), String.valueOf(d.oldestOverdueDays()));
            count++;
        }
        raised.values().forEach(s -> s.setClearedAt(now));     // reviewed, or no longer with credit
        return count;
    }

    // ---------------------------------------------------------------- helpers

    private void close(CreditApplication a, CreditApplicationStatus status, String reason) {
        String why = PartyRules.clean(reason);
        if (why == null || why.length() > 255) {
            throw BusinessException.of("po.reason.required");
        }
        Optional<AppUserPrincipal> user = AppUserPrincipal.current();
        a.setDecidedBy(user.map(AppUserPrincipal::getUsername).orElse("system"));
        a.setDecidedById(user.map(AppUserPrincipal::getId).orElse(null));
        a.setDecidedAt(LocalDateTime.now(clock));
        a.setDecisionNote(why);
        a.setStatus(status);
    }

    /** The next people are told it waits for them: Credit Control, the Director of Finance, or the approvers. */
    private void tell(CreditApplication a) {
        String permission = switch (a.getStatus()) {
            case PENDING_ASSESSMENT -> "ASSESS_CREDIT";
            case PENDING_REVIEW -> "REVIEW_CREDIT_APPLICATION";
            default -> "APPROVE_CREDIT_APPLICATION";
        };
        notifier.holders(permission, a.getRequestedById(), NotificationKind.APPROVAL, "notify.creditApp." + a.getStatus(),
                "notify.creditApp.waitingText", "/credit-applications/" + a.getId(), a.getNumber(), a.getCustomer().getName(),
                a.getCreditLimit().setScale(0, RoundingMode.HALF_UP).toPlainString(), String.valueOf(a.getPaymentTermsDays()));
    }

    private CreditApplication lock(UUID id) {
        repo.lockById(id).orElseThrow(() -> new NotFoundException("CreditApplication", id));
        return findDetailed(id);
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }
}
