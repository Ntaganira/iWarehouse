package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.dto.RequisitionDto;
import com.ntaganira.heritier.iWarehouse.entity.*;
import com.ntaganira.heritier.iWarehouse.enums.*;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.CostCentreRepository;
import com.ntaganira.heritier.iWarehouse.repository.PurchaseRequisitionRepository;
import com.ntaganira.heritier.iWarehouse.repository.SupplierRepository;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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
 * - File      : RequisitionService.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : Purchase requisitions (CMP 06, PRF-WH-2026-000001): a department asks for what it needs with its estimated
 *               cost and budget code (PERM_CREATE_REQUISITION). Submitting checks the quotes its value calls for
 *               (Expenditure.sourcing: one, three written, an RFQ or a tender with its reference), each kept as a document
 *               when the Settings ask, and the chosen one (explained when it is not the cheapest). The head of department
 *               approves it or rejects it with a reason (PERM_APPROVE_REQUISITION, never who asked); who asked cancels it.
 *               An order or a purchase bill then uses one approved requisition (use, MANDATORY): the chosen quote's
 *               supplier, the amount within the chosen quote and the Settings' tolerance; it is closed, and opened again if
 *               the order or bill is cancelled or rejected (release).
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class RequisitionService {

    private final PurchaseRequisitionRepository repo;
    private final CostCentreRepository costCentreRepo;
    private final SupplierRepository supplierRepo;
    private final AttachmentService attachments;
    private final DocumentNumberService numbers;
    private final SettingService settings;
    private final Notifier notifier;
    private final DelegationOfAuthority authority;
    private final Clock clock;

    public RequisitionService(PurchaseRequisitionRepository repo, CostCentreRepository costCentreRepo, SupplierRepository supplierRepo,
                              AttachmentService attachments, DocumentNumberService numbers, SettingService settings, Notifier notifier,
                              DelegationOfAuthority authority, Clock clock) {
        this.repo = repo;
        this.costCentreRepo = costCentreRepo;
        this.supplierRepo = supplierRepo;
        this.attachments = attachments;
        this.numbers = numbers;
        this.settings = settings;
        this.notifier = notifier;
        this.authority = authority;
        this.clock = clock;
    }

    /** Whether the signed-in user may decide it as its department's head: its manager when one is named, or the COO and above. */
    public boolean isHead(PurchaseRequisition r) {
        List<Long> managers = r.getCostCentre().getManagerUserId() == null ? List.of() : List.of(r.getCostCentre().getManagerUserId());
        return Expenditure.mayActAsHead(managers, AppUserPrincipal.current().map(AppUserPrincipal::getId).orElse(null),
                authority.currentLevel().orElse(null));
    }

    // ---------------------------------------------------------------- reading

    public Page<PurchaseRequisition> findPage(String search, String status, int page, int size) {
        Specification<PurchaseRequisition> spec = (root, query, cb) -> {
            Predicate p = cb.conjunction();
            if (StringUtils.hasText(search)) {
                String term = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                p = cb.and(p, cb.or(cb.like(cb.lower(root.get("number")), term), cb.like(cb.lower(root.get("title")), term),
                        cb.like(cb.lower(root.get("budgetCode")), term), cb.like(cb.lower(root.get("requestedBy")), term)));
            }
            if ("waiting".equals(status)) {
                p = cb.and(p, cb.equal(root.get("status"), RequisitionStatus.SUBMITTED));
            } else if (StringUtils.hasText(status)) {
                try {
                    p = cb.and(p, cb.equal(root.get("status"), RequisitionStatus.valueOf(status)));
                } catch (IllegalArgumentException e) {
                    // unknown: no filter
                }
            }
            return p;
        };
        return repo.findAll(spec, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "requestedAt")));
    }

    public PurchaseRequisition findDetailed(UUID id) {
        return repo.findDetailedById(id).orElseThrow(() -> new NotFoundException("PurchaseRequisition", id));
    }

    public long waitingCount() {
        return repo.countByStatus(RequisitionStatus.SUBMITTED);
    }

    /** Approved requisitions no order or bill used yet (the forms pick one). */
    public List<PurchaseRequisition> approvedUnused() {
        return repo.findByStatusOrderByNumberAsc(RequisitionStatus.APPROVED);
    }

    public List<CostCentre> costCentres() {
        return costCentreRepo.findByEnabledTrueOrderByCodeAsc();
    }

    public List<Supplier> suppliers() {
        return supplierRepo.findAll(Sort.by("name")).stream().filter(Supplier::isEnabled).toList();
    }

    public Expenditure.Tiers tiers() {
        return new Expenditure.Tiers(settings.getDecimal(SettingKey.EXPENSE_QUOTES_THREE_FROM), settings.getDecimal(SettingKey.EXPENSE_RFQ_FROM),
                settings.getDecimal(SettingKey.EXPENSE_TENDER_FROM));
    }

    /** The sourcing its estimated total calls for. */
    public SourcingMethod sourcingOf(PurchaseRequisition r) {
        return Expenditure.sourcing(r.getEstimatedTotal(), tiers());
    }

    /** Whether an order or a purchase bill of this RWF amount needs a requisition (CMP 06). */
    public boolean isRequired(BigDecimal amountRwf) {
        return Boolean.parseBoolean(settings.get(SettingKey.EXPENSE_REQUISITION_REQUIRED))
                && amountRwf.compareTo(settings.getDecimal(SettingKey.EXPENSE_REQUISITION_FROM)) >= 0;
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    public boolean isRequester(PurchaseRequisition r) {
        return AppUserPrincipal.current().map(u -> u.getId().equals(r.getRequestedById())).orElse(false);
    }

    /** The form of a requisition (a new one: today's department left to choose). */
    public RequisitionDto formOf(PurchaseRequisition r) {
        RequisitionDto dto = new RequisitionDto();
        dto.setCostCentreId(r.getCostCentre().getId());
        dto.setTitle(r.getTitle());
        dto.setJustification(r.getJustification());
        dto.setNeededBy(r.getNeededBy());
        dto.setBudgetCode(r.getBudgetCode());
        dto.setBudgeted(r.isBudgeted());
        dto.setNature(r.getNature());
        dto.setSourcingRef(r.getSourcingRef());
        dto.setChosenReason(r.getChosenReason());
        for (PurchaseRequisitionLine l : r.getLines()) {
            RequisitionDto.Line line = new RequisitionDto.Line();
            line.setItem(l.getItem());
            line.setQuantity(l.getQuantity());
            line.setUnit(l.getUnit());
            line.setUnitCost(l.getUnitCost());
            dto.getLines().add(line);
        }
        int i = 0;
        for (RequisitionQuote q : r.getQuotes()) {
            RequisitionDto.Quote quote = new RequisitionDto.Quote();
            quote.setSupplierId(q.getSupplier() == null ? null : q.getSupplier().getId());
            quote.setSupplierName(q.getSupplier() == null ? q.getSupplierName() : null);
            quote.setQuoteRef(q.getQuoteRef());
            quote.setQuoteDate(q.getQuoteDate());
            quote.setAmount(q.getAmount());
            quote.setNote(q.getNote());
            if (q.getId().equals(r.getChosenQuoteId())) {
                dto.setChosenQuote(i);
            }
            dto.getQuotes().add(quote);
            i++;
        }
        return dto;
    }

    // ---------------------------------------------------------------- the draft

    /** Saves a draft: a new one, or the requester's own draft changed. */
    @Transactional
    public PurchaseRequisition save(UUID id, RequisitionDto dto) {
        PurchaseRequisition r;
        if (id == null) {
            Optional<AppUserPrincipal> user = AppUserPrincipal.current();
            r = new PurchaseRequisition();
            r.setNumber(numbers.next(DocumentType.PURCHASE_REQUISITION));
            r.setRequestedBy(user.map(AppUserPrincipal::getUsername).orElse("system"));
            r.setRequestedById(user.map(AppUserPrincipal::getId).orElse(null));
            r.setRequestedAt(LocalDateTime.now(clock));
        } else {
            r = lock(id);
            if (!r.isDraft()) {
                throw BusinessException.of("requisition.notDraft", r.getNumber());
            }
            if (!isRequester(r)) {
                throw BusinessException.of("requisition.notYours", r.getNumber(), r.getRequestedBy());
            }
        }
        CostCentre centre = costCentreRepo.findById(dto.getCostCentreId())
                .orElseThrow(() -> BusinessException.onField("costCentreId", "requisition.costCentre.required"));
        if (!centre.isEnabled()) {
            throw BusinessException.onField("costCentreId", "requisition.costCentre.disabled", centre.getCode());
        }
        if (dto.getLines().isEmpty()) {
            throw BusinessException.onField("lines", "requisition.lines.required");
        }
        if (dto.getNeededBy() != null && dto.getNeededBy().isBefore(today())) {
            throw BusinessException.onField("neededBy", "requisition.neededBy.past");
        }
        r.setCostCentre(centre);
        r.setTitle(dto.getTitle().trim());
        r.setJustification(PartyRules.clean(dto.getJustification()));
        r.setNeededBy(dto.getNeededBy());
        r.setBudgetCode(clean(dto.getBudgetCode()) == null ? null : clean(dto.getBudgetCode()).toUpperCase(Locale.ROOT));
        r.setBudgeted(dto.isBudgeted());
        r.setNature(dto.getNature());
        r.setSourcingRef(clean(dto.getSourcingRef()));
        r.setChosenReason(PartyRules.clean(dto.getChosenReason()));

        r.getLines().clear();
        BigDecimal total = BigDecimal.ZERO;
        int no = 0;
        for (RequisitionDto.Line l : dto.getLines()) {
            PurchaseRequisitionLine line = new PurchaseRequisitionLine();
            line.setRequisition(r);
            line.setLineNo(++no);
            line.setItem(l.getItem().trim());
            line.setQuantity(l.getQuantity());
            line.setUnit(clean(l.getUnit()));
            line.setUnitCost(l.getUnitCost().setScale(2, RoundingMode.HALF_UP));
            line.setAmount(l.getQuantity().multiply(l.getUnitCost()).setScale(2, RoundingMode.HALF_UP));
            total = total.add(line.getAmount());
            r.getLines().add(line);
        }
        r.setEstimatedTotal(total);
        r.setSourcing(Expenditure.sourcing(total, tiers()));

        r.setChosenQuoteId(null);
        r.getQuotes().clear();
        if (id == null) {
            repo.save(r);                                    // the quotes need the requisition's id first
        }
        repo.flush();
        int i = 0;
        RequisitionQuote chosen = null;
        for (RequisitionDto.Quote q : dto.getQuotes()) {
            RequisitionQuote quote = new RequisitionQuote();
            quote.setRequisition(r);
            if (q.getSupplierId() != null) {
                Supplier supplier = supplierRepo.findById(q.getSupplierId()).orElseThrow(() -> new NotFoundException("Supplier", q.getSupplierId()));
                quote.setSupplier(supplier);
                quote.setSupplierName(supplier.getName());
            } else {
                String name = clean(q.getSupplierName());
                if (name == null) {
                    throw BusinessException.onField("quotes[" + i + "].supplierName", "requisition.quote.supplier.required");
                }
                quote.setSupplierName(name);
            }
            quote.setQuoteRef(clean(q.getQuoteRef()));
            quote.setQuoteDate(q.getQuoteDate());
            quote.setAmount(q.getAmount().setScale(2, RoundingMode.HALF_UP));
            quote.setNote(clean(q.getNote()));
            r.getQuotes().add(quote);
            if (dto.getChosenQuote() != null && dto.getChosenQuote() == i) {
                chosen = quote;
            }
            i++;
        }
        repo.flush();                                        // r is managed: persisting on flush keeps our quote instances (merge would copy them)
        if (chosen != null) {
            r.setChosenQuoteId(chosen.getId());
        }
        return r;
    }

    // ---------------------------------------------------------------- deciding

    /** Who asked submits it: the quotes its value needs, their documents, the chosen one; the approvers are told. */
    @Transactional
    public PurchaseRequisition submit(UUID id) {
        PurchaseRequisition r = lock(id);
        if (!r.isDraft()) {
            throw BusinessException.of("requisition.notDraft", r.getNumber());
        }
        if (!isRequester(r)) {
            throw BusinessException.of("requisition.notYours", r.getNumber(), r.getRequestedBy());
        }
        if (r.getEstimatedTotal().signum() <= 0) {
            throw BusinessException.of("requisition.total.zero", r.getNumber());
        }
        SourcingMethod sourcing = Expenditure.sourcing(r.getEstimatedTotal(), tiers());
        r.setSourcing(sourcing);
        if (r.getQuotes().size() < sourcing.quotesNeeded()) {
            throw BusinessException.of("requisition.quotes.tooFew", r.getNumber(), sourcing.quotesNeeded(), r.getQuotes().size());
        }
        if (sourcing.needsReference() && r.getSourcingRef() == null) {
            throw BusinessException.of("requisition.sourcingRef.required", r.getNumber());
        }
        if (Boolean.parseBoolean(settings.get(SettingKey.EXPENSE_QUOTE_DOCUMENTS))
                && attachments.count(AttachmentOwner.PURCHASE_REQUISITION, r.getId()) < sourcing.quotesNeeded()) {
            throw BusinessException.of("requisition.quotes.documents", r.getNumber(), sourcing.quotesNeeded());
        }
        RequisitionQuote chosen = r.getChosenQuote();
        if (chosen == null) {
            throw BusinessException.of("requisition.quote.chosen.required", r.getNumber());
        }
        RequisitionQuote cheapest = Expenditure.cheapest(r.getQuotes(), RequisitionQuote::getAmount).orElse(chosen);
        if (chosen.getAmount().compareTo(cheapest.getAmount()) > 0 && r.getChosenReason() == null) {
            throw BusinessException.of("requisition.quote.reason", r.getNumber(), cheapest.getSupplierName());
        }
        r.setSubmittedAt(LocalDateTime.now(clock));
        r.setStatus(RequisitionStatus.SUBMITTED);
        String link = "/requisitions/" + r.getId();
        if (r.getCostCentre().getManagerUserId() != null && !r.getCostCentre().getManagerUserId().equals(r.getRequestedById())) {
            notifier.user(r.getCostCentre().getManagerUserId(), NotificationKind.APPROVAL, "notify.requisition.waiting",
                    "notify.requisition.waitingText", link, r.getNumber(), r.getRequestedBy(), r.getCostCentre().getCode(), r.getTitle());
        } else {
            notifier.holders("APPROVE_REQUISITION", r.getRequestedById(), NotificationKind.APPROVAL, "notify.requisition.waiting",
                    "notify.requisition.waitingText", link, r.getNumber(), r.getRequestedBy(), r.getCostCentre().getCode(), r.getTitle());
        }
        return r;
    }

    /** The head of department approves it: never who asked. */
    @Transactional
    public PurchaseRequisition approve(UUID id, String note) {
        PurchaseRequisition r = lock(id);
        requireSubmitted(r);
        if (isRequester(r)) {
            throw BusinessException.of("requisition.ownDecision", r.getNumber());
        }
        requireHead(r);
        String clean = PartyRules.clean(note);
        if (clean != null && clean.length() > 500) {
            throw BusinessException.onField("note", "requisition.note.size");
        }
        decide(r, RequisitionStatus.APPROVED, clean);
        notifier.user(r.getRequestedById(), NotificationKind.DECISION, "notify.requisition.approved", "notify.decidedBy",
                "/requisitions/" + r.getId(), r.getNumber(), r.getDecidedBy());
        return r;
    }

    @Transactional
    public PurchaseRequisition reject(UUID id, String reason) {
        PurchaseRequisition r = lock(id);
        requireSubmitted(r);
        if (isRequester(r)) {
            throw BusinessException.of("requisition.ownDecision", r.getNumber());
        }
        requireHead(r);
        decide(r, RequisitionStatus.REJECTED, reason(reason));
        notifier.user(r.getRequestedById(), NotificationKind.DECISION, "notify.requisition.rejected", "notify.rejectedBy",
                "/requisitions/" + r.getId(), r.getNumber(), r.getDecidedBy(), r.getDecisionNote());
        return r;
    }

    /** Who asked cancels it, with a reason, before an order or a bill uses it. */
    @Transactional
    public PurchaseRequisition cancel(UUID id, String reason) {
        PurchaseRequisition r = lock(id);
        if (r.getStatus() != RequisitionStatus.DRAFT && r.getStatus() != RequisitionStatus.SUBMITTED && r.getStatus() != RequisitionStatus.APPROVED) {
            throw BusinessException.of("requisition.notOpen", r.getNumber());
        }
        if (!isRequester(r)) {
            throw BusinessException.of("requisition.notYours", r.getNumber(), r.getRequestedBy());
        }
        r.setDecidedBy(AppUserPrincipal.currentUsername());
        r.setDecidedById(AppUserPrincipal.current().map(AppUserPrincipal::getId).orElse(null));
        r.setDecisionNote(reason(reason));
        r.setStatus(RequisitionStatus.CANCELLED);
        return r;
    }

    private void requireHead(PurchaseRequisition r) {
        if (!isHead(r)) {
            throw BusinessException.of("requisition.notHead", r.getNumber(), r.getCostCentre().getCode(), r.getCostCentre().getManagerName());
        }
    }

    // ---------------------------------------------------------------- an order or a bill uses one

    /**
     * An order or a purchase bill uses an approved requisition (CMP 06): the supplier the chosen quote named, the amount (RWF)
     * within the chosen quote and the Settings' tolerance. Closes it, naming the document.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PurchaseRequisition use(UUID id, String documentNumber, Supplier supplier, BigDecimal amountRwf, String field) {
        if (id == null) {
            throw field == null ? BusinessException.of("requisition.required", documentNumber)
                    : BusinessException.onField(field, "requisition.required", documentNumber);
        }
        PurchaseRequisition r = lock(id);
        if (!r.isApproved()) {
            throw refusal(field, "requisition.notApproved", r.getNumber());
        }
        RequisitionQuote chosen = r.getChosenQuote();
        if (chosen != null && chosen.getSupplier() != null && supplier != null && !chosen.getSupplier().getId().equals(supplier.getId())) {
            throw refusal(field, "requisition.otherSupplier", r.getNumber(), chosen.getSupplierName());
        }
        BigDecimal reference = chosen != null ? chosen.getAmount() : r.getEstimatedTotal();
        BigDecimal tolerance = settings.getDecimal(SettingKey.EXPENSE_REQUISITION_TOLERANCE_PERCENT);
        if (!Expenditure.withinRequisition(amountRwf, reference, tolerance)) {
            throw refusal(field, "requisition.overAmount", r.getNumber(), reference.setScale(0, RoundingMode.HALF_UP).toPlainString(),
                    tolerance.stripTrailingZeros().toPlainString());
        }
        r.setUsedBy(documentNumber);
        r.setUsedAt(LocalDateTime.now(clock));
        r.setStatus(RequisitionStatus.CLOSED);
        return r;
    }

    /** The order or bill that used it was cancelled, rejected or sent back: it may be used again. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(UUID id, String documentNumber) {
        if (id == null) {
            return;
        }
        PurchaseRequisition r = lock(id);
        if (r.getStatus() == RequisitionStatus.CLOSED && documentNumber.equals(r.getUsedBy())) {
            r.setUsedBy(null);
            r.setUsedAt(null);
            r.setStatus(RequisitionStatus.APPROVED);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static BusinessException refusal(String field, String key, Object... args) {
        return field == null ? BusinessException.of(key, args) : BusinessException.onField(field, key, args);
    }

    private void decide(PurchaseRequisition r, RequisitionStatus status, String note) {
        r.setDecidedBy(AppUserPrincipal.currentUsername());
        r.setDecidedById(AppUserPrincipal.current().map(AppUserPrincipal::getId).orElse(null));
        r.setDecidedAt(LocalDateTime.now(clock));
        r.setDecisionNote(note);
        r.setStatus(status);
    }

    private static void requireSubmitted(PurchaseRequisition r) {
        if (!r.isSubmitted()) {
            throw BusinessException.of("requisition.notSubmitted", r.getNumber());
        }
    }

    private static String reason(String reason) {
        String clean = PartyRules.clean(reason);
        if (clean == null || clean.length() > 500) {
            throw BusinessException.onField("reason", "requisition.reason.required");
        }
        return clean;
    }

    private PurchaseRequisition lock(UUID id) {
        repo.lockById(id).orElseThrow(() -> new NotFoundException("PurchaseRequisition", id));
        return findDetailed(id);
    }

    private static String clean(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
