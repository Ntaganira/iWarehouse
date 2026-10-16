package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.config.NumberFormats;
import com.ntaganira.heritier.iWarehouse.dto.PayRunLinesDto;
import com.ntaganira.heritier.iWarehouse.dto.PayslipRow;
import com.ntaganira.heritier.iWarehouse.entity.*;
import com.ntaganira.heritier.iWarehouse.enums.AccountKey;
import com.ntaganira.heritier.iWarehouse.enums.DocumentType;
import com.ntaganira.heritier.iWarehouse.enums.NotificationKind;
import com.ntaganira.heritier.iWarehouse.enums.PayRunStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.*;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : PayRunService.java
 * - Date      : 2026. 10. 11.
 * - User      : Hntaganira
 * - Desc      : Pay runs (payroll). A run (PAY-WH-2026-000001) is made for a month with a line per employee working in it,
 *               from their salary and the rates in force on the month's last day; while a draft the month's overtime,
 *               bonus and deductions change and every line is worked out again (Payroll). Submitted, another person
 *               approves it (never who prepared or submitted it): its journal posts on the month's last day
 *               (PostingService.payroll), or they send it back with a reason. Then the salaries are paid (the payment
 *               journal). A posted run never changes (trg_pay_run_lines_posted): a correction goes into the next run.
 *               A driver's shortage is recovered from their pay, at most what they owe. Changes lock the run first.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class PayRunService {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MM/yyyy");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final PayRunRepository repo;
    private final PayRunLineRepository lineRepo;
    private final EmployeeRepository employeeRepo;
    private final PayrollRateService rates;
    private final DriverRepository driverRepo;
    private final JournalEntryRepository entryRepo;
    private final JournalService journalService;
    private final PostingService postings;
    private final StaffAdvanceService advances;
    private final DocumentNumberService numbers;
    private final PeriodLock periods;
    private final Notifier notifier;
    private final NumberFormats num;
    private final Clock clock;

    public PayRunService(PayRunRepository repo, PayRunLineRepository lineRepo, EmployeeRepository employeeRepo, PayrollRateService rates,
                         DriverRepository driverRepo, JournalEntryRepository entryRepo, JournalService journalService, PostingService postings,
                         StaffAdvanceService advances, DocumentNumberService numbers, PeriodLock periods, Notifier notifier, NumberFormats num,
                         Clock clock) {
        this.repo = repo;
        this.lineRepo = lineRepo;
        this.employeeRepo = employeeRepo;
        this.rates = rates;
        this.driverRepo = driverRepo;
        this.entryRepo = entryRepo;
        this.journalService = journalService;
        this.postings = postings;
        this.advances = advances;
        this.numbers = numbers;
        this.periods = periods;
        this.notifier = notifier;
        this.num = num;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- reading

    public Page<PayRun> findPage(String search, String status, int page, int size) {
        Specification<PayRun> spec = (root, query, cb) -> {
            Predicate p = cb.conjunction();
            if (StringUtils.hasText(search)) {
                p = cb.and(p, cb.like(cb.lower(root.get("number")), "%" + search.trim().toLowerCase(Locale.ROOT) + "%"));
            }
            PayRunStatus s = status(status);
            if (s != null) {
                p = cb.and(p, cb.equal(root.get("status"), s));
            }
            return p;
        };
        return repo.findAll(spec, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "period").and(Sort.by(Sort.Direction.DESC, "number"))));
    }

    public PayRun findDetailed(UUID id) {
        return repo.findDetailedById(id).orElseThrow(() -> new NotFoundException("PayRun", id));
    }

    /** Its lines, by employee code. */
    public List<PayRunLine> lines(UUID id) {
        return lineRepo.findByPayRunIdOrderByEmployeeCode(id);
    }

    /** Its journal and, once paid, the payment's. */
    public List<JournalEntry> journals(PayRun run) {
        List<JournalEntry> journals = new ArrayList<>();
        if (run.getJournalId() != null) {
            entryRepo.findById(run.getJournalId()).ifPresent(journals::add);
        }
        if (run.getPaymentJournalId() != null) {
            entryRepo.findById(run.getPaymentJournalId()).ifPresent(journals::add);
        }
        return journals;
    }

    public long pendingCount() {
        return repo.countByStatus(PayRunStatus.SUBMITTED);
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    /** The months a run can be made for: from a year back to next month, without a run yet, the next one to run first. */
    public List<LocalDate> openMonths() {
        LocalDate first = today().withDayOfMonth(1);
        List<LocalDate> months = new ArrayList<>();
        for (LocalDate m = first.plusMonths(1); !m.isBefore(first.minusMonths(12)); m = m.minusMonths(1)) {
            if (!repo.existsByPeriodAndStatusNot(m, PayRunStatus.CANCELLED)) {
                months.add(m);
            }
        }
        return months;
    }

    /** The month to offer first: the one after the latest run, else this month. */
    public LocalDate suggestedMonth() {
        return repo.findFirstByStatusNotOrderByPeriodDesc(PayRunStatus.CANCELLED).map(r -> r.getPeriod().plusMonths(1))
                .orElse(today().withDayOfMonth(1));
    }

    /** What each driver on the run still owes on Driver Shortage Receivable, by line. */
    public Map<UUID, BigDecimal> shortagesOwed(List<PayRunLine> lines) {
        Map<UUID, BigDecimal> balances = null;
        Map<UUID, BigDecimal> owed = new HashMap<>();
        for (PayRunLine l : lines) {
            if (l.getDriverId() != null) {
                if (balances == null) {
                    balances = journalService.driverBalances(AccountKey.DRIVER_SHORTAGE);
                }
                owed.put(l.getId(), balances.getOrDefault(l.getDriverId(), BigDecimal.ZERO).max(BigDecimal.ZERO));
            }
        }
        return owed;
    }

    /** The user signed in prepared or submitted it: they may not decide it. */
    public boolean isOwn(PayRun run) {
        Long me = AppUserPrincipal.current().map(AppUserPrincipal::getId).orElse(null);
        String username = AppUserPrincipal.currentUsername();
        boolean prepared = me != null && me.equals(run.getPreparedById()) || run.getPreparedBy().equals(username);
        boolean submitted = me != null && me.equals(run.getSubmittedById()) || username.equals(run.getSubmittedBy());
        return prepared || submitted;
    }

    /** The payslips of the employee this user signs in as (posted and paid runs). */
    public Page<PayslipRow> payslipsOf(Long userId, int page, int size) {
        return lineRepo.findPayslipsOfUser(userId, EmployeeService.PAID_OUT, PageRequest.of(page, size));
    }

    /** One of this user's own payslips, from a posted or paid run; anything else is not found. */
    public PayRunLine payslipOf(UUID lineId, Long userId) {
        PayRunLine line = lineRepo.findOfUser(lineId, userId).orElseThrow(() -> new NotFoundException("Payslip", lineId));
        if (!findDetailed(line.getPayRunId()).isPosted()) {
            throw new NotFoundException("Payslip", lineId);
        }
        return line;
    }

    public PayRunLine line(PayRun run, UUID lineId) {
        PayRunLine line = lineRepo.findById(lineId).orElseThrow(() -> new NotFoundException("PayRunLine", lineId));
        if (!line.getPayRunId().equals(run.getId())) {
            throw new NotFoundException("PayRunLine", lineId);
        }
        return line;
    }

    // ---------------------------------------------------------------- preparing

    /** A month's run: a line per employee working in it, at the rates in force on its last day. */
    @Transactional
    public PayRun create(LocalDate month) {
        LocalDate period = month.withDayOfMonth(1);
        LocalDate end = period.plusMonths(1).minusDays(1);
        if (period.isAfter(today().withDayOfMonth(1).plusMonths(1))) {
            throw BusinessException.onField("period", "payRun.period.future");
        }
        if (repo.existsByPeriodAndStatusNot(period, PayRunStatus.CANCELLED)) {
            throw BusinessException.onField("period", "payRun.period.taken", period.format(MONTH));
        }
        periods.requireOpen(end, "period");
        PayrollRateSet rateSet = rates.inForce(end)
                .orElseThrow(() -> BusinessException.onField("period", "payRun.noRates", end.format(DAY)));
        List<Employee> employees = employeeRepo.findWorkingIn(period, end);
        if (employees.isEmpty()) {
            throw BusinessException.onField("period", "payRun.noEmployees", period.format(MONTH));
        }
        Optional<AppUserPrincipal> user = AppUserPrincipal.current();
        PayRun run = new PayRun();
        run.setNumber(numbers.next(DocumentType.PAY_RUN));
        run.setPeriod(period);
        run.setStatus(PayRunStatus.DRAFT);
        run.setRateSet(rateSet);
        run.setPreparedById(user.map(AppUserPrincipal::getId).orElse(null));
        run.setPreparedBy(user.map(AppUserPrincipal::getUsername).orElse("system"));
        run.setPreparedAt(LocalDateTime.now(clock));
        repo.save(run);
        List<PayRunLine> lines = new ArrayList<>();
        for (Employee e : employees) {
            lines.add(newLine(run, e));
        }
        totals(run, lines);
        return run;
    }

    /** Adds the employees working in the month who are not on the draft yet (hired since). Returns how many. */
    @Transactional
    public int addMissing(UUID id) {
        PayRun run = lock(id);
        requireDraft(run);
        int added = 0;
        for (Employee e : employeeRepo.findWorkingIn(run.getPeriod(), run.getPeriodEnd())) {
            if (!lineRepo.existsByPayRunIdAndEmployee_Id(id, e.getId())) {
                newLine(run, e);
                added++;
            }
        }
        totals(run, lines(id));
        return added;
    }

    /** Takes an employee off the draft (not paid this month). Returns their name. */
    @Transactional
    public String removeLine(UUID id, UUID lineId) {
        PayRun run = lock(id);
        requireDraft(run);
        PayRunLine line = line(run, lineId);
        lineRepo.delete(line);
        lineRepo.flush();
        totals(run, lines(id));
        return line.getEmployeeName();
    }

    /** The month's earnings and deductions of a draft: every line is worked out again. Errors name the row's field. */
    @Transactional
    public PayRun saveLines(UUID id, PayRunLinesDto dto) {
        PayRun run = lock(id);
        requireDraft(run);
        List<PayRunLine> lines = lines(id);
        Map<UUID, PayRunLine> byId = lines.stream().collect(Collectors.toMap(PayRunLine::getId, Function.identity()));
        Map<UUID, BigDecimal> owed = shortagesOwed(lines);
        Payroll.Rates r = run.getRateSet().rates();
        for (int i = 0; i < dto.getLines().size(); i++) {
            PayRunLinesDto.Line row = dto.getLines().get(i);
            PayRunLine line = byId.get(row.getId());
            if (line == null) {
                throw BusinessException.of("payRun.line.gone");
            }
            String field = "lines[" + i + "].";
            BigDecimal shortage = amount(row.getShortageDeduction());
            if (shortage.signum() > 0) {
                if (line.getDriverId() == null) {
                    throw BusinessException.onField(field + "shortageDeduction", "payRun.shortage.notDriver", line.getEmployeeName());
                }
                BigDecimal max = owed.getOrDefault(line.getId(), BigDecimal.ZERO);
                if (shortage.compareTo(max) > 0) {
                    throw BusinessException.onField(field + "shortageDeduction", "payRun.shortage.tooMuch", num.money(max));
                }
            }
            line.setBasePay(amount(row.getBasePay()));
            line.setTransportAllowance(amount(row.getTransportAllowance()));
            line.setHousingAllowance(amount(row.getHousingAllowance()));
            line.setOtherAllowance(amount(row.getOtherAllowance()));
            line.setOvertime(amount(row.getOvertime()));
            line.setBonus(amount(row.getBonus()));
            line.setAdvanceDeduction(amount(row.getAdvanceDeduction()));
            line.setShortageDeduction(shortage);
            line.setOtherDeduction(amount(row.getOtherDeduction()));
            line.setDeductionNote(StringUtils.hasText(row.getDeductionNote()) ? row.getDeductionNote().trim() : null);
            Payroll.Result result = Payroll.calculate(line.earnings(), line.deductions(), line.getEmployment(), r);
            if (result.isNegative()) {
                throw BusinessException.onField(field + "otherDeduction", "payRun.line.negative", line.getEmployeeName(),
                        num.money(result.net().negate()));
            }
            line.apply(result);
        }
        totals(run, lines);
        return run;
    }

    /** Sent for approval: worked out once more at the run's rates (they may have changed while it was a draft). */
    @Transactional
    public PayRun submit(UUID id) {
        PayRun run = lock(id);
        requireDraft(run);
        List<PayRunLine> lines = lines(id);
        if (lines.isEmpty()) {
            throw BusinessException.of("payRun.empty", run.getNumber());
        }
        Payroll.Rates r = run.getRateSet().rates();
        for (PayRunLine line : lines) {
            Payroll.Result result = Payroll.calculate(line.earnings(), line.deductions(), line.getEmployment(), r);
            if (result.isNegative()) {
                throw BusinessException.of("payRun.line.negative", line.getEmployeeName(), num.money(result.net().negate()));
            }
            line.apply(result);
        }
        totals(run, lines);
        Optional<AppUserPrincipal> user = AppUserPrincipal.current();
        run.setSubmittedById(user.map(AppUserPrincipal::getId).orElse(null));
        run.setSubmittedBy(user.map(AppUserPrincipal::getUsername).orElse("system"));
        run.setSubmittedAt(LocalDateTime.now(clock));
        run.setStatus(PayRunStatus.SUBMITTED);
        notifier.holders("APPROVE_PAY_RUN", run.getSubmittedById(), NotificationKind.APPROVAL, "notify.payRun.waiting", "notify.payRun.waitingText",
                "/payroll/runs/" + run.getId(), run.getNumber(), run.getPeriod().format(MONTH), run.getSubmittedBy(), num.money(run.getTotalNet()));
        return run;
    }

    // ---------------------------------------------------------------- deciding

    /** Another person approves it: its journal posts on the month's last day. */
    @Transactional
    public PayRun approve(UUID id) {
        PayRun run = lock(id);
        requireSubmitted(run);
        if (isOwn(run)) {
            throw BusinessException.of("payRun.ownDecision", run.getNumber());
        }
        periods.requireOpen(run.getPeriodEnd());
        List<PayRunLine> lines = lines(id);
        run.setPostingDate(run.getPeriodEnd());
        JournalEntry entry = postings.payroll(run, lines);
        advances.recoverFromPayroll(lines);                       // CMP 08: advances not retired in time, taken from salary
        run.setApprovedBy(AppUserPrincipal.currentUsername());
        run.setApprovedAt(LocalDateTime.now(clock));
        run.setJournalId(entry.getId());
        run.setStatus(PayRunStatus.POSTED);
        notifier.user(run.getSubmittedById(), NotificationKind.DECISION, "notify.payRun.approved", "notify.decidedBy",
                "/payroll/runs/" + run.getId(), run.getNumber(), run.getApprovedBy());
        return run;
    }

    /** Another person sends it back to its preparer, with a reason: a draft again, nothing posted. */
    @Transactional
    public PayRun sendBack(UUID id, String reason) {
        PayRun run = lock(id);
        requireSubmitted(run);
        if (isOwn(run)) {
            throw BusinessException.of("payRun.ownDecision", run.getNumber());
        }
        run.setReturnedBy(AppUserPrincipal.currentUsername());
        run.setReturnedAt(LocalDateTime.now(clock));
        run.setReturnedNote(reason.trim());
        run.setStatus(PayRunStatus.DRAFT);
        notifier.user(run.getSubmittedById(), NotificationKind.DECISION, "notify.payRun.returned", "notify.rejectedBy",
                "/payroll/runs/" + run.getId(), run.getNumber(), run.getReturnedBy(), run.getReturnedNote());
        return run;
    }

    /** Given up before it was posted, with a reason: the month can have a new run. */
    @Transactional
    public PayRun cancel(UUID id, String reason) {
        PayRun run = lock(id);
        if (run.getStatus() != PayRunStatus.DRAFT && run.getStatus() != PayRunStatus.SUBMITTED) {
            throw BusinessException.of("payRun.notOpen", run.getNumber());
        }
        run.setCancelReason(reason.trim());
        run.setCancelledBy(AppUserPrincipal.currentUsername());
        run.setCancelledAt(LocalDateTime.now(clock));
        run.setStatus(PayRunStatus.CANCELLED);
        return run;
    }

    // ---------------------------------------------------------------- paying

    /** The salaries of a posted run paid on a day (from the month's first day to today): its payment journal. */
    @Transactional
    public PayRun pay(UUID id, LocalDate paidOn, String reference) {
        PayRun run = lock(id);
        if (run.getStatus() != PayRunStatus.POSTED) {
            throw BusinessException.of("payRun.notPosted", run.getNumber());
        }
        LocalDate day = paidOn == null ? today() : paidOn;
        if (day.isAfter(today())) {
            throw BusinessException.of("payRun.paid.future");
        }
        if (day.isBefore(run.getPeriod())) {
            throw BusinessException.of("payRun.paid.early", run.getPeriod().format(DAY));
        }
        periods.requireOpen(day);
        run.setPaidOn(day);
        run.setPaidBy(AppUserPrincipal.currentUsername());
        run.setPaymentReference(StringUtils.hasText(reference) ? reference.trim() : null);
        JournalEntry entry = postings.payrollPayment(run, lines(id));
        run.setPaymentJournalId(entry.getId());
        run.setStatus(PayRunStatus.PAID);
        return run;
    }

    // ---------------------------------------------------------------- helpers

    private PayRunLine newLine(PayRun run, Employee e) {
        PayRunLine line = new PayRunLine();
        line.setPayRunId(run.getId());
        line.setEmployee(e);
        line.setEmployeeCode(e.getCode());
        line.setEmployeeName(e.getFullName());
        line.setNationalId(e.getNationalId());
        line.setRssbNumber(e.getRssbNumber());
        line.setJobTitle(e.getJobTitle());
        line.setEmployment(e.getEmployment());
        line.setPayMethod(e.getPayMethod());
        line.setAccountNumber(e.getAccountNumber());
        line.setBasePay(e.getBaseSalary());
        line.setTransportAllowance(e.getTransportAllowance());
        line.setHousingAllowance(e.getHousingAllowance());
        line.setOtherAllowance(e.getOtherAllowance());
        line.setCostCentreId(e.getCostCentreId());
        if (e.getUser() != null) {
            driverRepo.findByUser_Id(e.getUser().getId()).ifPresent(d -> line.setDriverId(d.getId()));
        }
        line.apply(Payroll.calculate(line.earnings(), line.deductions(), line.getEmployment(), run.getRateSet().rates()));
        // An advance not retired by its day is taken from this salary, at most the net pay (CMP 08)
        LocalDate day = today().isBefore(run.getPeriodEnd()) ? today() : run.getPeriodEnd();
        advances.recoverable(e.getId(), day).ifPresent(a -> {
            line.setAdvanceId(a.getId());
            line.setAdvanceDeduction(Advances.deduction(a.getOutstanding(), line.getNetPay()));
            line.setDeductionNote(a.getNumber());
            line.apply(Payroll.calculate(line.earnings(), line.deductions(), line.getEmployment(), run.getRateSet().rates()));
        });
        return lineRepo.save(line);
    }

    private static void totals(PayRun run, List<PayRunLine> lines) {
        BigDecimal gross = BigDecimal.ZERO, paye = BigDecimal.ZERO, rssb = BigDecimal.ZERO, cbhi = BigDecimal.ZERO;
        BigDecimal other = BigDecimal.ZERO, net = BigDecimal.ZERO, employer = BigDecimal.ZERO;
        for (PayRunLine l : lines) {
            gross = gross.add(l.getGross());
            paye = paye.add(l.getPaye());
            rssb = rssb.add(l.getEmployeeContributions());
            cbhi = cbhi.add(l.getCbhi());
            other = other.add(l.getOtherDeductions());
            net = net.add(l.getNetPay());
            employer = employer.add(l.getEmployerContributions());
        }
        run.setEmployees(lines.size());
        run.setTotalGross(gross);
        run.setTotalPaye(paye);
        run.setTotalRssbEmployee(rssb);
        run.setTotalCbhi(cbhi);
        run.setTotalOther(other);
        run.setTotalNet(net);
        run.setTotalEmployer(employer);
    }

    private PayRun lock(UUID id) {
        return repo.lockById(id).orElseThrow(() -> new NotFoundException("PayRun", id));
    }

    private static void requireDraft(PayRun run) {
        if (!run.isDraft()) {
            throw BusinessException.of("payRun.notDraft", run.getNumber());
        }
    }

    private static void requireSubmitted(PayRun run) {
        if (run.getStatus() != PayRunStatus.SUBMITTED) {
            throw BusinessException.of("payRun.notSubmitted", run.getNumber());
        }
    }

    private static BigDecimal amount(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2) : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static PayRunStatus status(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return PayRunStatus.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null; // unknown status: no filter
        }
    }
}
