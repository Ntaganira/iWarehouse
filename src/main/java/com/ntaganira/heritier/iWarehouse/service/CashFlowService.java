package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.CashForecastItem;
import com.ntaganira.heritier.iWarehouse.entity.Customer;
import com.ntaganira.heritier.iWarehouse.entity.PayRun;
import com.ntaganira.heritier.iWarehouse.enums.*;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.CashForecastItemRepository;
import com.ntaganira.heritier.iWarehouse.repository.CustomerRepository;
import com.ntaganira.heritier.iWarehouse.repository.JournalLineRepository;
import com.ntaganira.heritier.iWarehouse.repository.PayRunRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : CashFlowService.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : The rolling cash-flow forecast and the weekly cash report (P.11). The forecast opens with the cash the ledger holds
 *               today (the vault, the bank, mobile money, petty cash, the tills and the drivers' floats) and looks the Settings'
 *               weeks ahead: what customers owe by due date (Ageing.open at their terms, extended terms included), what is owed
 *               to suppliers by due date (in RWF as booked), the net pay at each month's end (the month's posted run, else the
 *               latest), RRA and RSSB on the 15th (what the PAYE, RSSB and withholding accounts owe now, then the latest run's),
 *               and the planned items (CashForecastItem, PERM_MANAGE_CASH_FORECAST). The weekly cash report reads the money
 *               accounts' journals week by week (Monday to Sunday): what came in and went out by kind of document, money moved
 *               between them left out.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class CashFlowService {

    /** The money accounts. */
    public static final Set<AccountKey> MONEY = Collections.unmodifiableSet(EnumSet.of(AccountKey.CASH, AccountKey.CASH_VAULT, AccountKey.BANK,
            AccountKey.MOBILE_MONEY, AccountKey.PETTY_CASH, AccountKey.DRIVER_FLOAT));
    private static final Set<AccountKey> TAXES_OWED = EnumSet.of(AccountKey.PAYE_PAYABLE, AccountKey.RSSB_PAYABLE, AccountKey.WHT_PAYABLE);
    private static final List<PayRunStatus> POSTED = List.of(PayRunStatus.POSTED, PayRunStatus.PAID);
    static final int TAX_DAY = 15;

    private final CashForecastItemRepository itemRepo;
    private final CustomerRepository customerRepo;
    private final CustomerAgeing ageing;
    private final SupplierAccountService suppliers;
    private final PayRunRepository payRunRepo;
    private final JournalLineRepository lineRepo;
    private final SettingService settings;
    private final Clock clock;

    public CashFlowService(CashForecastItemRepository itemRepo, CustomerRepository customerRepo, CustomerAgeing ageing,
                           SupplierAccountService suppliers, PayRunRepository payRunRepo, JournalLineRepository lineRepo,
                           SettingService settings, Clock clock) {
        this.itemRepo = itemRepo;
        this.customerRepo = customerRepo;
        this.ageing = ageing;
        this.suppliers = suppliers;
        this.payRunRepo = payRunRepo;
        this.lineRepo = lineRepo;
        this.settings = settings;
        this.clock = clock;
    }

    /** The forecast: its weeks, the cash it opens with, and what is overdue already (in its first week). */
    public record Forecast(LocalDate start, List<CashFlow.Week> weeks, BigDecimal opening, BigDecimal overdueReceivables,
                           BigDecimal overduePayables, PayRun payrollBasis) {

        public LocalDate getEnd() {
            return weeks.isEmpty() ? start : weeks.get(weeks.size() - 1).to();
        }

        public BigDecimal getTotalIn() {
            return weeks.stream().map(CashFlow.Week::getTotalIn).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        public BigDecimal getTotalOut() {
            return weeks.stream().map(CashFlow.Week::getTotalOut).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        public BigDecimal getClosing() {
            return weeks.isEmpty() ? opening : weeks.get(weeks.size() - 1).getClosing();
        }

        public BigDecimal getTotal(CashFlow.Kind kind) {
            return weeks.stream().map(w -> w.get(kind)).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        /** The planned items over the forecast, in less out. */
        public BigDecimal getTotalPlanned() {
            return getTotal(CashFlow.Kind.PLANNED_IN).subtract(getTotal(CashFlow.Kind.PLANNED_OUT));
        }

        /** The first week closing below zero, or null. */
        public CashFlow.Week getFirstShort() {
            return weeks.stream().filter(CashFlow.Week::isShort).findFirst().orElse(null);
        }

        public BigDecimal getLowest() {
            return weeks.stream().map(CashFlow.Week::getClosing).min(Comparator.naturalOrder()).orElse(opening);
        }
    }

    /** A week of actual cash: what it opened with, what came in and went out by kind of document. */
    public record CashWeek(LocalDate from, LocalDate to, BigDecimal opening, Map<JournalSource, BigDecimal> in, Map<JournalSource, BigDecimal> out) {

        public BigDecimal getTotalIn() {
            return in.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        public BigDecimal getTotalOut() {
            return out.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        public BigDecimal getClosing() {
            return opening.add(getTotalIn()).subtract(getTotalOut());
        }

        /** The kinds of document that moved cash this week, in and out. */
        public Set<JournalSource> getSources() {
            Set<JournalSource> sources = new TreeSet<>(Comparator.comparing(Enum::name));
            sources.addAll(in.keySet());
            sources.addAll(out.keySet());
            return sources;
        }
    }

    /** A planned item as the form gives it. */
    public record ItemForm(LocalDate expectedOn, CashDirection direction, String description, BigDecimal amount, CashRepeat repeats, LocalDate until) {
    }

    // ---------------------------------------------------------------- the forecast

    public Forecast forecast() {
        LocalDate start = today();
        int weeks = Math.max(1, Math.min(52, settings.getInt(SettingKey.CASHFLOW_WEEKS)));
        LocalDate end = start.plusDays(7L * weeks - 1);
        List<CashFlow.Flow> flows = new ArrayList<>();

        // What customers owe, by due date at their terms
        BigDecimal overdueIn = BigDecimal.ZERO;
        Map<UUID, List<Ageing.Entry>> entries = ageing.entries();
        Map<UUID, Customer> customers = customerRepo.findAllById(entries.keySet()).stream().collect(Collectors.toMap(Customer::getId, Function.identity()));
        for (Map.Entry<UUID, List<Ageing.Entry>> e : entries.entrySet()) {
            Customer c = customers.get(e.getKey());
            for (Ageing.Due d : Ageing.open(e.getValue(), c == null ? 0 : c.getPaymentTermsDays())) {
                flows.add(new CashFlow.Flow(d.due(), CashFlow.Kind.RECEIVABLES, d.amount()));
                if (d.due().isBefore(start)) {
                    overdueIn = overdueIn.add(d.amount());
                }
            }
        }

        // What is owed to suppliers, by due date
        BigDecimal overdueOut = BigDecimal.ZERO;
        for (Ageing.Due d : suppliers.payablesDue()) {
            flows.add(new CashFlow.Flow(d.due(), CashFlow.Kind.PAYABLES, d.amount()));
            if (d.due().isBefore(start)) {
                overdueOut = overdueOut.add(d.amount());
            }
        }

        // The net pay at each month's end; RRA and RSSB on the 15th
        PayRun latest = payRunRepo.findFirstByStatusInOrderByPeriodDesc(POSTED).orElse(null);
        for (LocalDate monthEnd : CashFlow.monthEnds(start, end)) {
            Optional<PayRun> run = payRunRepo.findFirstByPeriodAndStatusIn(monthEnd.withDayOfMonth(1), POSTED);
            if (run.isPresent()) {
                if (run.get().getStatus() == PayRunStatus.POSTED) {
                    flows.add(new CashFlow.Flow(monthEnd, CashFlow.Kind.PAYROLL, run.get().getTotalNet()));
                }
            } else if (latest != null) {
                flows.add(new CashFlow.Flow(monthEnd, CashFlow.Kind.PAYROLL, latest.getTotalNet()));
            }
        }
        boolean first = true;
        for (LocalDate taxDay : CashFlow.occurrences(start.withDayOfMonth(TAX_DAY), CashRepeat.MONTHLY, null, start, end)) {
            BigDecimal owed;
            if (first) {
                owed = lineRepo.balanceOfKeysAt(TAXES_OWED, start).negate().max(BigDecimal.ZERO);       // what the accounts owe now
                first = false;
            } else {
                owed = latest == null ? BigDecimal.ZERO : latest.getTotalPaye().add(latest.getTotalRssb());
            }
            flows.add(new CashFlow.Flow(taxDay, CashFlow.Kind.TAXES, owed));
        }

        // The planned items
        for (CashForecastItem item : items()) {
            CashFlow.Kind kind = item.getDirection() == CashDirection.IN ? CashFlow.Kind.PLANNED_IN : CashFlow.Kind.PLANNED_OUT;
            for (LocalDate day : CashFlow.occurrences(item.getExpectedOn(), item.getRepeats(), item.getUntil(), start, end)) {
                flows.add(new CashFlow.Flow(day, kind, item.getAmount()));
            }
        }
        BigDecimal opening = lineRepo.balanceOfKeysAt(MONEY, start);
        return new Forecast(start, CashFlow.forecast(start, weeks, opening, flows), opening, overdueIn, overdueOut, latest);
    }

    // ---------------------------------------------------------------- the weekly cash report

    /** The Monday of a day's week. */
    public static LocalDate mondayOf(LocalDate day) {
        return day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /** {@code count} weeks of actual cash, Monday to Sunday, the last one the week of {@code lastMonday}; oldest first. */
    public List<CashWeek> weeks(LocalDate lastMonday, int count) {
        LocalDate from = lastMonday.minusWeeks(count - 1L);
        LocalDate to = lastMonday.plusDays(6);
        List<Map<JournalSource, BigDecimal>> ins = new ArrayList<>();
        List<Map<JournalSource, BigDecimal>> outs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ins.add(new EnumMap<>(JournalSource.class));
            outs.add(new EnumMap<>(JournalSource.class));
        }
        for (Object[] r : lineRepo.cashByJournal(MONEY, from, to)) {
            LocalDate date = (LocalDate) r[0];
            JournalSource source = (JournalSource) r[1];
            BigDecimal net = (BigDecimal) r[2];
            int week = (int) (ChronoUnit.DAYS.between(from, date) / 7);
            if (net.signum() > 0) {
                ins.get(week).merge(source, net, BigDecimal::add);
            } else if (net.signum() < 0) {
                outs.get(week).merge(source, net.negate(), BigDecimal::add);
            }
        }
        List<CashWeek> weeks = new ArrayList<>();
        BigDecimal opening = lineRepo.balanceOfKeysAt(MONEY, from.minusDays(1));
        for (int i = 0; i < count; i++) {
            LocalDate monday = from.plusWeeks(i);
            CashWeek w = new CashWeek(monday, monday.plusDays(6), opening, ins.get(i), outs.get(i));
            weeks.add(w);
            opening = w.getClosing();
        }
        return weeks;
    }

    // ---------------------------------------------------------------- planned items

    public List<CashForecastItem> items() {
        return itemRepo.findByStatusOrderByExpectedOnAscDescriptionAsc("ACTIVE");
    }

    @Transactional
    public CashForecastItem add(ItemForm form) {
        if (form.expectedOn() == null) {
            throw BusinessException.onField("expectedOn", "cashflow.item.date.required");
        }
        if (form.direction() == null) {
            throw BusinessException.onField("direction", "cashflow.item.direction.required");
        }
        String description = PartyRules.clean(form.description());
        if (description == null || description.length() > 200) {
            throw BusinessException.onField("description", "cashflow.item.description.required");
        }
        if (form.amount() == null || form.amount().signum() <= 0 || form.amount().stripTrailingZeros().scale() > 2) {
            throw BusinessException.onField("amount", "cashflow.item.amount.invalid");
        }
        CashRepeat repeats = form.repeats() == null ? CashRepeat.NONE : form.repeats();
        if (repeats != CashRepeat.NONE && form.until() != null && form.until().isBefore(form.expectedOn())) {
            throw BusinessException.onField("until", "cashflow.item.until.early");
        }
        CashForecastItem item = new CashForecastItem();
        item.setExpectedOn(form.expectedOn());
        item.setDirection(form.direction());
        item.setDescription(description);
        item.setAmount(form.amount().setScale(2, RoundingMode.HALF_UP));
        item.setRepeats(repeats);
        item.setUntil(repeats == CashRepeat.NONE ? null : form.until());
        return itemRepo.save(item);
    }

    @Transactional
    public CashForecastItem cancel(UUID id) {
        CashForecastItem item = itemRepo.lockById(id).orElseThrow(() -> new NotFoundException("CashForecastItem", id));
        if (!item.isActive()) {
            throw BusinessException.of("cashflow.item.cancelled", item.getDescription());
        }
        item.setStatus("CANCELLED");
        return item;
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }
}
