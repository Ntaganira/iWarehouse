package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.Customer;
import com.ntaganira.heritier.iWarehouse.entity.CustomerReminder;
import com.ntaganira.heritier.iWarehouse.entity.JournalLine;
import com.ntaganira.heritier.iWarehouse.enums.AccountKey;
import com.ntaganira.heritier.iWarehouse.enums.DocumentType;
import com.ntaganira.heritier.iWarehouse.enums.JournalSource;
import com.ntaganira.heritier.iWarehouse.enums.SettingKey;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.AccountRepository;
import com.ntaganira.heritier.iWarehouse.repository.CustomerReminderRepository;
import com.ntaganira.heritier.iWarehouse.repository.CustomerRepository;
import com.ntaganira.heritier.iWarehouse.repository.JournalLineRepository;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : CreditControlService.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : Following up receivables (BP 5.1, Table 9; Annex D; F-02): the monthly movement of each customer's
 *               account (opening, new credit, collections, credit notes, write-offs, closing), the days sales
 *               outstanding and the share more than 90 days overdue against the Settings' ceiling, customer statements
 *               for a period, and payment reminders by how long the oldest charge is overdue (1: up to 30 days, 2: up to
 *               60, 3: a final notice), each kept with its figures. All from the receivable account's lines.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class CreditControlService {

    private final JournalLineRepository lineRepo;
    private final AccountRepository accountRepo;
    private final CustomerRepository customerRepo;
    private final CustomerReminderRepository reminderRepo;
    private final CustomerAccountService accountService;
    private final JournalService journalService;
    private final CustomerAgeing ageing;
    private final DocumentNumberService numbers;
    private final SettingService settingService;
    private final Clock clock;

    public CreditControlService(JournalLineRepository lineRepo, AccountRepository accountRepo, CustomerRepository customerRepo,
                                CustomerReminderRepository reminderRepo, CustomerAccountService accountService, JournalService journalService,
                                CustomerAgeing ageing, DocumentNumberService numbers, SettingService settingService, Clock clock) {
        this.lineRepo = lineRepo;
        this.accountRepo = accountRepo;
        this.customerRepo = customerRepo;
        this.reminderRepo = reminderRepo;
        this.accountService = accountService;
        this.journalService = journalService;
        this.ageing = ageing;
        this.numbers = numbers;
        this.settingService = settingService;
        this.clock = clock;
    }

    public record MovementRow(Customer customer, ReceivableMovement.Row row) {
    }

    /** The movement of receivables in a month, the largest closing balance first. */
    public record Movement(YearMonth month, List<MovementRow> rows, ReceivableMovement.Row total) {
    }

    /** What is owed, how much of it more than 90 days overdue against the ceiling, the days sales outstanding. */
    public record Kpis(BigDecimal receivables, BigDecimal overdue, BigDecimal over90, BigDecimal over90Percent, BigDecimal maxPercent,
                       BigDecimal creditSales, int days, BigDecimal dso) {

        public boolean isOver90Breached() {
            return over90Percent.compareTo(maxPercent) > 0;
        }
    }

    /** A customer with an overdue balance: the level of reminder due and the last one sent. */
    public record Due(Customer customer, Ageing.Result ageing, long oldestDays, int level, CustomerReminder last) {
    }

    /** A customer's statement for a period: the balance brought forward, the lines in the order posted with the balance after each. */
    public record Statement(Customer customer, LocalDate from, LocalDate to, BigDecimal opening,
                            List<CustomerAccountService.StatementRow> rows, BigDecimal closing, Ageing.Result ageing) {
    }

    // ---------------------------------------------------------------- the movement and the indicators

    public Movement movement(YearMonth month) {
        List<ReceivableMovement.Entry> entries = entries(month.atEndOfMonth());
        List<ReceivableMovement.Row> rows = ReceivableMovement.of(entries, month.atDay(1), month.atEndOfMonth());
        Map<UUID, Customer> customers = customerRepo.findAllById(rows.stream().map(ReceivableMovement.Row::customerId).toList()).stream()
                .collect(Collectors.toMap(Customer::getId, Function.identity()));
        List<MovementRow> list = new ArrayList<>(rows.stream().map(r -> new MovementRow(customers.get(r.customerId()), r)).toList());
        list.sort(Comparator.comparing((MovementRow r) -> r.row().getClosing()).reversed().thenComparing(r -> r.customer().getName()));
        return new Movement(month, list, ReceivableMovement.total(rows));
    }

    public Kpis kpis(CustomerAccountService.Receivables receivables) {
        LocalDate today = LocalDate.now(clock);
        int days = settingService.getInt(SettingKey.CREDIT_DSO_DAYS);
        BigDecimal creditSales = ReceivableMovement.creditSales(entries(today), today.minusDays(days - 1L), today);
        BigDecimal total = receivables.total();
        BigDecimal over90 = receivables.totals().getOrDefault(Ageing.Bucket.OVER_90, BigDecimal.ZERO);
        BigDecimal overdue = receivables.totals().entrySet().stream().filter(e -> e.getKey() != Ageing.Bucket.NOT_DUE)
                .map(Map.Entry::getValue).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Kpis(total, overdue, over90, ReceivableMovement.share(over90, total),
                settingService.getDecimal(SettingKey.CREDIT_OVER90_MAX_PERCENT), creditSales, days, ReceivableMovement.dso(total, creditSales, days));
    }

    // ---------------------------------------------------------------- statements

    public Statement statement(Customer customer, LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to)) {
            throw BusinessException.of("statement.period.invalid");
        }
        List<JournalLine> lines = journalService.customerLines(customer.getId());
        BigDecimal balance = BigDecimal.ZERO;
        List<CustomerAccountService.StatementRow> rows = new ArrayList<>();
        BigDecimal opening = null;
        for (JournalLine l : lines) {
            LocalDate date = l.getEntry().getEntryDate();
            if (date.isAfter(to)) {
                break;
            }
            if (!date.isBefore(from) && opening == null) {
                opening = balance;
            }
            balance = balance.add(l.getDebit()).subtract(l.getCredit());
            if (!date.isBefore(from)) {
                rows.add(new CustomerAccountService.StatementRow(l, balance));
            }
        }
        return new Statement(customer, from, to, opening == null ? balance : opening, rows, balance, ageing.of(customer, lines));
    }

    // ---------------------------------------------------------------- reminders

    /** The level of reminder for the oldest charge overdue so many days: 1 up to 30, 2 up to 60, 3 beyond (a final notice). */
    public static int levelFor(long oldestDays) {
        return oldestDays <= 30 ? 1 : oldestDays <= 60 ? 2 : 3;
    }

    /** Customers with an overdue balance, the most overdue first, with the reminder due and the last one sent. */
    public List<Due> remindersDue() {
        LocalDate today = LocalDate.now(clock);
        List<CustomerAccountService.Aged> aged = accountService.receivables().customers().stream()
                .filter(a -> a.ageing().getOverdue().signum() > 0 && a.ageing().oldestDue() != null).toList();
        Map<UUID, CustomerReminder> last = new HashMap<>();
        if (!aged.isEmpty()) {
            reminderRepo.findOfCustomers(aged.stream().map(a -> a.customer().getId()).toList())
                    .forEach(r -> last.putIfAbsent(r.getCustomer().getId(), r));
        }
        List<Due> due = new ArrayList<>();
        for (CustomerAccountService.Aged a : aged) {
            long days = Math.max(1, ChronoUnit.DAYS.between(a.ageing().oldestDue(), today));
            due.add(new Due(a.customer(), a.ageing(), days, levelFor(days), last.get(a.customer().getId())));
        }
        due.sort(Comparator.comparingLong(Due::oldestDays).reversed().thenComparing(d -> d.customer().getName()));
        return due;
    }

    /** Issues a reminder to a customer with an overdue balance, at the level its oldest charge calls for. */
    @Transactional
    public CustomerReminder issue(UUID customerId) {
        Customer customer = customerRepo.findById(customerId).orElseThrow(() -> new NotFoundException("Customer", customerId));
        LocalDate today = LocalDate.now(clock);
        Ageing.Result result = ageing.of(customer);
        if (result.getOverdue().signum() <= 0 || result.oldestDue() == null) {
            throw BusinessException.of("reminder.nothing", customer.getName());
        }
        long days = Math.max(1, ChronoUnit.DAYS.between(result.oldestDue(), today));
        AppUserPrincipal user = AppUserPrincipal.current().orElseThrow(() -> BusinessException.of("till.noUser"));
        CustomerReminder r = new CustomerReminder();
        r.setNumber(numbers.next(DocumentType.CUSTOMER_REMINDER));
        r.setCustomer(customer);
        r.setLevel(levelFor(days));
        r.setAsOf(today);
        r.setBalance(result.balance());
        r.setOverdue(result.getOverdue());
        r.setOldestDays((int) days);
        r.setCreatedAt(LocalDateTime.now(clock));
        r.setUserId(user.getId());
        r.setUsername(user.getUsername());
        return reminderRepo.save(r);
    }

    public Page<CustomerReminder> reminders(UUID customerId, int page, int size) {
        PageRequest request = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return customerId == null ? reminderRepo.findAllBy(request) : reminderRepo.findByCustomer_Id(customerId, request);
    }

    public CustomerReminder findReminder(UUID id) {
        return reminderRepo.findById(id).orElseThrow(() -> new NotFoundException("CustomerReminder", id));
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    // ---------------------------------------------------------------- helpers

    private List<ReceivableMovement.Entry> entries(LocalDate to) {
        UUID receivable = accountRepo.findBySystemKey(AccountKey.RECEIVABLE).orElseThrow(() -> new IllegalStateException("No receivable account"))
                .getId();
        return lineRepo.customerMovements(receivable, to).stream()
                .map(r -> new ReceivableMovement.Entry((UUID) r[0], (LocalDate) r[1], (JournalSource) r[2], (BigDecimal) r[3], (BigDecimal) r[4]))
                .toList();
    }
}
