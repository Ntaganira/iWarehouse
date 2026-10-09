package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.SaleApproval;
import com.ntaganira.heritier.iWarehouse.entity.SalesInvoice;
import com.ntaganira.heritier.iWarehouse.entity.SalesInvoiceLine;
import com.ntaganira.heritier.iWarehouse.enums.SaleApprovalStatus;
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
 * - Desc      : Deciding the counter's approval requests (POS-05, POS-06). Another person than the requester
 *               approves (a price change then applies to its line, with the list price and the reason kept; a
 *               credit lets the sale be paid on credit up to the amount) or rejects with a reason. Approving locks
 *               the till, then the request (the cashier's order), so it never crosses the sale being changed or paid.
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

    public SaleApprovalService(SaleApprovalRepository repo, SalesInvoiceRepository invoiceRepo, TillSessionRepository tillRepo,
                               SalesService salesService, Clock clock) {
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
        return repo.countByStatus(SaleApprovalStatus.PENDING);
    }

    /** A price change's line as it is now (the sale's or the invoice's), if it is still there. */
    public Optional<SalesInvoiceLine> lineOf(SaleApproval approval) {
        if (approval.getLineId() == null) {
            return Optional.empty();
        }
        return invoiceRepo.findDetailedById(approval.getInvoice().getId())
                .flatMap(i -> i.getLines().stream().filter(l -> l.getId().equals(approval.getLineId())).findFirst());
    }

    /** Whether the signed-in user asked for it (they cannot decide it). */
    public static boolean isRequester(SaleApproval approval) {
        Optional<AppUserPrincipal> user = AppUserPrincipal.current();
        if (user.isPresent() && approval.getRequestedById() != null) {
            return approval.getRequestedById().equals(user.get().getId());
        }
        return approval.getRequestedBy().equals(user.map(AppUserPrincipal::getUsername).orElse("system"));
    }

    /**
     * Another person approves. A price change applies to its line (still on the sale and still at the list price it was
     * asked against); an earlier approved change of that line, or an earlier approved credit, is replaced.
     */
    @Transactional
    public SaleApproval approve(UUID id, String note) {
        // The till first, then the request: the order the cashier takes them in
        tillRepo.lockById(repo.tillOf(id).orElseThrow(() -> new NotFoundException("SaleApproval", id)));
        SaleApproval approval = lockPending(id);
        if (isRequester(approval)) {
            throw BusinessException.of("saleApproval.own", approval.getNumber());
        }
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
            others.stream().filter(a -> a.isPrice() && line.getId().equals(a.getLineId()))
                    .forEach(a -> salesService.withdraw(a, "Replaced by " + approval.getNumber()));
            salesService.setPrice(line, approval.getRequestedPrice(), approval.getListPrice(), approval.getReason());
        } else {
            others.stream().filter(SaleApproval::isCredit).forEach(a -> salesService.withdraw(a, "Replaced by " + approval.getNumber()));
        }
        decide(approval, SaleApprovalStatus.APPROVED, PartyRules.clean(note));
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
