package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.config.NumberFormats;
import com.ntaganira.heritier.iWarehouse.dto.BudgetDto;
import com.ntaganira.heritier.iWarehouse.dto.BudgetLinesDto;
import com.ntaganira.heritier.iWarehouse.entity.*;
import com.ntaganira.heritier.iWarehouse.enums.*;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.*;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
import java.util.stream.Collectors;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : BudgetService.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : The annual budget (CMP 04, BP 5.3; T.01, T.02, T.04): Finance starts a year's budget (PERM_PREPARE_BUDGET)
 *               with a part for each department in use; each department's head (PERM_SUBMIT_BUDGET: the cost centre's
 *               manager when named, else any holder) enters its lines by account month by month and submits them by the
 *               Settings' day (30 November); Finance may send a part back with a note, enters the company's lines (revenue),
 *               and submits the consolidated budget once every department with lines has submitted. The Board approves it
 *               (PERM_APPROVE_BUDGET, decision BUDGET: the Board's office and its resolution) or sends it back. A line of
 *               the approved budget changes only by a revision (BRV, PERM_REVISE_BUDGET) approved within the Delegation of
 *               Authority by the share it changes (decision BUDGET_REVISION: the MD up to 15 %, the Board above).
 *               overBudget tells expense bills which lines go beyond what is left (T.04: unbudgeted). checkDeadlines
 *               (scheduled) tells ALERT_BUDGET holders once when departments are late and when the year starts without an
 *               approved budget.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class BudgetService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    static final String SUBMIT_PREFIX = "BUDGET_SUBMIT:";
    static final String APPROVE_PREFIX = "BUDGET_APPROVE:";
    private static final Set<AccountType> BUDGETED = EnumSet.of(AccountType.REVENUE, AccountType.EXPENSE);

    /** A budget's lines summed: revenue and expenses for the year. */
    public record Totals(BigDecimal revenue, BigDecimal expenses) {
        public BigDecimal getResult() {
            return revenue.subtract(expenses);
        }
    }

    private final BudgetRepository repo;
    private final BudgetLineRepository lineRepo;
    private final BudgetDepartmentRepository deptRepo;
    private final BudgetRevisionRepository revisionRepo;
    private final CostCentreRepository costCentreRepo;
    private final AccountRepository accountRepo;
    private final JournalLineRepository journalLineRepo;
    private final AlertStateRepository alertRepo;
    private final DocumentNumberService numbers;
    private final DelegationOfAuthority authority;
    private final SettingService settings;
    private final Notifier notifier;
    private final NumberFormats num;
    private final Clock clock;

    public BudgetService(BudgetRepository repo, BudgetLineRepository lineRepo, BudgetDepartmentRepository deptRepo, BudgetRevisionRepository revisionRepo,
                         CostCentreRepository costCentreRepo, AccountRepository accountRepo, JournalLineRepository journalLineRepo,
                         AlertStateRepository alertRepo, DocumentNumberService numbers, DelegationOfAuthority authority, SettingService settings,
                         Notifier notifier, NumberFormats num, Clock clock) {
        this.repo = repo;
        this.lineRepo = lineRepo;
        this.deptRepo = deptRepo;
        this.revisionRepo = revisionRepo;
        this.costCentreRepo = costCentreRepo;
        this.accountRepo = accountRepo;
        this.journalLineRepo = journalLineRepo;
        this.alertRepo = alertRepo;
        this.numbers = numbers;
        this.authority = authority;
        this.settings = settings;
        this.notifier = notifier;
        this.num = num;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- reading

    public List<Budget> all() {
        return repo.findAllByOrderByBudgetYearDesc();
    }

    public Page<Budget> search(String search, int page, int size) {
        String term = "%" + (search == null ? "" : search.trim().toLowerCase(Locale.ROOT)) + "%";
        return repo.search(term, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "budgetYear")));
    }

    public Budget findDetailed(UUID id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException("Budget", id));
    }

    /** A budget's lines: the company's first, then by department, each by account code. */
    public List<BudgetLine> lines(UUID budgetId) {
        return lineRepo.findByBudget_Id(budgetId).stream()
                .sorted(Comparator.comparing((BudgetLine l) -> l.getCostCentre() == null ? "" : l.getCostCentre().getCode())
                        .thenComparing(l -> l.getAccount().getCode()))
                .toList();
    }

    public List<BudgetLine> linesOf(UUID budgetId, UUID centreId) {
        return lines(budgetId).stream().filter(l -> Objects.equals(centreId, l.getCostCentre() == null ? null : l.getCostCentre().getId())).toList();
    }

    public BudgetLine line(UUID id) {
        return lineRepo.findDetailedById(id).orElseThrow(() -> new NotFoundException("BudgetLine", id));
    }

    public List<BudgetDepartment> departments(UUID budgetId) {
        return deptRepo.findByBudgetIdOrderByCostCentre_CodeAsc(budgetId);
    }

    public Page<BudgetRevision> revisions(UUID budgetId, int page, int size) {
        return revisionRepo.findByBudget_IdOrderByRequestedAtDesc(budgetId, PageRequest.of(page, size));
    }

    public BudgetRevision revision(UUID id) {
        return revisionRepo.findDetailedById(id).orElseThrow(() -> new NotFoundException("BudgetRevision", id));
    }

    public long waitingCount() {
        return repo.countByStatus(BudgetStatus.SUBMITTED);
    }

    public long revisionsWaiting() {
        return revisionRepo.countByStatus(BudgetRevisionStatus.SUBMITTED);
    }

    public long revisionsWaiting(UUID budgetId) {
        return revisionRepo.countByBudget_IdAndStatus(budgetId, BudgetRevisionStatus.SUBMITTED);
    }

    public Optional<Budget> approvedFor(int year) {
        return repo.findByBudgetYearAndStatus(year, BudgetStatus.APPROVED);
    }

    /** The revenue and expense accounts in use: what a budget line may be on. */
    public List<Account> accounts() {
        return accountRepo.findAllByOrderByCodeAsc().stream().filter(a -> a.isEnabled() && BUDGETED.contains(a.getType())).toList();
    }

    public List<CostCentre> costCentres() {
        return costCentreRepo.findByEnabledTrueOrderByCodeAsc();
    }

    public Totals totals(List<BudgetLine> lines) {
        BigDecimal revenue = BigDecimal.ZERO, expenses = BigDecimal.ZERO;
        for (BudgetLine l : lines) {
            if (l.getAccount().getType() == AccountType.REVENUE) {
                revenue = revenue.add(l.getTotal());
            } else {
                expenses = expenses.add(l.getTotal());
            }
        }
        return new Totals(revenue, expenses);
    }

    /** Whether the signed-in user heads the department (CMP 04): its manager when one is named, or the COO and above. */
    public boolean isHead(CostCentre centre) {
        Set<Long> managers = centre.getManagerUserId() == null ? Set.of() : Set.of(centre.getManagerUserId());
        return Expenditure.mayActAsHead(managers, AppUserPrincipal.current().map(AppUserPrincipal::getId).orElse(null),
                authority.currentLevel().orElse(null));
    }

    /** Whether the signed-in user may change these lines now: Finance any while prepared, a head their open part. */
    public boolean mayEdit(Budget budget, BudgetDepartment dept) {
        if (!budget.isDraft()) {
            return false;
        }
        if (dept == null) {
            return AppUserPrincipal.currentHas("PERM_PREPARE_BUDGET");
        }
        if (dept.isSubmitted()) {
            return false;
        }
        return AppUserPrincipal.currentHas("PERM_PREPARE_BUDGET") || (AppUserPrincipal.currentHas("PERM_SUBMIT_BUDGET") && isHead(dept.getCostCentre()));
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    public LocalDate submitBy(int year) {
        return Budgets.dayOf(settings.get(SettingKey.BUDGET_SUBMIT_BY), year - 1);
    }

    public LocalDate approveBy(int year) {
        return Budgets.dayOf(settings.get(SettingKey.BUDGET_APPROVE_BY), year - 1);
    }

    public DelegationOfAuthority.Check authorityOf(Budget budget) {
        return authority.check(AuthorityDecision.BUDGET, BigDecimal.ZERO);
    }

    public DelegationOfAuthority.Check authorityOf(BudgetRevision revision) {
        return authority.check(AuthorityDecision.BUDGET_REVISION, revision.getChangePercent());
    }

    public BudgetDto newForm() {
        BudgetDto dto = new BudgetDto();
        int next = today().getYear() + 1;
        dto.setBudgetYear(next);
        return dto;
    }

    public BudgetDto formOf(Budget b) {
        BudgetDto dto = new BudgetDto();
        dto.setBudgetYear(b.getBudgetYear());
        dto.setTitle(b.getTitle());
        dto.setLandedCostBudget(b.getLandedCostBudget());
        dto.setDemurrageCeiling(b.getDemurrageCeiling());
        return dto;
    }

    public BudgetLinesDto linesForm(UUID budgetId, UUID centreId) {
        BudgetLinesDto dto = new BudgetLinesDto();
        for (BudgetLine l : linesOf(budgetId, centreId)) {
            BudgetLinesDto.Line line = new BudgetLinesDto.Line();
            line.setAccountId(l.getAccount().getId());
            line.setMonths(new ArrayList<>(Arrays.stream(l.getMonths()).map(m -> m.setScale(0, RoundingMode.HALF_UP)).toList()));
            line.setAnnual(l.getTotal().setScale(0, RoundingMode.HALF_UP));
            dto.getLines().add(line);
        }
        if (dto.getLines().isEmpty()) {
            dto.getLines().add(new BudgetLinesDto.Line());
        }
        return dto;
    }

    // ---------------------------------------------------------------- preparing

    /** Starts a year's budget with an open part for each department in use. */
    @Transactional
    public Budget create(BudgetDto dto) {
        if (repo.findFirstByBudgetYearAndStatusNot(dto.getBudgetYear(), BudgetStatus.CANCELLED).isPresent()) {
            throw BusinessException.onField("budgetYear", "budget.year.taken", String.valueOf(dto.getBudgetYear()));
        }
        Optional<AppUserPrincipal> me = AppUserPrincipal.current();
        Budget b = new Budget();
        b.setNumber(numbers.next(DocumentType.BUDGET));
        b.setBudgetYear(dto.getBudgetYear());
        apply(b, dto);
        b.setPreparedBy(me.map(AppUserPrincipal::getUsername).orElse("system"));
        b.setPreparedById(me.map(AppUserPrincipal::getId).orElse(null));
        b.setPreparedAt(LocalDateTime.now(clock));
        repo.save(b);
        for (CostCentre c : costCentres()) {
            BudgetDepartment d = new BudgetDepartment();
            d.setBudgetId(b.getId());
            d.setCostCentre(c);
            deptRepo.save(d);
            if (c.getManagerUserId() != null) {
                notifier.user(c.getManagerUserId(), NotificationKind.BUDGET, "notify.budget.open", "notify.budget.openText", link(b), b.getNumber(),
                        String.valueOf(b.getBudgetYear()), c.getCode(), submitBy(b.getBudgetYear()).format(DAY));
            }
        }
        if (costCentres().stream().anyMatch(c -> c.getManagerUserId() == null)) {
            notifier.holders("SUBMIT_BUDGET", b.getPreparedById(), NotificationKind.BUDGET, "notify.budget.open", "notify.budget.openText", link(b),
                    b.getNumber(), String.valueOf(b.getBudgetYear()), "", submitBy(b.getBudgetYear()).format(DAY));
        }
        return b;
    }

    @Transactional
    public Budget update(UUID id, BudgetDto dto) {
        Budget b = lock(id);
        requireDraft(b);
        apply(b, dto);
        return b;
    }

    /** The lines of one department (or of the company: centre null): changed, added and removed in one save. */
    @Transactional
    public Budget saveLines(UUID budgetId, UUID centreId, BudgetLinesDto dto) {
        Budget b = lock(budgetId);
        requireDraft(b);
        BudgetDepartment dept = centreId == null ? null : department(budgetId, centreId);
        if (!mayEdit(b, dept)) {
            throw BusinessException.of(dept == null ? "budget.company.financeOnly" : "budget.department.notYours", dept == null ? "" : dept.getCostCentre().getCode());
        }
        Map<UUID, Account> allowed = accounts().stream().collect(Collectors.toMap(Account::getId, a -> a));
        Map<UUID, BudgetLine> existing = new HashMap<>();
        linesOf(budgetId, centreId).forEach(l -> existing.put(l.getAccount().getId(), l));
        Set<UUID> seen = new HashSet<>();
        for (int i = 0; i < dto.getLines().size(); i++) {
            BudgetLinesDto.Line row = dto.getLines().get(i);
            String field = "lines[" + i + "]";
            Account account = allowed.get(row.getAccountId());
            if (account == null) {
                throw BusinessException.onField(field + ".accountId", "budget.line.account");
            }
            if (!seen.add(account.getId())) {
                throw BusinessException.onField(field + ".accountId", "budget.line.twice", account.getCode());
            }
            BigDecimal[] months = months(row, field);
            BudgetLine line = existing.remove(account.getId());
            if (line == null) {
                line = new BudgetLine();
                line.setBudget(b);
                line.setCostCentre(dept == null ? null : dept.getCostCentre());
                line.setAccount(account);
            }
            line.setMonths(months);
            lineRepo.save(line);
        }
        lineRepo.deleteAll(existing.values());
        return b;
    }

    /** A department's head (or Finance) submits its part. */
    @Transactional
    public BudgetDepartment submitDepartment(UUID budgetId, UUID centreId) {
        Budget b = lock(budgetId);
        requireDraft(b);
        BudgetDepartment d = department(budgetId, centreId);
        if (!mayEdit(b, d)) {
            throw BusinessException.of("budget.department.notYours", d.getCostCentre().getCode());
        }
        Optional<AppUserPrincipal> me = AppUserPrincipal.current();
        d.setSubmittedBy(me.map(AppUserPrincipal::getUsername).orElse("system"));
        d.setSubmittedById(me.map(AppUserPrincipal::getId).orElse(null));
        d.setSubmittedAt(LocalDateTime.now(clock));
        d.setStatus(BudgetDepartmentStatus.SUBMITTED);
        notifier.holders("PREPARE_BUDGET", d.getSubmittedById(), NotificationKind.BUDGET, "notify.budget.departmentIn", "notify.budget.departmentInText",
                link(b), b.getNumber(), d.getCostCentre().getCode(), d.getSubmittedBy());
        return d;
    }

    /** Finance sends a department's part back with a note: open again for its head. */
    @Transactional
    public BudgetDepartment returnDepartment(UUID budgetId, UUID centreId, String note) {
        Budget b = lock(budgetId);
        requireDraft(b);
        BudgetDepartment d = department(budgetId, centreId);
        if (!d.isSubmitted()) {
            throw BusinessException.of("budget.department.notSubmitted", d.getCostCentre().getCode());
        }
        d.setReturnedBy(AppUserPrincipal.currentUsername());
        d.setReturnedNote(reason(note));
        d.setStatus(BudgetDepartmentStatus.OPEN);
        if (d.getSubmittedById() != null) {
            notifier.user(d.getSubmittedById(), NotificationKind.BUDGET, "notify.budget.departmentBack", "notify.rejectedBy", link(b), b.getNumber(),
                    d.getReturnedBy(), d.getReturnedNote());
        }
        return d;
    }

    /** Finance submits the consolidated budget to the Board once every department with lines has submitted. */
    @Transactional
    public Budget submit(UUID id) {
        Budget b = lock(id);
        requireDraft(b);
        List<BudgetLine> lines = lines(id);
        if (lines.isEmpty()) {
            throw BusinessException.of("budget.empty", b.getNumber());
        }
        Set<UUID> withLines = lines.stream().filter(l -> l.getCostCentre() != null).map(l -> l.getCostCentre().getId()).collect(Collectors.toSet());
        List<String> open = departments(id).stream().filter(d -> withLines.contains(d.getCostCentre().getId()) && !d.isSubmitted())
                .map(d -> d.getCostCentre().getCode()).toList();
        if (!open.isEmpty()) {
            throw BusinessException.of("budget.departmentsOpen", String.join(", ", open));
        }
        Optional<AppUserPrincipal> me = AppUserPrincipal.current();
        b.setSubmittedBy(me.map(AppUserPrincipal::getUsername).orElse("system"));
        b.setSubmittedById(me.map(AppUserPrincipal::getId).orElse(null));
        b.setSubmittedAt(LocalDateTime.now(clock));
        b.setStatus(BudgetStatus.SUBMITTED);
        Totals t = totals(lines);
        notifier.holders("APPROVE_BUDGET", b.getSubmittedById(), NotificationKind.APPROVAL, "notify.budget.waiting", "notify.budget.waitingText", link(b),
                b.getNumber(), String.valueOf(b.getBudgetYear()), num.money(t.revenue()), num.money(t.expenses()));
        return b;
    }

    /** The Board approves it with its resolution (decision BUDGET), never who prepared or submitted it. */
    @Transactional
    public Budget approve(UUID id, String note) {
        Budget b = lock(id);
        requireSubmitted(b);
        if (isOwn(b)) {
            throw BusinessException.of("budget.ownDecision", b.getNumber());
        }
        String clean = note(note);
        authority.require(AuthorityDecision.BUDGET, BigDecimal.ZERO, clean);
        decide(b, BudgetStatus.APPROVED, clean);
        AlertOnce.clear(alertRepo, APPROVE_PREFIX + b.getBudgetYear(), LocalDateTime.now(clock));
        notifier.user(b.getSubmittedById(), NotificationKind.DECISION, "notify.budget.approved", "notify.decidedBy", link(b), b.getNumber(), b.getDecidedBy());
        return b;
    }

    /** The Board sends it back to Finance with a reason: prepared again. */
    @Transactional
    public Budget sendBack(UUID id, String reason) {
        Budget b = lock(id);
        requireSubmitted(b);
        if (isOwn(b)) {
            throw BusinessException.of("budget.ownDecision", b.getNumber());
        }
        b.setReturnedNote(reason(reason));
        b.setStatus(BudgetStatus.DRAFT);
        notifier.user(b.getSubmittedById(), NotificationKind.DECISION, "notify.budget.returned", "notify.rejectedBy", link(b), b.getNumber(),
                AppUserPrincipal.currentUsername(), b.getReturnedNote());
        return b;
    }

    /** Finance cancels a budget not approved, with a reason: the year may be started again. */
    @Transactional
    public Budget cancel(UUID id, String reason) {
        Budget b = lock(id);
        if (b.getStatus() != BudgetStatus.DRAFT && b.getStatus() != BudgetStatus.SUBMITTED) {
            throw BusinessException.of("budget.notCancellable", b.getNumber());
        }
        decide(b, BudgetStatus.CANCELLED, reason(reason));
        return b;
    }

    // ---------------------------------------------------------------- revisions

    /** Asks to change a line of the approved budget (T.02): its new months and the reason; one revision waits per line. */
    @Transactional
    public BudgetRevision requestRevision(UUID lineId, List<BigDecimal> newMonths, String reason) {
        BudgetLine line = line(lineId);
        Budget b = line.getBudget();
        if (!b.isApproved()) {
            throw BusinessException.of("budget.revision.notApproved", b.getNumber());
        }
        if (revisionRepo.existsByLine_IdAndStatus(lineId, BudgetRevisionStatus.SUBMITTED)) {
            throw BusinessException.of("budget.revision.waiting");
        }
        BudgetLinesDto.Line row = new BudgetLinesDto.Line();
        row.setMonths(newMonths);
        BigDecimal[] months = months(row, "months");
        BigDecimal newTotal = Budgets.sum(months);
        if (Arrays.equals(Arrays.stream(months).map(m -> m.setScale(2)).toArray(), Arrays.stream(line.getMonths()).map(m -> m.setScale(2)).toArray())) {
            throw BusinessException.onField("months", "budget.revision.same");
        }
        Optional<AppUserPrincipal> me = AppUserPrincipal.current();
        BudgetRevision r = new BudgetRevision();
        r.setNumber(numbers.next(DocumentType.BUDGET_REVISION));
        r.setBudget(b);
        r.setLine(line);
        r.setMonths(months);
        r.setOldTotal(line.getTotal());
        r.setNewTotal(newTotal);
        r.setChangePercent(Budgets.revisionPercent(line.getTotal(), newTotal));
        r.setReason(reason(reason));
        r.setRequestedBy(me.map(AppUserPrincipal::getUsername).orElse("system"));
        r.setRequestedById(me.map(AppUserPrincipal::getId).orElse(null));
        r.setRequestedAt(LocalDateTime.now(clock));
        revisionRepo.save(r);
        notifier.holders("APPROVE_BUDGET_REVISION", r.getRequestedById(), NotificationKind.APPROVAL, "notify.budget.revision", "notify.budget.revisionText",
                revisionLink(r), r.getNumber(), lineLabel(line), num.money(r.getOldTotal()), num.money(r.getNewTotal()), r.getChangePercent().toPlainString());
        return r;
    }

    /** Approved within the Delegation of Authority by the share it changes; the line takes its new months. */
    @Transactional
    public BudgetRevision approveRevision(UUID id, String note) {
        BudgetRevision r = lockRevision(id);
        if (!r.isWaiting()) {
            throw BusinessException.of("budget.revision.notWaiting", r.getNumber());
        }
        if (isRequester(r)) {
            throw BusinessException.of("budget.revision.own", r.getNumber());
        }
        String clean = note(note);
        AuthorityLevel level = authority.require(AuthorityDecision.BUDGET_REVISION, r.getChangePercent(), clean);
        BudgetLine line = lineRepo.lockById(r.getLine().getId()).orElseThrow(() -> new NotFoundException("BudgetLine", r.getLine().getId()));
        if (line.getTotal().compareTo(r.getOldTotal()) != 0) {
            throw BusinessException.of("budget.revision.stale", r.getNumber(), num.money(line.getTotal()));
        }
        line.setMonths(r.getMonths());
        decideRevision(r, BudgetRevisionStatus.APPROVED, clean);
        r.setAuthorityLevel(level);
        notifier.user(r.getRequestedById(), NotificationKind.DECISION, "notify.budget.revisionApproved", "notify.decidedBy", revisionLink(r), r.getNumber(),
                r.getDecidedBy());
        return r;
    }

    @Transactional
    public BudgetRevision rejectRevision(UUID id, String reason) {
        BudgetRevision r = lockRevision(id);
        if (!r.isWaiting()) {
            throw BusinessException.of("budget.revision.notWaiting", r.getNumber());
        }
        if (isRequester(r)) {
            throw BusinessException.of("budget.revision.own", r.getNumber());
        }
        decideRevision(r, BudgetRevisionStatus.REJECTED, reason(reason));
        notifier.user(r.getRequestedById(), NotificationKind.DECISION, "notify.budget.revisionRejected", "notify.rejectedBy", revisionLink(r), r.getNumber(),
                r.getDecidedBy(), r.getDecisionNote());
        return r;
    }

    @Transactional
    public BudgetRevision cancelRevision(UUID id, String reason) {
        BudgetRevision r = lockRevision(id);
        if (!r.isWaiting()) {
            throw BusinessException.of("budget.revision.notWaiting", r.getNumber());
        }
        if (!isRequester(r)) {
            throw BusinessException.of("budget.revision.notYours", r.getNumber(), r.getRequestedBy());
        }
        decideRevision(r, BudgetRevisionStatus.CANCELLED, reason(reason));
        return r;
    }

    public boolean isRequester(BudgetRevision r) {
        return AppUserPrincipal.current().map(u -> u.getId().equals(r.getRequestedById())).orElse(false);
    }

    /** Who prepared or submitted it does not decide it. */
    public boolean isOwn(Budget b) {
        Long me = AppUserPrincipal.current().map(AppUserPrincipal::getId).orElse(null);
        return me != null && (me.equals(b.getPreparedById()) || me.equals(b.getSubmittedById()));
    }

    // ---------------------------------------------------------------- budget control (T.04)

    /**
     * Of the amounts an expense bill would spend by cost centre and account, those beyond what is left of the year's approved
     * budget (annual less what was posted), as "cost centre · account" labels; none without an approved budget for the year.
     */
    public List<String> overBudget(int year, Map<UUID, Map<AccountKey, BigDecimal>> amounts) {
        Optional<Budget> budget = approvedFor(year);
        if (budget.isEmpty() || amounts.isEmpty()) {
            return List.of();
        }
        Map<String, BudgetLine> lines = new HashMap<>();
        lineRepo.findByBudget_Id(budget.get().getId()).forEach(l -> lines.put(key(l.getCostCentre() == null ? null : l.getCostCentre().getId(),
                l.getAccount().getId()), l));
        Map<String, BigDecimal> spent = new HashMap<>();
        for (Object[] r : journalLineRepo.movementsByAccountAndCentre(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31), EnumSet.of(AccountType.EXPENSE))) {
            spent.put(key((UUID) r[1], (UUID) r[0]), ((BigDecimal) r[2]).subtract((BigDecimal) r[3]));
        }
        Map<UUID, CostCentre> centres = costCentreRepo.findAll().stream().collect(Collectors.toMap(CostCentre::getId, c -> c));
        List<String> over = new ArrayList<>();
        amounts.forEach((centreId, byAccount) -> byAccount.forEach((key, amount) -> {
            Account account = accountRepo.findBySystemKey(key).orElse(null);
            if (account == null || account.getType() != AccountType.EXPENSE) {
                return;                                                   // capital items are not on the operating budget
            }
            BudgetLine line = lines.get(key(centreId, account.getId()));
            BigDecimal left = line == null ? BigDecimal.ZERO : line.getTotal().subtract(spent.getOrDefault(key(centreId, account.getId()), BigDecimal.ZERO));
            if (amount.compareTo(left) > 0) {
                CostCentre c = centreId == null ? null : centres.get(centreId);
                over.add((c == null ? "" : c.getCode() + " · ") + account.getCode() + " " + account.getName());
            }
        }));
        return over;
    }

    // ---------------------------------------------------------------- deadlines

    /** Departments late with next year's budget, and a year begun without an approved budget: told once each (CMP 04). */
    @Scheduled(fixedDelayString = "${app.alerts.budget-every:PT6H}", initialDelayString = "${app.alerts.first-check:PT1M}")
    @Transactional
    public int checkDeadlines() {
        LocalDate today = today();
        LocalDateTime now = LocalDateTime.now(clock);
        int told = 0;
        int next = today.getYear() + 1;
        if (today.isAfter(submitBy(next))) {
            Optional<Budget> b = repo.findFirstByBudgetYearAndStatusNot(next, BudgetStatus.CANCELLED);
            List<String> late = b.map(budget -> departments(budget.getId()).stream().filter(d -> !d.isSubmitted() && budget.isDraft())
                    .map(d -> d.getCostCentre().getCode()).toList()).orElse(List.of("—"));
            if (!late.isEmpty() && AlertOnce.raise(alertRepo, SUBMIT_PREFIX + next, now)) {
                notifier.holders("ALERT_BUDGET", null, NotificationKind.BUDGET, "notify.budget.late", "notify.budget.lateText", "/budgets",
                        String.valueOf(next), String.join(", ", late), submitBy(next).format(DAY));
                told++;
            }
        }
        int year = today.getYear();
        if (today.isAfter(approveBy(year)) && approvedFor(year).isEmpty() && AlertOnce.raise(alertRepo, APPROVE_PREFIX + year, now)) {
            notifier.holders("ALERT_BUDGET", null, NotificationKind.BUDGET, "notify.budget.notApproved", "notify.budget.notApprovedText", "/budgets",
                    String.valueOf(year), approveBy(year).format(DAY));
            told++;
        }
        return told;
    }

    // ---------------------------------------------------------------- helpers

    private BudgetDepartment department(UUID budgetId, UUID centreId) {
        return deptRepo.findByBudgetIdAndCostCentre_Id(budgetId, centreId).orElseThrow(() -> new NotFoundException("BudgetDepartment", centreId));
    }

    private static BigDecimal[] months(BudgetLinesDto.Line row, String field) {
        List<BigDecimal> given = row.getMonths();
        if (given == null || given.size() != 12) {
            throw BusinessException.onField(field + ".months", "budget.line.months");
        }
        BigDecimal[] months = new BigDecimal[12];
        for (int m = 0; m < 12; m++) {
            BigDecimal v = given.get(m) == null ? BigDecimal.ZERO : given.get(m);
            if (v.signum() < 0) {
                throw BusinessException.onField(field + ".months", "budget.line.negative");
            }
            months[m] = v.setScale(2, RoundingMode.HALF_UP);
        }
        return months;
    }

    private void apply(Budget b, BudgetDto dto) {
        b.setTitle(StringUtils.hasText(dto.getTitle()) ? dto.getTitle().trim() : "Budget " + dto.getBudgetYear());
        b.setLandedCostBudget(dto.getLandedCostBudget() == null ? BigDecimal.ZERO : dto.getLandedCostBudget().setScale(2, RoundingMode.HALF_UP));
        b.setDemurrageCeiling(dto.getDemurrageCeiling() == null ? BigDecimal.ZERO : dto.getDemurrageCeiling().setScale(2, RoundingMode.HALF_UP));
    }

    private void decide(Budget b, BudgetStatus status, String note) {
        Optional<AppUserPrincipal> me = AppUserPrincipal.current();
        b.setDecidedBy(me.map(AppUserPrincipal::getUsername).orElse("system"));
        b.setDecidedById(me.map(AppUserPrincipal::getId).orElse(null));
        b.setDecidedAt(LocalDateTime.now(clock));
        b.setDecisionNote(note);
        b.setStatus(status);
    }

    private void decideRevision(BudgetRevision r, BudgetRevisionStatus status, String note) {
        Optional<AppUserPrincipal> me = AppUserPrincipal.current();
        r.setDecidedBy(me.map(AppUserPrincipal::getUsername).orElse("system"));
        r.setDecidedById(me.map(AppUserPrincipal::getId).orElse(null));
        r.setDecidedAt(LocalDateTime.now(clock));
        r.setDecisionNote(note);
        r.setStatus(status);
    }

    static String key(UUID centreId, UUID accountId) {
        return (centreId == null ? "-" : centreId.toString()) + "|" + accountId;
    }

    static String lineLabel(BudgetLine l) {
        return (l.getCostCentre() == null ? "" : l.getCostCentre().getCode() + " · ") + l.getAccount().getCode() + " " + l.getAccount().getName();
    }

    private static void requireDraft(Budget b) {
        if (!b.isDraft()) {
            throw BusinessException.of("budget.notDraft", b.getNumber());
        }
    }

    private static void requireSubmitted(Budget b) {
        if (b.getStatus() != BudgetStatus.SUBMITTED) {
            throw BusinessException.of("budget.notSubmitted", b.getNumber());
        }
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

    private Budget lock(UUID id) {
        return repo.lockById(id).orElseThrow(() -> new NotFoundException("Budget", id));
    }

    private BudgetRevision lockRevision(UUID id) {
        revisionRepo.lockById(id).orElseThrow(() -> new NotFoundException("BudgetRevision", id));
        return revision(id);
    }

    static String link(Budget b) {
        return "/budgets/" + b.getId();
    }

    static String revisionLink(BudgetRevision r) {
        return "/budgets/revisions/" + r.getId();
    }
}
