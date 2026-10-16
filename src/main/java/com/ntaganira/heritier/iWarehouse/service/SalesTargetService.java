package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.SalesTarget;
import com.ntaganira.heritier.iWarehouse.entity.Territory;
import com.ntaganira.heritier.iWarehouse.entity.User;
import com.ntaganira.heritier.iWarehouse.enums.Market;
import com.ntaganira.heritier.iWarehouse.enums.SalesTargetScope;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.repository.SalesTargetRepository;
import com.ntaganira.heritier.iWarehouse.repository.TerritoryRepository;
import com.ntaganira.heritier.iWarehouse.repository.UserRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : SalesTargetService.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : Sales targets (SDP 15, BP 4.1, Annex D; T.07, T.09): month by month for the business, each territory (by its
 *               code), salesperson (by username) and market (domestic, export), set on a grid per year and scope
 *               (PERM_MANAGE_SALES_TARGETS, in the change log). The sales against target of a week or a month: the period's
 *               net sales (SalesReportService: each invoice less its credit notes, net of VAT) by the same keys, against
 *               the period's share of each month's target (Budgets.targetFor).
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class SalesTargetService {

    public static final String COMPANY = "COMPANY";
    /** The territory key of the sales of customers with no territory (shown as "No territory"). */
    public static final String NO_TERRITORY = "-";

    /** A row of the grid: its key, what it reads as, and the twelve months' targets. */
    public record GridRow(String key, String label, BigDecimal[] months) {
        public BigDecimal getTotal() {
            return Budgets.sum(months);
        }
    }

    /** One key's sales against its target in a period. */
    public record TargetRow(String key, String label, BigDecimal actual, BigDecimal target) {
        public BigDecimal getAchieved() {
            return Budgets.achieved(actual, target);
        }

        public BigDecimal getGap() {
            return actual.subtract(target);
        }

        public boolean isMet() {
            return target.signum() > 0 && actual.compareTo(target) >= 0;
        }
    }

    public record Report(LocalDate from, LocalDate to, boolean week, TargetRow company, List<TargetRow> territories, List<TargetRow> salespeople,
                         List<TargetRow> markets) {
    }

    private final SalesTargetRepository repo;
    private final TerritoryRepository territoryRepo;
    private final UserRepository userRepo;
    private final SalesReportService salesReport;
    private final Clock clock;

    public SalesTargetService(SalesTargetRepository repo, TerritoryRepository territoryRepo, UserRepository userRepo, SalesReportService salesReport,
                              Clock clock) {
        this.repo = repo;
        this.territoryRepo = territoryRepo;
        this.userRepo = userRepo;
        this.salesReport = salesReport;
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    /** The grid of a year and scope: every territory or market, the business, and the salespeople who have targets. */
    public List<GridRow> grid(int year, SalesTargetScope scope) {
        Map<String, BigDecimal[]> amounts = new LinkedHashMap<>();
        for (SalesTarget t : repo.findByTargetYearAndScope(year, scope)) {
            amounts.computeIfAbsent(t.getTargetKey(), k -> zeros())[t.getTargetMonth() - 1] = t.getAmount();
        }
        Map<String, String> labels = labels(scope);
        List<String> keys = new ArrayList<>();
        switch (scope) {
            case COMPANY -> keys.add(COMPANY);
            case MARKET -> Arrays.stream(Market.values()).forEach(m -> keys.add(m.name()));
            case TERRITORY -> territoryRepo.findByEnabledTrueOrderByCodeAsc().forEach(t -> keys.add(t.getCode()));
            case SALESPERSON -> { }
        }
        amounts.keySet().stream().filter(k -> !keys.contains(k)).sorted().forEach(keys::add);
        return keys.stream().map(k -> new GridRow(k, labels.getOrDefault(k, k), amounts.getOrDefault(k, zeros()))).toList();
    }

    /** Users who may be given a target and have none on the grid yet. */
    public List<User> salespeople(Collection<String> taken) {
        return userRepo.findAll(Sort.by("fullName")).stream().filter(User::isEnabled).filter(u -> !taken.contains(u.getUsername())).toList();
    }

    /** Saves a year's targets of one scope: each key's twelve months (0 or more). */
    @Transactional
    public int save(int year, SalesTargetScope scope, Map<String, List<BigDecimal>> rows) {
        Map<String, String> labels = labels(scope);
        Map<String, SalesTarget> existing = repo.findByTargetYearAndScope(year, scope).stream()
                .collect(Collectors.toMap(t -> t.getTargetKey() + "#" + t.getTargetMonth(), Function.identity()));
        int changed = 0;
        for (Map.Entry<String, List<BigDecimal>> row : rows.entrySet()) {
            String key = row.getKey();
            if (!labels.containsKey(key) && !(scope == SalesTargetScope.SALESPERSON && userRepo.findByUsername(key).isPresent())) {
                throw BusinessException.of("salesTarget.key.unknown", key);
            }
            List<BigDecimal> months = row.getValue();
            for (int m = 1; m <= 12; m++) {
                BigDecimal amount = m - 1 < months.size() && months.get(m - 1) != null ? months.get(m - 1).setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(2);
                if (amount.signum() < 0) {
                    throw BusinessException.of("salesTarget.amount.negative", labels.getOrDefault(key, key));
                }
                SalesTarget t = existing.get(key + "#" + m);
                if (t == null) {
                    if (amount.signum() == 0) {
                        continue;
                    }
                    t = new SalesTarget();
                    t.setTargetYear(year);
                    t.setTargetMonth(m);
                    t.setScope(scope);
                    t.setTargetKey(key);
                    t.setAmount(amount);
                    repo.save(t);
                    changed++;
                } else if (t.getAmount().compareTo(amount) != 0) {
                    t.setAmount(amount);
                    changed++;
                }
            }
        }
        return changed;
    }

    /** The week (Monday to Sunday) a day falls in, up to today. */
    public LocalDate[] week(LocalDate day) {
        LocalDate from = day.with(DayOfWeek.MONDAY);
        return new LocalDate[]{from, from.plusDays(6)};
    }

    /** Sales against target from one day to another (a week or a month): the business and each key of every scope. */
    public Report report(LocalDate from, LocalDate to, boolean week) {
        List<SalesAnalysis.Fact> facts = salesReport.factsOf(from, to);
        Map<SalesTargetScope, Map<String, BigDecimal>> actual = new EnumMap<>(SalesTargetScope.class);
        for (SalesTargetScope s : SalesTargetScope.values()) {
            actual.put(s, new HashMap<>());
        }
        for (SalesAnalysis.Fact f : facts) {
            actual.get(SalesTargetScope.COMPANY).merge(COMPANY, f.net(), BigDecimal::add);
            // Sales of no territory get their own row, so the territories add up to the business
            actual.get(SalesTargetScope.TERRITORY).merge(f.invoice().territoryCode() == null ? NO_TERRITORY : f.invoice().territoryCode(), f.net(),
                    BigDecimal::add);
            if (f.invoice().salesperson() != null) {
                actual.get(SalesTargetScope.SALESPERSON).merge(f.invoice().salesperson(), f.net(), BigDecimal::add);
            }
            actual.get(SalesTargetScope.MARKET).merge(f.invoice().market() == null ? Market.DOMESTIC.name() : f.invoice().market(), f.net(), BigDecimal::add);
        }
        Set<Integer> years = new HashSet<>();
        for (int y = from.getYear(); y <= to.getYear(); y++) {
            years.add(y);
        }
        Map<SalesTargetScope, List<TargetRow>> rows = new EnumMap<>(SalesTargetScope.class);
        for (SalesTargetScope scope : SalesTargetScope.values()) {
            Map<String, Map<YearMonth, BigDecimal>> monthly = new HashMap<>();
            for (SalesTarget t : repo.findByScopeAndTargetYearIn(scope, years)) {
                monthly.computeIfAbsent(t.getTargetKey(), k -> new HashMap<>()).put(YearMonth.of(t.getTargetYear(), t.getTargetMonth()), t.getAmount());
            }
            Set<String> keys = new TreeSet<>(monthly.keySet());
            keys.addAll(actual.get(scope).keySet());
            if (scope == SalesTargetScope.COMPANY) {
                keys.add(COMPANY);
            }
            Map<String, String> labels = labels(scope);
            List<TargetRow> list = new ArrayList<>();
            for (String key : keys) {
                list.add(new TargetRow(key, labels.getOrDefault(key, key), actual.get(scope).getOrDefault(key, BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP),
                        Budgets.targetFor(monthly.getOrDefault(key, Map.of()), from, to)));
            }
            list.sort(Comparator.comparing(TargetRow::actual).reversed().thenComparing(TargetRow::label));
            rows.put(scope, list);
        }
        return new Report(from, to, week, rows.get(SalesTargetScope.COMPANY).get(0), rows.get(SalesTargetScope.TERRITORY),
                rows.get(SalesTargetScope.SALESPERSON), rows.get(SalesTargetScope.MARKET));
    }

    // ---------------------------------------------------------------- helpers

    /** What the keys of a scope read as: a territory's code and name, a salesperson's name; markets are labelled by the page. */
    private Map<String, String> labels(SalesTargetScope scope) {
        Map<String, String> labels = new LinkedHashMap<>();
        switch (scope) {
            case COMPANY -> labels.put(COMPANY, COMPANY);
            case MARKET -> Arrays.stream(Market.values()).forEach(m -> labels.put(m.name(), m.name()));
            case TERRITORY -> {
                for (Territory t : territoryRepo.findAllByOrderByCodeAsc()) {
                    labels.put(t.getCode(), t.getCode() + " · " + t.getName());
                }
            }
            case SALESPERSON -> userRepo.findAll().forEach(u -> labels.put(u.getUsername(), u.getFullName() == null ? u.getUsername() : u.getFullName()));
        }
        return labels;
    }

    private static BigDecimal[] zeros() {
        BigDecimal[] months = new BigDecimal[12];
        Arrays.fill(months, BigDecimal.ZERO);
        return months;
    }
}
