package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.*;
import com.ntaganira.heritier.iWarehouse.enums.*;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.repository.*;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : BudgetReportService.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : The budget against actual (CMP 04, 10; Annex D; T.03, T.11, T.13, C.12): for a month of the approved budget's
 *               year, each line's budget and actual (the journals: revenue credits less debits, expenses debits less
 *               credits, by account and cost centre) for the month and the year to date, the variance and its share,
 *               flagged beyond the Settings' tolerance (Budgets.Variance); what was posted with no budget line; the COO's
 *               indicators (turnover and operating cost against budget, the gross margin against the Settings' target) and
 *               supply chain's (count accuracy, landed cost against the year's landed cost budget to date, demurrage and
 *               storage per consignment against the ceiling). A month is signed off once after it ends (PERM_REVIEW_BUDGET,
 *               a note when lines are flagged); not signed off by the Settings' day of the next month, ALERT_BUDGET
 *               holders are told once.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class BudgetReportService {

    static final String REVIEW_PREFIX = "BUDGET_REVIEW:";
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MM/yyyy");

    /** A budget line against actual: the month and the year to date. */
    public record Row(String centreCode, String centreName, String accountCode, String accountName, boolean revenue, Budgets.Variance month,
                      Budgets.Variance toDate, boolean flagged) {
    }

    /** Spending or revenue on a cost centre and account the budget has no line for. */
    public record Unbudgeted(String centreCode, String accountCode, String accountName, boolean revenue, BigDecimal month, BigDecimal toDate) {
    }

    /** A consignment's demurrage and storage against the ceiling (C.12). */
    public record Consignment(UUID shipmentId, String number, BigDecimal demurrage, BigDecimal storage, BigDecimal ceiling) {
        public BigDecimal getTotal() {
            return demurrage.add(storage);
        }

        public boolean isOver() {
            return ceiling.signum() > 0 && getTotal().compareTo(ceiling) > 0;
        }
    }

    /** The COO's indicators (Annex D) for the year to date. */
    public record Coo(Budgets.Variance turnover, BigDecimal marginPercent, BigDecimal marginTarget, Budgets.Variance operatingCost) {
        public boolean isMarginMet() {
            return marginPercent != null && marginPercent.compareTo(marginTarget) >= 0;
        }
    }

    /** Supply chain's indicators (Annex D): count accuracy of the month, landed cost to date, consignments over the ceiling. */
    public record SupplyChain(BigDecimal countAccuracy, Budgets.Variance landedCost, List<Consignment> consignments) {
        public long getOverCeiling() {
            return consignments.stream().filter(Consignment::isOver).count();
        }
    }

    public record Report(Budget budget, int year, int month, List<Row> rows, List<Unbudgeted> unbudgeted, Coo coo, SupplyChain supplyChain,
                         BigDecimal tolerance, BudgetReview review, boolean ended, LocalDate reviewDue) {

        public long getFlagged() {
            return rows.stream().filter(Row::flagged).count() + unbudgeted.size();
        }
    }

    private final BudgetService budgets;
    private final BudgetLineRepository lineRepo;
    private final BudgetReviewRepository reviewRepo;
    private final AccountRepository accountRepo;
    private final CostCentreRepository costCentreRepo;
    private final JournalLineRepository journalLineRepo;
    private final ShipmentCostRepository shipmentCostRepo;
    private final InventoryKpiService kpiService;
    private final FinancialStatementService statements;
    private final AlertStateRepository alertRepo;
    private final SettingService settings;
    private final Notifier notifier;
    private final Clock clock;

    public BudgetReportService(BudgetService budgets, BudgetLineRepository lineRepo, BudgetReviewRepository reviewRepo, AccountRepository accountRepo,
                               CostCentreRepository costCentreRepo, JournalLineRepository journalLineRepo, ShipmentCostRepository shipmentCostRepo,
                               InventoryKpiService kpiService, FinancialStatementService statements, AlertStateRepository alertRepo,
                               SettingService settings, Notifier notifier, Clock clock) {
        this.budgets = budgets;
        this.lineRepo = lineRepo;
        this.reviewRepo = reviewRepo;
        this.accountRepo = accountRepo;
        this.costCentreRepo = costCentreRepo;
        this.journalLineRepo = journalLineRepo;
        this.shipmentCostRepo = shipmentCostRepo;
        this.kpiService = kpiService;
        this.statements = statements;
        this.alertRepo = alertRepo;
        this.settings = settings;
        this.notifier = notifier;
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    /** The month the report opens on: the last month that ended. */
    public YearMonth defaultMonth() {
        return YearMonth.from(today()).minusMonths(1);
    }

    /** The year's approved budget against actual for a month; empty without an approved budget. */
    public Optional<Report> report(int year, int month) {
        Optional<Budget> approved = budgets.approvedFor(year);
        if (approved.isEmpty()) {
            return Optional.empty();
        }
        Budget budget = approved.get();
        YearMonth ym = YearMonth.of(year, month);
        BigDecimal tolerance = settings.getDecimal(SettingKey.BUDGET_VARIANCE_PERCENT);
        Map<UUID, Account> accounts = accountRepo.findAllByOrderByCodeAsc().stream().collect(Collectors.toMap(Account::getId, Function.identity()));
        Map<UUID, CostCentre> centres = costCentreRepo.findAll().stream().collect(Collectors.toMap(CostCentre::getId, Function.identity()));
        Map<String, BigDecimal> monthActual = actuals(ym.atDay(1), ym.atEndOfMonth(), accounts);
        Map<String, BigDecimal> toDateActual = actuals(LocalDate.of(year, 1, 1), ym.atEndOfMonth(), accounts);

        List<Row> rows = new ArrayList<>();
        Set<String> budgeted = new HashSet<>();
        BigDecimal revenueBudget = BigDecimal.ZERO, revenueActual = BigDecimal.ZERO, opexBudget = BigDecimal.ZERO, opexActual = BigDecimal.ZERO;
        for (BudgetLine l : budgets.lines(budget.getId())) {
            UUID centreId = l.getCostCentre() == null ? null : l.getCostCentre().getId();
            String key = BudgetService.key(centreId, l.getAccount().getId());
            budgeted.add(key);
            boolean revenue = l.getAccount().getType() == AccountType.REVENUE;
            Budgets.Variance m = new Budgets.Variance(l.month(month), monthActual.getOrDefault(key, BigDecimal.ZERO));
            Budgets.Variance ytd = new Budgets.Variance(Budgets.toDate(l.getMonths(), month), toDateActual.getOrDefault(key, BigDecimal.ZERO));
            rows.add(new Row(l.getCostCentre() == null ? null : l.getCostCentre().getCode(), l.getCostCentre() == null ? null : l.getCostCentre().getName(),
                    l.getAccount().getCode(), l.getAccount().getName(), revenue, m, ytd, m.flagged(tolerance) || ytd.flagged(tolerance)));
            if (revenue) {
                revenueBudget = revenueBudget.add(ytd.budget());
            } else if (!isCostOfSales(l.getAccount())) {
                opexBudget = opexBudget.add(ytd.budget());
            }
        }
        List<Unbudgeted> unbudgeted = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>(toDateActual.keySet());
        for (String key : keys) {
            String[] parts = key.split("\\|");
            Account account = accounts.get(UUID.fromString(parts[1]));
            boolean revenue = account.getType() == AccountType.REVENUE;
            BigDecimal ytd = toDateActual.get(key);
            if (revenue) {
                revenueActual = revenueActual.add(ytd);
            } else if (!isCostOfSales(account) && !ExpenditureReportService.NOT_SPENDING.contains(account.getSystemKey())) {
                opexActual = opexActual.add(ytd);
            }
            if (budgeted.contains(key) || ytd.signum() == 0 || (!revenue && ExpenditureReportService.NOT_SPENDING.contains(account.getSystemKey()))
                    || isCostOfSales(account)) {
                continue;
            }
            CostCentre c = "-".equals(parts[0]) ? null : centres.get(UUID.fromString(parts[0]));
            unbudgeted.add(new Unbudgeted(c == null ? null : c.getCode(), account.getCode(), account.getName(), revenue,
                    monthActual.getOrDefault(key, BigDecimal.ZERO), ytd));
        }
        unbudgeted.sort(Comparator.comparing(Unbudgeted::accountCode).thenComparing(u -> u.centreCode() == null ? "" : u.centreCode()));

        FinancialStatements.IncomeStatement income = statements.incomeStatement(LocalDate.of(year, 1, 1), ym.atEndOfMonth());
        Coo coo = new Coo(new Budgets.Variance(revenueBudget, revenueActual), Budgets.marginPercent(income.getTotalRevenue(), income.getTotalCostOfSales()),
                settings.getDecimal(SettingKey.BUDGET_MARGIN_TARGET), new Budgets.Variance(opexBudget, opexActual));

        // Supply chain: count accuracy of the month, landed cost to date against the budget pro rata, consignments' demurrage
        BigDecimal accuracy = null;
        try {
            InventoryKpis.Kpi kpi = kpiService.measure(ym.atDay(1), ym.atEndOfMonth()).get(InventoryKpis.Code.ACCURACY);
            accuracy = kpi == null ? null : kpi.value();
        } catch (RuntimeException e) {
            // no count in the month: no accuracy
        }
        BigDecimal landed = BigDecimal.ZERO;
        Map<UUID, BigDecimal[]> byShipment = new LinkedHashMap<>();
        Map<UUID, String> numbers = new HashMap<>();
        for (ShipmentCost c : shipmentCostRepo.postedIn(LocalDate.of(year, 1, 1), ym.atEndOfMonth())) {
            BigDecimal rwf = c.getRate() == null ? c.getAmount() : c.getAmount().multiply(c.getRate()).setScale(2, RoundingMode.HALF_UP);
            landed = landed.add(rwf);
            if (c.getCostType() == CostType.DEMURRAGE || c.getCostType() == CostType.STORAGE) {
                BigDecimal[] sums = byShipment.computeIfAbsent(c.getShipment().getId(), k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                sums[c.getCostType() == CostType.DEMURRAGE ? 0 : 1] = sums[c.getCostType() == CostType.DEMURRAGE ? 0 : 1].add(rwf);
                numbers.put(c.getShipment().getId(), c.getShipment().getNumber());
            }
        }
        List<Consignment> consignments = new ArrayList<>();
        byShipment.forEach((id, sums) -> consignments.add(new Consignment(id, numbers.get(id), sums[0], sums[1], budget.getDemurrageCeiling())));
        SupplyChain supply = new SupplyChain(accuracy, new Budgets.Variance(Budgets.proRata(budget.getLandedCostBudget(), month), landed), consignments);

        return Optional.of(new Report(budget, year, month, rows, unbudgeted, coo, supply, tolerance,
                reviewRepo.findByReviewYearAndReviewMonth(year, month).orElse(null), ym.isBefore(YearMonth.from(today())), reviewDue(ym)));
    }

    /** The day a month's review is due: the Settings' day of the next month. */
    public LocalDate reviewDue(YearMonth month) {
        YearMonth next = month.plusMonths(1);
        return next.atDay(Math.min(next.lengthOfMonth(), Math.max(1, settings.getInt(SettingKey.BUDGET_REVIEW_DAY))));
    }

    /** Signs a month off once it has ended (T.03), with a note when lines are flagged. */
    @Transactional
    public BudgetReview signOff(int year, int month, String note) {
        YearMonth ym = YearMonth.of(year, month);
        if (!ym.isBefore(YearMonth.from(today()))) {
            throw BusinessException.of("budgetReport.notEnded", ym.format(MONTH));
        }
        if (reviewRepo.findByReviewYearAndReviewMonth(year, month).isPresent()) {
            throw BusinessException.of("budgetReport.signed", ym.format(MONTH));
        }
        Report r = report(year, month).orElseThrow(() -> BusinessException.of("budgetReport.noBudget", String.valueOf(year)));
        String clean = PartyRules.clean(note);
        int flagged = (int) r.getFlagged();
        if (flagged > 0 && clean == null) {
            throw BusinessException.onField("note", "budgetReport.noteRequired", flagged);
        }
        if (clean != null && clean.length() > 1000) {
            throw BusinessException.onField("note", "budgetReport.noteSize");
        }
        Optional<AppUserPrincipal> me = AppUserPrincipal.current();
        BudgetReview review = new BudgetReview();
        review.setReviewYear(year);
        review.setReviewMonth(month);
        review.setFlagged(flagged);
        review.setSignedBy(me.map(AppUserPrincipal::getUsername).orElse("system"));
        review.setSignedById(me.map(AppUserPrincipal::getId).orElse(null));
        review.setSignedAt(LocalDateTime.now(clock));
        review.setNote(clean);
        AlertOnce.clear(alertRepo, REVIEW_PREFIX + ym, LocalDateTime.now(clock));
        return reviewRepo.save(review);
    }

    /** A month of an approved budget not signed off by its day: ALERT_BUDGET holders are told once. */
    @Scheduled(fixedDelayString = "${app.alerts.budget-every:PT6H}", initialDelayString = "${app.alerts.first-check:PT1M}")
    @Transactional
    public int checkReviews() {
        YearMonth last = YearMonth.from(today()).minusMonths(1);
        if (budgets.approvedFor(last.getYear()).isEmpty() || !today().isAfter(reviewDue(last))) {
            return 0;
        }
        if (reviewRepo.findByReviewYearAndReviewMonth(last.getYear(), last.getMonthValue()).isEmpty()
                && AlertOnce.raise(alertRepo, REVIEW_PREFIX + last, LocalDateTime.now(clock))) {
            notifier.holders("ALERT_BUDGET", null, NotificationKind.BUDGET, "notify.budget.review", "notify.budget.reviewText",
                    "/reports/budget?year=" + last.getYear() + "&month=" + last.getMonthValue(), last.format(MONTH), reviewDue(last).format(
                            DateTimeFormatter.ofPattern("dd/MM/yyyy")));
            return 1;
        }
        return 0;
    }

    // ---------------------------------------------------------------- helpers

    /** Actual by cost centre and account: revenue as credits less debits, expenses as debits less credits. */
    private Map<String, BigDecimal> actuals(LocalDate from, LocalDate to, Map<UUID, Account> accounts) {
        Map<String, BigDecimal> actual = new HashMap<>();
        for (Object[] r : journalLineRepo.movementsByAccountAndCentre(from, to, EnumSet.of(AccountType.REVENUE, AccountType.EXPENSE))) {
            Account account = accounts.get((UUID) r[0]);
            BigDecimal debits = (BigDecimal) r[2], credits = (BigDecimal) r[3];
            BigDecimal amount = account.getType() == AccountType.REVENUE ? credits.subtract(debits) : debits.subtract(credits);
            actual.merge(BudgetService.key((UUID) r[1], account.getId()), amount, BigDecimal::add);
        }
        return actual;
    }

    private static boolean isCostOfSales(Account account) {
        return account.getSystemKey() == AccountKey.COGS;
    }
}
