package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.dto.ExpenseBillDto;
import com.ntaganira.heritier.iWarehouse.entity.*;
import com.ntaganira.heritier.iWarehouse.enums.*;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.*;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import jakarta.persistence.criteria.JoinType;
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
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : ExpenseBillService.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : Expense bills (CMP 01, 05, 07; EXP-WH-2026-000001): a supplier's invoice for utilities, rent, services, fees,
 *               repairs, transport or a capital item, entered by Finance (PERM_CREATE_EXPENSE) from an approved RWF
 *               supplier, once per supplier invoice, dated up to today in an open month, its lines by category, cost centre
 *               and location with at most 18 % VAT, all operating or all capital. Submitting needs the supplier's invoice
 *               as a document (when the Settings ask) and, for a purchase, an approved requisition it uses
 *               (RequisitionService.use). Then the head of department recommends it (when the Settings ask), Finance
 *               reviews it from the Settings' amount, and another person approves it within the expenditure matrix
 *               (Expenditure.decision: an emergency, unbudgeted, capital or operating; DelegationOfAuthority), never who
 *               entered, recommended or reviewed it. Approving posts it (PostingService.expenseBill: Dr the categories /
 *               Cr Payable naming the supplier, paid as a supplier payment). An emergency the MD approved is ratified by
 *               the Board (PERM_RATIFY_EXPENSE with the Board's office); not ratified within the Settings' days, ALERT_EXPENSE
 *               holders are told once. Rejected or cancelled with a reason (the requisition may be used again); a posted
 *               bill is reversed with a reason, never edited.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class ExpenseBillService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    static final String ALERT_PREFIX = "EXPENSE_RATIFY:";
    private static final List<ExpenseBillStatus> WAITING = List.of(ExpenseBillStatus.SUBMITTED, ExpenseBillStatus.RECOMMENDED,
            ExpenseBillStatus.REVIEWED);

    private final ExpenseBillRepository repo;
    private final SupplierRepository supplierRepo;
    private final CostCentreRepository costCentreRepo;
    private final CurrencyRepository currencyRepo;
    private final AlertStateRepository alertRepo;
    private final RequisitionService requisitions;
    private final BudgetService budgets;
    private final FixedAssetService fixedAssets;
    private final AttachmentService attachments;
    private final StockService stockService;
    private final PostingService postingService;
    private final JournalService journalService;
    private final DocumentNumberService numbers;
    private final DelegationOfAuthority authority;
    private final PeriodLock periods;
    private final SettingService settings;
    private final Notifier notifier;
    private final Clock clock;

    public ExpenseBillService(ExpenseBillRepository repo, SupplierRepository supplierRepo, CostCentreRepository costCentreRepo,
                              CurrencyRepository currencyRepo, AlertStateRepository alertRepo, RequisitionService requisitions,
                              BudgetService budgets, FixedAssetService fixedAssets, AttachmentService attachments, StockService stockService, PostingService postingService, JournalService journalService,
                              DocumentNumberService numbers, DelegationOfAuthority authority, PeriodLock periods, SettingService settings,
                              Notifier notifier, Clock clock) {
        this.repo = repo;
        this.supplierRepo = supplierRepo;
        this.costCentreRepo = costCentreRepo;
        this.currencyRepo = currencyRepo;
        this.alertRepo = alertRepo;
        this.requisitions = requisitions;
        this.budgets = budgets;
        this.fixedAssets = fixedAssets;
        this.attachments = attachments;
        this.stockService = stockService;
        this.postingService = postingService;
        this.journalService = journalService;
        this.numbers = numbers;
        this.authority = authority;
        this.periods = periods;
        this.settings = settings;
        this.notifier = notifier;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- reading

    public Page<ExpenseBill> findPage(String search, String status, int page, int size) {
        Specification<ExpenseBill> spec = (root, query, cb) -> {
            Predicate p = cb.conjunction();
            if (StringUtils.hasText(search)) {
                String term = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                var supplier = root.join("supplier", JoinType.LEFT);
                p = cb.and(p, cb.or(cb.like(cb.lower(root.get("number")), term), cb.like(cb.lower(root.get("description")), term),
                        cb.like(cb.lower(root.get("supplierRef")), term), cb.like(cb.lower(supplier.get("name")), term)));
            }
            if ("waiting".equals(status)) {
                p = cb.and(p, root.get("status").in(WAITING));
            } else if ("ratify".equals(status)) {
                p = cb.and(p, cb.isTrue(root.get("emergency")), cb.equal(root.get("status"), ExpenseBillStatus.APPROVED),
                        cb.isNull(root.get("ratifiedAt")));
            } else if (StringUtils.hasText(status)) {
                try {
                    p = cb.and(p, cb.equal(root.get("status"), ExpenseBillStatus.valueOf(status)));
                } catch (IllegalArgumentException e) {
                    // unknown: no filter
                }
            }
            return p;
        };
        return repo.findAll(spec, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "billDate").and(Sort.by(Sort.Direction.DESC, "number"))));
    }

    public ExpenseBill findDetailed(UUID id) {
        return repo.findDetailedById(id).orElseThrow(() -> new NotFoundException("ExpenseBill", id));
    }

    public long waitingCount() {
        return repo.countByStatusIn(WAITING);
    }

    public long toRatifyCount() {
        return repo.findByEmergencyTrueAndStatusAndRatifiedAtIsNullOrderByDecidedAtAsc(ExpenseBillStatus.APPROVED).size();
    }

    /** The fixed assets its capital items became (C.08). */
    public List<FixedAsset> assets(ExpenseBill bill) {
        return fixedAssets.ofBill(bill);
    }

    /** Its journal and, once reversed, the reversal. */
    public List<JournalEntry> journals(ExpenseBill bill) {
        List<JournalEntry> journals = new ArrayList<>();
        if (bill.getJournalId() != null) {
            journals.add(journalService.findById(bill.getJournalId()));
        }
        if (bill.getReversalJournalId() != null) {
            journals.add(journalService.findById(bill.getReversalJournalId()));
        }
        return journals;
    }

    /** Approved RWF suppliers a bill may be from (CMP 08: the register). */
    public List<Supplier> suppliers() {
        String base = baseCurrency();
        return supplierRepo.findAll(Sort.by("name")).stream().filter(s -> s.isEnabled() && s.isApproved() && base.equals(s.getCurrencyCode())).toList();
    }

    public List<CostCentre> costCentres() {
        return costCentreRepo.findByEnabledTrueOrderByCodeAsc();
    }

    /** Places an expense may be for: the sites and the vehicles (F-15 A: Gahanga, BBF Rubavu, van). */
    public List<Location> locations() {
        return stockService.locationsById().values().stream().filter(Location::isEnabled)
                .filter(l -> l.getParentId() == null).sorted(Comparator.comparing(Location::getCode)).toList();
    }

    public List<PurchaseRequisition> requisitions(UUID chosen) {
        List<PurchaseRequisition> list = new ArrayList<>(requisitions.approvedUnused());
        if (chosen != null && list.stream().noneMatch(r -> r.getId().equals(chosen))) {
            // The bill's own requisition is closed by it: still shown on its form
            list.add(0, requisitions.findDetailed(chosen));
        }
        return list;
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    /** What its approval needs from the Delegation of Authority and whether the signed-in user may give it. */
    public DelegationOfAuthority.Check authorityOf(ExpenseBill bill) {
        return authority.check(decisionOf(bill), bill.getAmount());
    }

    public AuthorityDecision decisionOf(ExpenseBill bill) {
        return Expenditure.decision(bill.isEmergency(), bill.isBudgeted(), bill.getNature());
    }

    public boolean recommendRequired() {
        return Boolean.parseBoolean(settings.get(SettingKey.EXPENSE_RECOMMEND));
    }

    public boolean reviewRequired(ExpenseBill bill) {
        return Expenditure.needsReview(bill.getAmount(), settings.getDecimal(SettingKey.EXPENSE_FINANCE_REVIEW_FROM));
    }

    /** The step it waits for: RECOMMEND, REVIEW or APPROVE (null when not waiting). */
    public String nextStep(ExpenseBill bill) {
        if (!bill.isWaiting()) {
            return null;
        }
        if (bill.getStatus() == ExpenseBillStatus.SUBMITTED && recommendRequired()) {
            return "RECOMMEND";
        }
        if (bill.getStatus() != ExpenseBillStatus.REVIEWED && reviewRequired(bill)) {
            return "REVIEW";
        }
        return "APPROVE";
    }

    /** Whether the signed-in user took part in it already (entered, recommended or reviewed): they do not decide it. */
    public boolean tookPart(ExpenseBill bill) {
        Long me = AppUserPrincipal.current().map(AppUserPrincipal::getId).orElse(null);
        return me != null && (me.equals(bill.getEnteredById()) || me.equals(bill.getRecommendedById()) || me.equals(bill.getReviewedById()));
    }

    /** The managers the bill's cost centres name (its heads of department). */
    public Set<Long> managers(ExpenseBill bill) {
        Set<Long> managers = new LinkedHashSet<>();
        bill.getLines().forEach(l -> {
            if (l.getCostCentre().getManagerUserId() != null) {
                managers.add(l.getCostCentre().getManagerUserId());
            }
        });
        return managers;
    }

    /** Whether the signed-in user may recommend it as head of department (CMP 05). */
    public boolean isHead(ExpenseBill bill) {
        return Expenditure.mayActAsHead(managers(bill), AppUserPrincipal.current().map(AppUserPrincipal::getId).orElse(null),
                authority.currentLevel().orElse(null));
    }

    /** The bill's departments and accounts that go beyond what is left of the year's approved budget (T.04): flagged unbudgeted. */
    public List<String> overBudget(ExpenseBill bill) {
        Map<UUID, Map<AccountKey, BigDecimal>> amounts = new HashMap<>();
        for (ExpenseBillLine l : bill.getLines()) {
            amounts.computeIfAbsent(l.getCostCentre().getId(), k -> new EnumMap<>(AccountKey.class)).merge(l.getCategory().account(), l.getNetAmount(), BigDecimal::add);
        }
        return budgets.overBudget(bill.getBillDate().getYear(), amounts);
    }

    public boolean isEnterer(ExpenseBill bill) {
        return AppUserPrincipal.current().map(u -> u.getId().equals(bill.getEnteredById())).orElse(false);
    }

    /** Whether submitting it needs a requisition (CMP 06): a purchase category and the amount from the Settings' threshold. */
    public boolean requisitionRequired(ExpenseBill bill) {
        boolean purchase = bill.getLines().stream().anyMatch(l -> l.getCategory().needsRequisition());
        return purchase && requisitions.isRequired(bill.getAmount());
    }

    /** The withholding tax rate a bill withholds by default (U.14: the Settings). */
    public BigDecimal defaultWhtRate() {
        return settings.getDecimal(SettingKey.TAX_WHT_RATE);
    }

    public ExpenseBillDto newForm() {
        ExpenseBillDto dto = new ExpenseBillDto();
        dto.setWhtRate(defaultWhtRate());
        dto.setBillDate(today());
        dto.getLines().add(new ExpenseBillDto.Line());
        return dto;
    }

    public ExpenseBillDto formOf(ExpenseBill bill) {
        ExpenseBillDto dto = new ExpenseBillDto();
        dto.setSupplierId(bill.getSupplier().getId());
        dto.setSupplierRef(bill.getSupplierRef());
        dto.setBillDate(bill.getBillDate());
        dto.setDueDate(bill.getDueDate());
        dto.setDescription(bill.getDescription());
        dto.setBudgeted(bill.isBudgeted());
        dto.setEmergency(bill.isEmergency());
        dto.setRequisitionId(bill.getRequisition() == null ? null : bill.getRequisition().getId());
        dto.setWithhold(bill.getWhtRate().signum() > 0);
        dto.setWhtRate(bill.getWhtRate().signum() > 0 ? bill.getWhtRate() : defaultWhtRate());
        for (ExpenseBillLine l : bill.getLines()) {
            ExpenseBillDto.Line line = new ExpenseBillDto.Line();
            line.setCategory(l.getCategory());
            line.setDescription(l.getDescription());
            line.setCostCentreId(l.getCostCentre().getId());
            line.setLocationId(l.getLocationId());
            line.setNetAmount(l.getNetAmount());
            line.setVatAmount(l.getVatAmount());
            dto.getLines().add(line);
        }
        return dto;
    }

    // ---------------------------------------------------------------- entering

    /** Saves a draft: a new bill, or its enterer's draft changed. */
    @Transactional
    public ExpenseBill save(UUID id, ExpenseBillDto dto) {
        ExpenseBill bill;
        if (id == null) {
            Optional<AppUserPrincipal> user = AppUserPrincipal.current();
            bill = new ExpenseBill();
            bill.setNumber(numbers.next(DocumentType.EXPENSE_BILL));
            bill.setEnteredBy(user.map(AppUserPrincipal::getUsername).orElse("system"));
            bill.setEnteredById(user.map(AppUserPrincipal::getId).orElse(null));
            bill.setEnteredAt(LocalDateTime.now(clock));
        } else {
            bill = lock(id);
            if (!bill.isDraft()) {
                throw BusinessException.of("expense.notDraft", bill.getNumber());
            }
            if (!isEnterer(bill)) {
                throw BusinessException.of("expense.notYours", bill.getNumber(), bill.getEnteredBy());
            }
        }
        Supplier supplier = supplierRepo.findById(dto.getSupplierId()).orElseThrow(() -> new NotFoundException("Supplier", dto.getSupplierId()));
        if (!supplier.isEnabled()) {
            throw BusinessException.onField("supplierId", "expense.supplier.disabled", supplier.getName());
        }
        SupplierService.requireApproved(supplier, "supplierId");      // CMP 08, 09: no payment to a supplier off the register
        if (!baseCurrency().equals(supplier.getCurrencyCode())) {
            throw BusinessException.onField("supplierId", "expense.supplier.currency", supplier.getName(), supplier.getCurrencyCode());
        }
        String ref = dto.getSupplierRef().trim().toUpperCase(Locale.ROOT);
        if (repo.isBilled(supplier.getId(), ref, bill.getId())) {
            throw BusinessException.onField("supplierRef", "expense.supplierRef.billed", ref, supplier.getName());
        }
        if (dto.getBillDate().isAfter(today())) {
            throw BusinessException.onField("billDate", "expense.billDate.future");
        }
        periods.requireOpen(dto.getBillDate(), "billDate");
        if (dto.getDueDate() != null && dto.getDueDate().isBefore(dto.getBillDate())) {
            throw BusinessException.onField("dueDate", "expense.dueDate.early");
        }
        if (dto.getLines().isEmpty()) {
            throw BusinessException.onField("lines", "expense.lines.required");
        }
        long capital = dto.getLines().stream().filter(l -> l.getCategory().isCapital()).count();
        if (capital > 0 && capital < dto.getLines().size()) {
            throw BusinessException.onField("lines", "expense.nature.mixed");
        }
        Map<UUID, Location> places = stockService.locationsById();
        bill.setSupplier(supplier);
        bill.setSupplierRef(ref);
        bill.setBillDate(dto.getBillDate());
        bill.setDueDate(dto.getDueDate());
        bill.setDescription(dto.getDescription().trim());
        bill.setBudgeted(dto.isBudgeted());
        bill.setEmergency(dto.isEmergency());
        bill.setNature(capital > 0 ? ExpenseNature.CAPEX : ExpenseNature.OPEX);
        bill.setRequisition(dto.getRequisitionId() == null ? null : requisitions.findDetailed(dto.getRequisitionId()));
        bill.getLines().clear();
        BigDecimal net = BigDecimal.ZERO;
        BigDecimal vat = BigDecimal.ZERO;
        int no = 0;
        for (ExpenseBillDto.Line l : dto.getLines()) {
            String field = "lines[" + no + "]";
            CostCentre centre = costCentreRepo.findById(l.getCostCentreId())
                    .orElseThrow(() -> BusinessException.onField(field + ".costCentreId", "expense.line.costCentre.required"));
            if (!centre.isEnabled()) {
                throw BusinessException.onField(field + ".costCentreId", "expense.line.costCentre.disabled", centre.getCode());
            }
            if (l.getLocationId() != null && !places.containsKey(l.getLocationId())) {
                throw BusinessException.onField(field + ".locationId", "expense.line.location.unknown");
            }
            BigDecimal lineNet = l.getNetAmount().setScale(2, RoundingMode.HALF_UP);
            BigDecimal lineVat = l.getVatAmount() == null ? BigDecimal.ZERO.setScale(2) : l.getVatAmount().setScale(2, RoundingMode.HALF_UP);
            if (lineVat.compareTo(Expenditure.maxVat(lineNet)) > 0) {
                throw BusinessException.onField(field + ".vatAmount", "expense.line.vat.tooHigh", Expenditure.maxVat(lineNet).toPlainString());
            }
            ExpenseBillLine line = new ExpenseBillLine();
            line.setBill(bill);
            line.setLineNo(++no);
            line.setCategory(l.getCategory());
            line.setDescription(l.getDescription().trim());
            line.setCostCentre(centre);
            line.setLocationId(l.getLocationId());
            line.setNetAmount(lineNet);
            line.setVatAmount(lineVat);
            bill.getLines().add(line);
            net = net.add(lineNet);
            vat = vat.add(lineVat);
        }
        bill.setNetAmount(net);
        bill.setVatAmount(vat);
        bill.setAmount(net.add(vat));
        BigDecimal rate = dto.isWithhold() ? (dto.getWhtRate() == null ? defaultWhtRate() : dto.getWhtRate()) : BigDecimal.ZERO;
        if (dto.isWithhold() && rate.signum() <= 0) {
            throw BusinessException.onField("whtRate", "expense.wht.range");
        }
        bill.setWhtRate(rate.setScale(2, RoundingMode.HALF_UP));
        bill.setWhtAmount(withheld(net, rate));                       // U.14: on the amount before VAT, whole francs
        if (!overBudget(bill).isEmpty()) {
            bill.setBudgeted(false);                                 // T.04: beyond what is left of the year's approved budget
        }
        bill.setDecision(decisionOf(bill));
        return repo.save(bill);
    }

    /** Its enterer submits it: the supplier's invoice attached, a purchase's requisition used; the next signer is told. */
    @Transactional
    public ExpenseBill submit(UUID id) {
        ExpenseBill bill = lock(id);
        if (!bill.isDraft()) {
            throw BusinessException.of("expense.notDraft", bill.getNumber());
        }
        if (!isEnterer(bill)) {
            throw BusinessException.of("expense.notYours", bill.getNumber(), bill.getEnteredBy());
        }
        periods.requireOpen(bill.getBillDate(), "billDate");
        if (Boolean.parseBoolean(settings.get(SettingKey.EXPENSE_INVOICE_DOCUMENT)) && attachments.count(AttachmentOwner.EXPENSE_BILL, bill.getId()) == 0) {
            throw BusinessException.of("expense.invoice.required", bill.getNumber());
        }
        if (requisitionRequired(bill) || bill.getRequisition() != null) {
            requisitions.use(bill.getRequisition() == null ? null : bill.getRequisition().getId(), bill.getNumber(), bill.getSupplier(),
                    bill.getAmount(), null);
        }
        bill.setDecision(decisionOf(bill));
        bill.setSubmittedAt(LocalDateTime.now(clock));
        bill.setStatus(ExpenseBillStatus.SUBMITTED);
        tellNext(bill);
        return bill;
    }

    /** The head of department recommends it (CMP 05), never who entered it. */
    @Transactional
    public ExpenseBill recommend(UUID id, String note) {
        ExpenseBill bill = lock(id);
        if (bill.getStatus() != ExpenseBillStatus.SUBMITTED || !"RECOMMEND".equals(nextStep(bill))) {
            throw BusinessException.of("expense.step.notRecommend", bill.getNumber());
        }
        if (tookPart(bill)) {
            throw BusinessException.of("expense.ownStep", bill.getNumber());
        }
        if (!isHead(bill)) {
            throw BusinessException.of("expense.notHead", bill.getNumber());
        }
        Optional<AppUserPrincipal> me = AppUserPrincipal.current();
        bill.setRecommendedBy(me.map(AppUserPrincipal::getUsername).orElse("system"));
        bill.setRecommendedById(me.map(AppUserPrincipal::getId).orElse(null));
        bill.setRecommendedAt(LocalDateTime.now(clock));
        bill.setRecommendNote(note(note));
        bill.setStatus(ExpenseBillStatus.RECOMMENDED);
        tellNext(bill);
        return bill;
    }

    /** Finance reviews it from the Settings' amount (CMP 05), never who entered or recommended it. */
    @Transactional
    public ExpenseBill review(UUID id, String note) {
        ExpenseBill bill = lock(id);
        if (!"REVIEW".equals(nextStep(bill))) {
            throw BusinessException.of("expense.step.notReview", bill.getNumber());
        }
        if (tookPart(bill)) {
            throw BusinessException.of("expense.ownStep", bill.getNumber());
        }
        Optional<AppUserPrincipal> me = AppUserPrincipal.current();
        bill.setReviewedBy(me.map(AppUserPrincipal::getUsername).orElse("system"));
        bill.setReviewedById(me.map(AppUserPrincipal::getId).orElse(null));
        bill.setReviewedAt(LocalDateTime.now(clock));
        bill.setReviewNote(note(note));
        bill.setStatus(ExpenseBillStatus.REVIEWED);
        tellNext(bill);
        return bill;
    }

    /** Approved within the expenditure matrix by someone who took no part in it: it posts to the supplier's account. */
    @Transactional
    public ExpenseBill approve(UUID id, String note) {
        ExpenseBill bill = lock(id);
        if (!"APPROVE".equals(nextStep(bill))) {
            throw BusinessException.of("expense.step.notApprove", bill.getNumber());
        }
        if (tookPart(bill)) {
            throw BusinessException.of("expense.ownStep", bill.getNumber());
        }
        periods.requireOpen(bill.getBillDate(), "billDate");
        String clean = note(note);
        AuthorityLevel level = authority.require(decisionOf(bill), bill.getAmount(), clean);
        Optional<AppUserPrincipal> me = AppUserPrincipal.current();
        JournalEntry entry = postingService.expenseBill(bill);
        fixedAssets.fromBill(bill);                                  // C.08: each capital item into the register
        LocalDateTime now = LocalDateTime.now(clock);
        bill.setDecidedBy(me.map(AppUserPrincipal::getUsername).orElse("system"));
        bill.setDecidedById(me.map(AppUserPrincipal::getId).orElse(null));
        bill.setDecidedAt(now);
        bill.setDecisionNote(clean);
        bill.setAuthorityLevel(level);
        bill.setJournalId(entry.getId());
        bill.setPostedAt(now);
        bill.setStatus(ExpenseBillStatus.APPROVED);
        notifier.user(bill.getEnteredById(), NotificationKind.DECISION, "notify.expense.approved", "notify.decidedBy", link(bill), bill.getNumber(),
                bill.getDecidedBy());
        if (bill.isEmergency()) {
            notifier.holders("RATIFY_EXPENSE", bill.getDecidedById(), NotificationKind.APPROVAL, "notify.expense.ratify", "notify.expense.ratifyText",
                    link(bill), bill.getNumber(), bill.getSupplier().getName(), bill.getAmount().setScale(0, RoundingMode.HALF_UP).toPlainString(),
                    bill.getDecidedBy());
        }
        return bill;
    }

    /** Rejected with a reason by someone who took no part in it; its requisition may be used again. */
    @Transactional
    public ExpenseBill reject(UUID id, String reason) {
        ExpenseBill bill = lock(id);
        if (!bill.isWaiting()) {
            throw BusinessException.of("expense.notWaiting", bill.getNumber());
        }
        if (tookPart(bill)) {
            throw BusinessException.of("expense.ownStep", bill.getNumber());
        }
        decideClosed(bill, ExpenseBillStatus.REJECTED, reason(reason));
        notifier.user(bill.getEnteredById(), NotificationKind.DECISION, "notify.expense.rejected", "notify.rejectedBy", link(bill), bill.getNumber(),
                bill.getDecidedBy(), bill.getDecisionNote());
        return bill;
    }

    /** Its enterer cancels it, with a reason, before it is approved. */
    @Transactional
    public ExpenseBill cancel(UUID id, String reason) {
        ExpenseBill bill = lock(id);
        if (!bill.isDraft() && !bill.isWaiting()) {
            throw BusinessException.of("expense.notWaiting", bill.getNumber());
        }
        if (!isEnterer(bill)) {
            throw BusinessException.of("expense.notYours", bill.getNumber(), bill.getEnteredBy());
        }
        decideClosed(bill, ExpenseBillStatus.CANCELLED, reason(reason));
        return bill;
    }

    /** The Board ratifies an emergency the MD approved (CMP 05): a holder of the Board's office, with the resolution. */
    @Transactional
    public ExpenseBill ratify(UUID id, String note) {
        ExpenseBill bill = lock(id);
        if (!bill.isAwaitingRatification()) {
            throw BusinessException.of("expense.ratify.notDue", bill.getNumber());
        }
        if (authority.currentLevel().orElse(null) != AuthorityLevel.BOARD) {
            throw BusinessException.of("expense.ratify.board", bill.getNumber());
        }
        if (AppUserPrincipal.current().map(u -> u.getId().equals(bill.getDecidedById())).orElse(false)) {
            throw BusinessException.of("expense.ratify.samePerson", bill.getNumber());       // the Board ratifies what another approved
        }
        String clean = PartyRules.clean(note);
        if (clean == null || clean.length() > 500) {
            throw BusinessException.onField("note", "expense.ratify.note");
        }
        Optional<AppUserPrincipal> me = AppUserPrincipal.current();
        bill.setRatifiedBy(me.map(AppUserPrincipal::getUsername).orElse("system"));
        bill.setRatifiedById(me.map(AppUserPrincipal::getId).orElse(null));
        bill.setRatifiedAt(LocalDateTime.now(clock));
        bill.setRatifyNote(clean);
        AlertOnce.clear(alertRepo, ALERT_PREFIX + bill.getId(), LocalDateTime.now(clock));
        return bill;
    }

    /** Reverses a posted bill on a day from its date to today, in an open month, with a reason (never edited). */
    @Transactional
    public ExpenseBill reverse(UUID id, LocalDate date, String reason) {
        ExpenseBill bill = lock(id);
        if (!bill.isPosted()) {
            throw BusinessException.of("expense.notPosted", bill.getNumber());
        }
        LocalDate day = date == null ? today() : date;
        if (day.isAfter(today())) {
            throw BusinessException.of("expense.reversal.future");
        }
        if (day.isBefore(bill.getBillDate())) {
            throw BusinessException.of("expense.reversal.early", bill.getBillDate().format(DAY));
        }
        periods.requireOpen(day);
        String clean = reason(reason);
        fixedAssets.billReversed(bill, day, clean);                  // its assets cancelled, unless depreciated or disposed of
        JournalEntry reversal = journalService.reverse(journalService.findById(bill.getJournalId()), day,
                "Reversal of " + bill.getNumber() + ": " + clean);
        bill.setReversalJournalId(reversal.getId());
        bill.setReversalDate(day);
        bill.setReversalReason(clean);
        bill.setReversedBy(AppUserPrincipal.currentUsername());
        bill.setReversedAt(LocalDateTime.now(clock));
        bill.setStatus(ExpenseBillStatus.REVERSED);
        return bill;
    }

    /** Emergencies not ratified within the Settings' days: ALERT_EXPENSE holders are told once per bill (CMP 05). */
    @Scheduled(fixedDelayString = "${app.alerts.expense-every:PT6H}", initialDelayString = "${app.alerts.first-check:PT1M}")
    @Transactional
    public int checkRatifications() {
        int days = settings.getInt(SettingKey.EXPENSE_RATIFY_DAYS);
        LocalDateTime now = LocalDateTime.now(clock);
        int told = 0;
        for (ExpenseBill bill : repo.findByEmergencyTrueAndStatusAndRatifiedAtIsNullOrderByDecidedAtAsc(ExpenseBillStatus.APPROVED)) {
            if (bill.getDecidedAt() == null || bill.getDecidedAt().plusDays(days).isAfter(now)) {
                continue;
            }
            if (AlertOnce.raise(alertRepo, ALERT_PREFIX + bill.getId(), now)) {
                notifier.holders("ALERT_EXPENSE", null, NotificationKind.EXPENSE, "notify.expense.ratifyLate", "notify.expense.ratifyLateText",
                        link(bill), bill.getNumber(), bill.getSupplier().getName(), bill.getDecidedAt().toLocalDate().format(DAY), String.valueOf(days));
                told++;
            }
        }
        return told;
    }

    // ---------------------------------------------------------------- helpers

    /** Tells whoever signs next: the recommenders, the reviewers or the approvers. */
    private void tellNext(ExpenseBill bill) {
        String step = nextStep(bill);
        Long me = AppUserPrincipal.current().map(AppUserPrincipal::getId).orElse(null);
        if ("RECOMMEND".equals(step) && !managers(bill).isEmpty()) {
            for (Long manager : managers(bill)) {                    // the departments' heads, when they are named
                if (!manager.equals(me)) {
                    notifier.user(manager, NotificationKind.APPROVAL, "notify.expense.waiting", "notify.expense.waitingText", link(bill), bill.getNumber(),
                            bill.getSupplier().getName(), bill.getAmount().setScale(0, RoundingMode.HALF_UP).toPlainString(), bill.getDescription());
                }
            }
            return;
        }
        String permission = switch (step) {
            case "RECOMMEND" -> "RECOMMEND_EXPENSE";
            case "REVIEW" -> "REVIEW_EXPENSE";
            default -> "APPROVE_EXPENSE";
        };
        notifier.holders(permission, AppUserPrincipal.current().map(AppUserPrincipal::getId).orElse(null), NotificationKind.APPROVAL,
                "notify.expense.waiting", "notify.expense.waitingText", link(bill), bill.getNumber(), bill.getSupplier().getName(),
                bill.getAmount().setScale(0, RoundingMode.HALF_UP).toPlainString(), bill.getDescription());
    }

    private void decideClosed(ExpenseBill bill, ExpenseBillStatus status, String note) {
        requisitions.release(bill.getRequisition() == null ? null : bill.getRequisition().getId(), bill.getNumber());
        Optional<AppUserPrincipal> me = AppUserPrincipal.current();
        bill.setDecidedBy(me.map(AppUserPrincipal::getUsername).orElse("system"));
        bill.setDecidedById(me.map(AppUserPrincipal::getId).orElse(null));
        bill.setDecidedAt(LocalDateTime.now(clock));
        bill.setDecisionNote(note);
        bill.setStatus(status);
    }

    /** The tax withheld on an amount before VAT at a rate (%), whole francs, half up (U.14). */
    static BigDecimal withheld(BigDecimal net, BigDecimal rate) {
        return net.multiply(rate).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP).setScale(2);
    }

    private static String link(ExpenseBill bill) {
        return "/expenses/" + bill.getId();
    }

    private static String note(String note) {
        String clean = PartyRules.clean(note);
        if (clean != null && clean.length() > 500) {
            throw BusinessException.onField("note", "expense.note.size");
        }
        return clean;
    }

    private static String reason(String reason) {
        String clean = PartyRules.clean(reason);
        if (clean == null || clean.length() > 500) {
            throw BusinessException.onField("reason", "expense.reason.required");
        }
        return clean;
    }

    private ExpenseBill lock(UUID id) {
        repo.lockById(id).orElseThrow(() -> new NotFoundException("ExpenseBill", id));
        return findDetailed(id);
    }

    private String baseCurrency() {
        return currencyRepo.findByBaseCurrencyTrue().map(com.ntaganira.heritier.iWarehouse.entity.Currency::getCode).orElse("RWF");
    }
}
