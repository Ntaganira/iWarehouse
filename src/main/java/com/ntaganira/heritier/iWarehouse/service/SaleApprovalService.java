package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.SaleApproval;
import com.ntaganira.heritier.iWarehouse.entity.SalesInvoice;
import com.ntaganira.heritier.iWarehouse.entity.SalesInvoiceLine;
import com.ntaganira.heritier.iWarehouse.enums.AuthorityDecision;
import com.ntaganira.heritier.iWarehouse.enums.NotificationKind;
import com.ntaganira.heritier.iWarehouse.enums.SaleApprovalStatus;
import com.ntaganira.heritier.iWarehouse.enums.SettingKey;
import com.ntaganira.heritier.iWarehouse.enums.SalesInvoiceStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.SaleApprovalRepository;
import com.ntaganira.heritier.iWarehouse.repository.SalesInvoiceRepository;
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
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : SaleApprovalService.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : Deciding the counter's approval requests (POS-05, POS-06, F-03). Another person than the requester
 *               approves (a price change then applies to its line, with the list price and the reason kept; a
 *               credit lets the sale be paid on credit up to the amount; longer terms go on the invoice) or rejects with
 *               a reason. The approver's office must reach what the Delegation of Authority sets for what is approved;
 *               a price or credit may be approved in part. When Settings ask for it, someone else recommends first
 *               (the Director of Commercial), and the recommender does not decide. Approving locks the till, then the
 *               request (the cashier's order), so it never crosses the sale being changed or paid.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class SaleApprovalService {

    private final SaleApprovalRepository repo;
    private final SalesInvoiceRepository invoiceRepo;
    private final TillSessionRepository tillRepo;
    private final SalesService salesService;
    private final Clock clock;
    private final DelegationOfAuthority authority;
    private final SettingService settingService;
    private final Notifier notifier;

    public SaleApprovalService(SaleApprovalRepository repo, SalesInvoiceRepository invoiceRepo, TillSessionRepository tillRepo,
                               SalesService salesService, Clock clock, DelegationOfAuthority authority, SettingService settingService,
                               Notifier notifier) {
        this.authority = authority;
        this.settingService = settingService;
        this.notifier = notifier;
        this.repo = repo;
        this.invoiceRepo = invoiceRepo;
        this.tillRepo = tillRepo;
        this.salesService = salesService;
        this.clock = clock;
    }

    /** Requests, newest first; filtered by status and by number, customer, what or who asked. */
    public Page<SaleApproval> findPage(String status, String search, int page, int size) {
        Specification<SaleApproval> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (StringUtils.hasText(status)) {
                try {
                    p.add(cb.equal(root.get("status"), SaleApprovalStatus.valueOf(status)));
                } catch (IllegalArgumentException ignored) {
                    // an unknown status filters nothing
                }
            }
            if (StringUtils.hasText(search)) {
                String term = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                p.add(cb.or(
                        cb.like(cb.lower(root.get("number")), term),
                        cb.like(cb.lower(root.get("subject")), term),
                        cb.like(cb.lower(root.get("customer").get("name")), term),
                        cb.like(cb.lower(root.get("requestedBy")), term)));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
        Sort sort = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("number"));
        return repo.findAll(spec, PageRequest.of(page, size, sort));
    }

    public SaleApproval findDetailed(UUID id) {
        return repo.findDetailedById(id).orElseThrow(() -> new NotFoundException("SaleApproval", id));
    }

    public long pendingCount() {
        return repo.countByStatusIn(SaleApprovalStatus.WAITING);
    }

    /** Whether Settings ask for a recommendation before a decision (F-03). */
    public boolean recommendationRequired() {
        return settingService.getBoolean(SettingKey.APPROVAL_SALE_RECOMMEND);
    }

    /** What the request needs from the Delegation of Authority and whether the signed-in user may decide it, as asked. */
    public DelegationOfAuthority.Check authorityOf(SaleApproval approval) {
        return authority.check(approval.getAuthorityDecision(), approval.getAuthorityAmount());
    }

    /** A price change's line as it is now (the sale's or the invoice's), if it is still there. */
    public Optional<SalesInvoiceLine> lineOf(SaleApproval approval) {
        if (approval.getLineId() == null) {
            return Optional.empty();
        }
        return invoiceRepo.findDetailedById(approval.getInvoice().getId())
                .flatMap(i -> i.getLines().stream().filter(l -> l.getId().equals(approval.getLineId())).findFirst());
    }

    /** Whether the signed-in user recommended it (they cannot decide it too). */
    public static boolean isRecommender(SaleApproval approval) {
        Optional<AppUserPrincipal> user = AppUserPrincipal.current();
        return approval.getRecommendedById() != null && user.isPresent() && approval.getRecommendedById().equals(user.get().getId());
    }

    /** Whether the signed-in user asked for it (they cannot decide it). */
    public static boolean isRequester(SaleApproval approval) {
        Optional<AppUserPrincipal> user = AppUserPrincipal.current();
        if (user.isPresent() && approval.getRequestedById() != null) {
            return approval.getRequestedById().equals(user.get().getId());
        }
        return approval.getRequestedBy().equals(user.map(AppUserPrincipal::getUsername).orElse("system"));
    }

    /** Approves it as asked. */
    @Transactional
    public SaleApproval approve(UUID id, String note) {
        return approve(id, note, null);
    }

    /**
     * Another person approves, as asked or in part ({@code inPart}: a price between the one asked and the list price, or
     * less credit than asked). A price change applies to its line (still on the sale and still at the list price it was
     * asked against); an earlier approved change of that line, an earlier approved credit or longer terms are replaced.
     * The approver's office must reach what the Delegation of Authority sets for what is approved.
     */
    @Transactional
    public SaleApproval approve(UUID id, String note, BigDecimal inPart) {
        // The till first, then the request: the order the cashier takes them in
        tillRepo.lockById(repo.tillOf(id).orElseThrow(() -> new NotFoundException("SaleApproval", id)));
        SaleApproval approval = lockPending(id);
        if (isRequester(approval)) {
            throw BusinessException.of("saleApproval.own", approval.getNumber());
        }
        if (recommendationRequired() && !approval.isRecommended()) {
            throw BusinessException.of("saleApproval.notRecommended", approval.getNumber());
        }
        if (isRecommender(approval)) {
            throw BusinessException.of("saleApproval.ownRecommendation", approval.getNumber());
        }
        String clean = PartyRules.clean(note);
        SalesInvoice sale = invoiceRepo.findDetailedById(approval.getInvoice().getId())
                .filter(i -> i.getStatus() == SalesInvoiceStatus.DRAFT)
                .orElseThrow(() -> BusinessException.of("saleApproval.saleGone", approval.getNumber()));
        List<SaleApproval> others = repo.findByInvoice_IdAndStatusIn(sale.getId(), List.of(SaleApprovalStatus.APPROVED));
        if (approval.isPrice()) {
            SalesInvoiceLine line = sale.getLines().stream().filter(l -> l.getId().equals(approval.getLineId())).findFirst()
                    .orElseThrow(() -> BusinessException.of("saleApproval.lineGone", approval.getNumber()));
            if (SalesService.listPriceOf(line).compareTo(approval.getListPrice()) != 0) {
                throw BusinessException.of("saleApproval.priceMoved", approval.getNumber());
            }
            BigDecimal price = approval.getRequestedPrice();
            if (inPart != null) {
                price = inPart.setScale(2, RoundingMode.HALF_UP);
                if (price.compareTo(approval.getRequestedPrice()) < 0 || price.compareTo(approval.getListPrice()) >= 0) {
                    throw BusinessException.onField("inPart", "saleApproval.inPart.price", approval.getRequestedPrice(), approval.getListPrice());
                }
            }
            // What is approved: the discount, or (still below cost) the line's amount at that price
            BigDecimal after = salesService.amountAt(line, price);
            boolean belowCost = approval.isBelowCost() && approval.getCostAmount() != null
                    && Discounts.netOf(after, line.getVatRate()).compareTo(approval.getCostAmount()) < 0;
            authority.require(belowCost ? AuthorityDecision.BELOW_COST : AuthorityDecision.DISCOUNT,
                    belowCost ? after : Discounts.percent(approval.getListPrice(), price), clean);
            others.stream().filter(a -> a.isPrice() && line.getId().equals(a.getLineId()))
                    .forEach(a -> salesService.withdraw(a, "Replaced by " + approval.getNumber()));
            approval.setApprovedPrice(inPart != null ? price : null);
            salesService.setPrice(line, price, approval.getListPrice(), approval.getReason());
        } else if (approval.isCredit()) {
            BigDecimal credit = approval.getCreditAmount();
            if (inPart != null) {
                credit = inPart.setScale(2, RoundingMode.HALF_UP);
                if (credit.signum() <= 0 || credit.compareTo(approval.getCreditAmount()) > 0) {
                    throw BusinessException.onField("inPart", "saleApproval.inPart.credit", approval.getCreditAmount());
                }
            }
            boolean overdue = approval.getOverdueAmount() != null && approval.getOverdueAmount().signum() > 0;
            BigDecimal stake = overdue ? credit : approval.getOwed().add(credit).subtract(approval.getCreditLimit()).max(BigDecimal.ZERO);
            authority.require(AuthorityDecision.CREDIT_EXCEPTION, stake, clean);
            others.stream().filter(SaleApproval::isCredit).forEach(a -> salesService.withdraw(a, "Replaced by " + approval.getNumber()));
            approval.setApprovedCredit(inPart != null ? credit : null);
        } else {
            authority.require(AuthorityDecision.CREDIT_EXCEPTION, approval.getAmountAfter(), clean);
            others.stream().filter(SaleApproval::isTerms).forEach(a -> salesService.withdraw(a, "Replaced by " + approval.getNumber()));
        }
        decide(approval, SaleApprovalStatus.APPROVED, clean);
        return approval;
    }

    /**
     * Someone other than the requester recommends it before it is decided (F-03: the Director of Commercial recommends, the
     * COO decides); the approvers are told it is ready.
     */
    @Transactional
    public SaleApproval recommend(UUID id, String note) {
        tillRepo.lockById(repo.tillOf(id).orElseThrow(() -> new NotFoundException("SaleApproval", id)));
        SaleApproval approval = lockPending(id);
        if (approval.isRecommended()) {
            throw BusinessException.of("saleApproval.alreadyRecommended", approval.getNumber());
        }
        if (isRequester(approval)) {
            throw BusinessException.of("saleApproval.ownRecommend", approval.getNumber());
        }
        Optional<AppUserPrincipal> user = AppUserPrincipal.current();
        approval.setRecommendedBy(user.map(AppUserPrincipal::getUsername).orElse("system"));
        approval.setRecommendedById(user.map(AppUserPrincipal::getId).orElse(null));
        approval.setRecommendedAt(LocalDateTime.now(clock));
        approval.setRecommendationNote(PartyRules.clean(note));
        approval.setStatus(SaleApprovalStatus.RECOMMENDED);
        notifier.holders("APPROVE_SALE", approval.getRecommendedById(), NotificationKind.APPROVAL, "notify.saleApproval.recommended",
                "notify.saleApproval.recommendedText", "/sale-approvals/" + approval.getId(), approval.getNumber(), approval.getRecommendedBy(),
                approval.getSubject());
        return approval;
    }

    /** Another person rejects it, with a reason: the sale stays as it is. */
    @Transactional
    public SaleApproval reject(UUID id, String reason) {
        SaleApproval approval = lockPending(id);
        if (isRequester(approval)) {
            throw BusinessException.of("saleApproval.ownReject", approval.getNumber());
        }
        decide(approval, SaleApprovalStatus.REJECTED, reason.trim());
        return approval;
    }

    private SaleApproval lockPending(UUID id) {
        repo.lockById(id).orElseThrow(() -> new NotFoundException("SaleApproval", id));
        SaleApproval approval = findDetailed(id);
        if (!approval.isPending()) {
            throw BusinessException.of("saleApproval.notPending", approval.getNumber());
        }
        return approval;
    }

    private void decide(SaleApproval approval, SaleApprovalStatus status, String note) {
        approval.setStatus(status);
        approval.setDecidedBy(AppUserPrincipal.current().map(AppUserPrincipal::getUsername).orElse("system"));
        approval.setDecidedAt(LocalDateTime.now(clock));
        approval.setDecisionNote(note);
    }
}
