package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.config.NumberFormats;
import com.ntaganira.heritier.iWarehouse.dto.AdvanceRetireDto;
import com.ntaganira.heritier.iWarehouse.dto.StaffAdvanceDto;
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
import org.springframework.transaction.annotation.Propagation;
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
 * - File      : StaffAdvanceService.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : Staff advances (CMP 08; ADV-WH-2026-000001): asked for an employee (PERM_REQUEST_ADVANCE), a journey worked
 *               out from its per diem (PerDiemService; a foreign rate converted at ExchangeRateService.rateFor, kept on it)
 *               plus any fares, or another amount; no new advance while the employee has one open (the Settings). Approved
 *               within the Delegation of Authority (decision STAFF_ADVANCE: the Managing Director), never by who asked or
 *               the employee; paid (PERM_PAY_ADVANCE) from the vault, the bank, mobile money or a petty cash fund (at most
 *               what it holds and the Settings' payment), which sets the day it is retired by (Advances.dueOn). Retired
 *               (PERM_RETIRE_ADVANCE) with what it paid for by category and cost centre, the receipts attached, the rest
 *               returned or paid back (PostingService.advanceRetirement). Not retired in time: ALERT_EXPENSE holders are
 *               told once, and the next pay run recovers it from salary (recoverable, recoverFromPayroll at its approval).
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class StaffAdvanceService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    static final String OVERDUE_PREFIX = "ADVANCE_OVERDUE:";
    private static final List<StaffAdvanceStatus> OPEN = List.of(StaffAdvanceStatus.SUBMITTED, StaffAdvanceStatus.APPROVED, StaffAdvanceStatus.PAID);

    /** A journey's per diem: its days and nights, the amount in the rate's currency, the rate applied and the RWF. */
    public record Estimate(int days, int nights, String currency, BigDecimal perDiem, ExchangeRateService.AppliedRate rate, BigDecimal rwf) {
    }

    private final StaffAdvanceRepository repo;
    private final EmployeeRepository employeeRepo;
    private final CostCentreRepository costCentreRepo;
    private final CurrencyRepository currencyRepo;
    private final AlertStateRepository alertRepo;
    private final PerDiemService perDiem;
    private final PettyCashService pettyCash;
    private final ExchangeRateService rates;
    private final AttachmentService attachments;
    private final PostingService postingService;
    private final JournalService journalService;
    private final DocumentNumberService numbers;
    private final DelegationOfAuthority authority;
    private final PeriodLock periods;
    private final SettingService settings;
    private final Notifier notifier;
    private final NumberFormats num;
    private final Clock clock;

    public StaffAdvanceService(StaffAdvanceRepository repo, EmployeeRepository employeeRepo, CostCentreRepository costCentreRepo,
                               CurrencyRepository currencyRepo, AlertStateRepository alertRepo, PerDiemService perDiem, PettyCashService pettyCash,
                               ExchangeRateService rates, AttachmentService attachments, PostingService postingService, JournalService journalService,
                               DocumentNumberService numbers, DelegationOfAuthority authority, PeriodLock periods, SettingService settings,
                               Notifier notifier, NumberFormats num, Clock clock) {
        this.repo = repo;
        this.employeeRepo = employeeRepo;
        this.costCentreRepo = costCentreRepo;
        this.currencyRepo = currencyRepo;
        this.alertRepo = alertRepo;
        this.perDiem = perDiem;
        this.pettyCash = pettyCash;
        this.rates = rates;
        this.attachments = attachments;
        this.postingService = postingService;
        this.journalService = journalService;
        this.numbers = numbers;
        this.authority = authority;
        this.periods = periods;
        this.settings = settings;
        this.notifier = notifier;
        this.num = num;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- reading

    public Page<StaffAdvance> findPage(String search, String status, int page, int size) {
        LocalDate today = today();
        Specification<StaffAdvance> spec = (root, query, cb) -> {
            Predicate p = cb.conjunction();
            if (StringUtils.hasText(search)) {
                String term = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                var employee = root.join("employee", JoinType.LEFT);
                p = cb.and(p, cb.or(cb.like(cb.lower(root.get("number")), term), cb.like(cb.lower(root.get("purpose")), term),
                        cb.like(cb.lower(employee.get("fullName")), term), cb.like(cb.lower(employee.get("code")), term)));
            }
            if ("waiting".equals(status)) {
                p = cb.and(p, cb.equal(root.get("status"), StaffAdvanceStatus.SUBMITTED));
            } else if ("overdue".equals(status)) {
                p = cb.and(p, cb.equal(root.get("status"), StaffAdvanceStatus.PAID), cb.lessThan(root.get("dueOn"), today));
            } else if (StringUtils.hasText(status)) {
                try {
                    p = cb.and(p, cb.equal(root.get("status"), StaffAdvanceStatus.valueOf(status)));
                } catch (IllegalArgumentException e) {
                    // unknown: no filter
                }
            }
            return p;
        };
        return repo.findAll(spec, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "requestedAt")));
    }

    public StaffAdvance findDetailed(UUID id) {
        return repo.findDetailedById(id).orElseThrow(() -> new NotFoundException("StaffAdvance", id));
    }

    public long waitingCount() {
        return repo.countByStatus(StaffAdvanceStatus.SUBMITTED);
    }

    public long overdueCount() {
        return repo.findByStatusAndDueOnBeforeOrderByDueOnAsc(StaffAdvanceStatus.PAID, today()).size();
    }

    public List<JournalEntry> journals(StaffAdvance a) {
        List<JournalEntry> journals = new ArrayList<>();
        if (a.getPayJournalId() != null) {
            journals.add(journalService.findById(a.getPayJournalId()));
        }
        if (a.getRetireJournalId() != null) {
            journals.add(journalService.findById(a.getRetireJournalId()));
        }
        return journals;
    }

    public List<Employee> employees() {
        return employeeRepo.findByLeftOnIsNullOrderByFullNameAsc();
    }

    public List<CostCentre> costCentres() {
        return costCentreRepo.findByEnabledTrueOrderByCodeAsc();
    }

    public List<ExpenseCategory> categories() {
        return Arrays.stream(ExpenseCategory.values()).filter(c -> !c.isCapital()).toList();
    }

    public List<PettyCashService.FundRow> funds() {
        return pettyCash.activeFunds();
    }

    /** The employee's advances still open (CMP 08: one at a time), but this one. */
    public List<StaffAdvance> openOf(UUID employeeId, UUID except) {
        return repo.findByEmployee_IdAndStatusInOrderByRequestedAtAsc(employeeId, OPEN).stream().filter(a -> !a.getId().equals(except)).toList();
    }

    public boolean isRequester(StaffAdvance a) {
        return AppUserPrincipal.current().map(u -> u.getId().equals(a.getRequestedById())).orElse(false);
    }

    /** The employee themselves, signed in. */
    public boolean isEmployee(StaffAdvance a) {
        return a.getEmployee().getUser() != null && AppUserPrincipal.current().map(u -> u.getId().equals(a.getEmployee().getUser().getId())).orElse(false);
    }

    public DelegationOfAuthority.Check authorityOf(StaffAdvance a) {
        return authority.check(AuthorityDecision.STAFF_ADVANCE, a.getAmount());
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    public StaffAdvanceDto newForm(UUID employeeId) {
        StaffAdvanceDto dto = new StaffAdvanceDto();
        dto.setKind(StaffAdvanceKind.TRAVEL);
        dto.setEmployeeId(employeeId);
        if (employeeId != null) {
            employeeRepo.findById(employeeId).ifPresent(e -> dto.setCostCentreId(e.getCostCentreId()));
        }
        return dto;
    }

    public StaffAdvanceDto formOf(StaffAdvance a) {
        StaffAdvanceDto dto = new StaffAdvanceDto();
        dto.setEmployeeId(a.getEmployee().getId());
        dto.setKind(a.getKind());
        dto.setPurpose(a.getPurpose());
        dto.setCostCentreId(a.getCostCentre().getId());
        dto.setPerDiemCategory(a.getPerDiemCategory());
        dto.setDestination(a.getDestination());
        dto.setDepartOn(a.getDepartOn());
        dto.setReturnOn(a.getReturnOn());
        if (a.getKind() == StaffAdvanceKind.TRAVEL) {
            BigDecimal perDiemRwf = a.getRate() == null ? a.getPerDiemAmount().setScale(0, RoundingMode.HALF_UP).setScale(2)
                    : Advances.inRwf(a.getPerDiemAmount(), a.getRate());
            dto.setExtraAmount(a.getAmount().subtract(perDiemRwf));
        } else {
            dto.setAmount(a.getAmount());
        }
        return dto;
    }

    /** The per diem of a journey at today's rates (CMP 08). */
    public Estimate estimate(PerDiemCategory category, LocalDate departOn, LocalDate returnOn) {
        PerDiemRate rate = perDiem.of(category);
        int days = Advances.days(departOn, returnOn);
        int nights = Advances.nights(departOn, returnOn);
        BigDecimal amount = Advances.perDiem(rate.getDaily(), rate.getAccommodation(), days, nights);
        if (baseCurrency().equals(rate.getCurrencyCode())) {
            return new Estimate(days, nights, rate.getCurrencyCode(), amount, null, amount.setScale(0, RoundingMode.HALF_UP).setScale(2));
        }
        ExchangeRateService.AppliedRate applied = rates.rateFor(rate.getCurrencyCode(), today());
        return new Estimate(days, nights, rate.getCurrencyCode(), amount, applied, Advances.inRwf(amount, applied.rate()));
    }

    /** Payroll: the employee's oldest advance not retired by its day, still owed (CMP 08), when the Settings recover them. */
    public Optional<StaffAdvance> recoverable(UUID employeeId, LocalDate day) {
        if (!settings.getBoolean(SettingKey.ADVANCE_PAYROLL_RECOVERY)) {
            return Optional.empty();
        }
        return repo.findByEmployee_IdAndStatusInOrderByRequestedAtAsc(employeeId, List.of(StaffAdvanceStatus.PAID)).stream()
                .filter(a -> a.isOverdue(day) && a.getOutstanding().signum() > 0).findFirst();
    }

    /** The advances due in a period, retired, and retired on time (CMP 10). */
    public Advances.Rates retirementRates(LocalDate from, LocalDate to) {
        List<Advances.Due> due = repo.findByDueOnBetweenAndStatusIn(from, to, List.of(StaffAdvanceStatus.PAID, StaffAdvanceStatus.RETIRED,
                StaffAdvanceStatus.RECOVERED)).stream().map(a -> new Advances.Due(a.getDueOn(),
                a.getStatus() == StaffAdvanceStatus.RETIRED ? a.getRetiredOn() : null)).toList();
        return Advances.rates(due);
    }

    // ---------------------------------------------------------------- asking

    @Transactional
    public StaffAdvance save(UUID id, StaffAdvanceDto dto) {
        StaffAdvance a;
        if (id == null) {
            Optional<AppUserPrincipal> me = AppUserPrincipal.current();
            a = new StaffAdvance();
            a.setNumber(numbers.next(DocumentType.STAFF_ADVANCE));
            a.setRequestedBy(me.map(AppUserPrincipal::getUsername).orElse("system"));
            a.setRequestedById(me.map(AppUserPrincipal::getId).orElse(null));
            a.setRequestedAt(LocalDateTime.now(clock));
        } else {
            a = lock(id);
            if (!a.isDraft()) {
                throw BusinessException.of("advance.notDraft", a.getNumber());
            }
            if (!isRequester(a)) {
                throw BusinessException.of("advance.notYours", a.getNumber(), a.getRequestedBy());
            }
        }
        Employee employee = employeeRepo.findById(dto.getEmployeeId())
                .orElseThrow(() -> BusinessException.onField("employeeId", "advance.employee.required"));
        if (!employee.isActive()) {
            throw BusinessException.onField("employeeId", "advance.employee.left", employee.getFullName());
        }
        requireNoneOpen(employee, a.getId());
        CostCentre centre = costCentreRepo.findById(dto.getCostCentreId())
                .orElseThrow(() -> BusinessException.onField("costCentreId", "expense.line.costCentre.required"));
        if (!centre.isEnabled()) {
            throw BusinessException.onField("costCentreId", "expense.line.costCentre.disabled", centre.getCode());
        }
        a.setEmployee(employee);
        a.setKind(dto.getKind());
        a.setPurpose(dto.getPurpose().trim());
        a.setCostCentre(centre);
        if (dto.getKind() == StaffAdvanceKind.TRAVEL) {
            if (dto.getPerDiemCategory() == null) {
                throw BusinessException.onField("perDiemCategory", "advance.travel.category");
            }
            if (dto.getDepartOn() == null) {
                throw BusinessException.onField("departOn", "advance.travel.departOn");
            }
            if (dto.getReturnOn() == null || dto.getReturnOn().isBefore(dto.getDepartOn())) {
                throw BusinessException.onField("returnOn", "advance.travel.returnOn");
            }
            if (!StringUtils.hasText(dto.getDestination())) {
                throw BusinessException.onField("destination", "advance.travel.destination");
            }
            BigDecimal extra = dto.getExtraAmount() == null ? BigDecimal.ZERO : dto.getExtraAmount().setScale(2, RoundingMode.HALF_UP);
            if (extra.signum() < 0) {
                throw BusinessException.onField("extraAmount", "advance.extra.negative");
            }
            Estimate e = estimate(dto.getPerDiemCategory(), dto.getDepartOn(), dto.getReturnOn());
            a.setPerDiemCategory(dto.getPerDiemCategory());
            a.setDestination(dto.getDestination().trim());
            a.setDepartOn(dto.getDepartOn());
            a.setReturnOn(dto.getReturnOn());
            a.setDays(e.days());
            a.setNights(e.nights());
            a.setPerDiemCurrency(e.currency());
            a.setPerDiemAmount(e.perDiem());
            a.setRate(e.rate() == null ? null : e.rate().rate());
            a.setRateDate(e.rate() == null ? null : e.rate().rateDate());
            a.setRateSource(e.rate() == null ? null : e.rate().source());
            a.setAmount(e.rwf().add(extra));
        } else {
            if (dto.getAmount() == null || dto.getAmount().signum() <= 0) {
                throw BusinessException.onField("amount", "advance.amount.required");
            }
            a.setPerDiemCategory(null);
            a.setDestination(null);
            a.setDepartOn(null);
            a.setReturnOn(null);
            a.setDays(null);
            a.setNights(null);
            a.setPerDiemCurrency(null);
            a.setPerDiemAmount(null);
            a.setRate(null);
            a.setRateDate(null);
            a.setRateSource(null);
            a.setAmount(dto.getAmount().setScale(2, RoundingMode.HALF_UP));
        }
        if (a.getAmount().signum() <= 0) {
            throw BusinessException.onField("extraAmount", "advance.amount.required");
        }
        return repo.save(a);
    }

    /** Who asked submits it; the approvers are told. */
    @Transactional
    public StaffAdvance submit(UUID id) {
        StaffAdvance a = lock(id);
        if (!a.isDraft()) {
            throw BusinessException.of("advance.notDraft", a.getNumber());
        }
        if (!isRequester(a)) {
            throw BusinessException.of("advance.notYours", a.getNumber(), a.getRequestedBy());
        }
        requireNoneOpen(a.getEmployee(), a.getId());
        a.setSubmittedAt(LocalDateTime.now(clock));
        a.setStatus(StaffAdvanceStatus.SUBMITTED);
        notifier.holders("APPROVE_ADVANCE", a.getRequestedById(), NotificationKind.APPROVAL, "notify.advance.waiting", "notify.advance.waitingText",
                link(a), a.getNumber(), a.getEmployee().getFullName(), num.money(a.getAmount()), a.getPurpose());
        return a;
    }

    /** Approved within the Delegation of Authority (the Managing Director), never by who asked or the employee. */
    @Transactional
    public StaffAdvance approve(UUID id, String note) {
        StaffAdvance a = lock(id);
        if (a.getStatus() != StaffAdvanceStatus.SUBMITTED) {
            throw BusinessException.of("advance.notWaiting", a.getNumber());
        }
        if (isRequester(a) || isEmployee(a)) {
            throw BusinessException.of("advance.ownStep", a.getNumber());
        }
        String clean = note(note);
        AuthorityLevel level = authority.require(AuthorityDecision.STAFF_ADVANCE, a.getAmount(), clean);
        decide(a, StaffAdvanceStatus.APPROVED, clean);
        a.setAuthorityLevel(level);
        notifier.user(a.getRequestedById(), NotificationKind.DECISION, "notify.advance.approved", "notify.decidedBy", link(a), a.getNumber(),
                a.getDecidedBy());
        notifier.holders("PAY_ADVANCE", a.getDecidedById(), NotificationKind.APPROVAL, "notify.advance.toPay", "notify.advance.toPayText", link(a),
                a.getNumber(), a.getEmployee().getFullName(), num.money(a.getAmount()));
        return a;
    }

    @Transactional
    public StaffAdvance reject(UUID id, String reason) {
        StaffAdvance a = lock(id);
        if (a.getStatus() != StaffAdvanceStatus.SUBMITTED) {
            throw BusinessException.of("advance.notWaiting", a.getNumber());
        }
        if (isRequester(a) || isEmployee(a)) {
            throw BusinessException.of("advance.ownStep", a.getNumber());
        }
        decide(a, StaffAdvanceStatus.REJECTED, reason(reason));
        notifier.user(a.getRequestedById(), NotificationKind.DECISION, "notify.advance.rejected", "notify.rejectedBy", link(a), a.getNumber(),
                a.getDecidedBy(), a.getDecisionNote());
        return a;
    }

    /** Who asked cancels it, with a reason, before it is paid. */
    @Transactional
    public StaffAdvance cancel(UUID id, String reason) {
        StaffAdvance a = lock(id);
        if (a.getStatus() != StaffAdvanceStatus.DRAFT && a.getStatus() != StaffAdvanceStatus.SUBMITTED && a.getStatus() != StaffAdvanceStatus.APPROVED) {
            throw BusinessException.of("advance.notCancellable", a.getNumber());
        }
        if (!isRequester(a)) {
            throw BusinessException.of("advance.notYours", a.getNumber(), a.getRequestedBy());
        }
        decide(a, StaffAdvanceStatus.CANCELLED, reason(reason));       // an approval given is kept in the change log
        return a;
    }

    // ---------------------------------------------------------------- paying and retiring

    /** Pays an approved advance (never to oneself): it posts, and the day it is retired by is set (CMP 08). */
    @Transactional
    public StaffAdvance pay(UUID id, AdvancePayMethod method, UUID fundId, String reference, LocalDate paidOn) {
        StaffAdvance a = lock(id);
        if (a.getStatus() != StaffAdvanceStatus.APPROVED) {
            throw BusinessException.of("advance.notApproved", a.getNumber());
        }
        if (isEmployee(a)) {
            throw BusinessException.of("advance.ownStep", a.getNumber());
        }
        LocalDate day = paidOn == null ? today() : paidOn;
        if (day.isAfter(today())) {
            throw BusinessException.onField("paidOn", "pettyCash.date.future");
        }
        periods.requireOpen(day, "paidOn");
        PettyCashFund fund = money(method, fundId, reference, a.getAmount(), "pay");
        a.setPayMethod(method);
        a.setPayFund(fund);
        a.setPayReference(StringUtils.hasText(reference) ? reference.trim() : null);
        a.setPaidOn(day);
        a.setPaidBy(AppUserPrincipal.currentUsername());
        a.setPaidById(AppUserPrincipal.current().map(AppUserPrincipal::getId).orElse(null));
        JournalEntry entry = postingService.staffAdvance(a);
        a.setPayJournalId(entry.getId());
        a.setDueOn(Advances.dueOn(day, a.getReturnOn(), settings.getInt(SettingKey.ADVANCE_RETIRE_DAYS)));
        a.setStatus(StaffAdvanceStatus.PAID);
        if (fund != null) {
            pettyCash.refreshLow(fund, pettyCash.balance(fund));
        }
        notifier.user(a.getRequestedById(), NotificationKind.DECISION, "notify.advance.paid", "notify.advance.paidText", link(a), a.getNumber(),
                a.getEmployee().getFullName(), a.getDueOn().format(DAY));
        return a;
    }

    /**
     * Retires a paid advance with what it paid for (CMP 08): the receipts attached (when the Settings ask), the lines by category
     * and cost centre, and the rest returned (or paid back when the receipts come to more) by the method given. Posts it.
     */
    @Transactional
    public StaffAdvance retire(UUID id, AdvanceRetireDto dto) {
        StaffAdvance a = lock(id);
        if (a.getStatus() != StaffAdvanceStatus.PAID) {
            throw BusinessException.of("advance.notPaid", a.getNumber());
        }
        if (isEmployee(a)) {
            throw BusinessException.of("advance.ownStep", a.getNumber());
        }
        if (settings.getBoolean(SettingKey.ADVANCE_RECEIPTS_REQUIRED) && attachments.count(AttachmentOwner.STAFF_ADVANCE, a.getId()) == 0) {
            throw BusinessException.of("advance.receiptsRequired", a.getNumber());
        }
        LocalDate day = dto.getRetiredOn() == null ? today() : dto.getRetiredOn();
        if (day.isAfter(today())) {
            throw BusinessException.onField("retiredOn", "pettyCash.date.future");
        }
        if (day.isBefore(a.getPaidOn())) {
            throw BusinessException.onField("retiredOn", "advance.retire.early", a.getPaidOn().format(DAY));
        }
        periods.requireOpen(day, "retiredOn");
        BigDecimal spent = BigDecimal.ZERO;
        int no = 0;
        List<StaffAdvanceLine> lines = new ArrayList<>();
        for (AdvanceRetireDto.Line l : dto.getLines()) {
            String field = "lines[" + no + "]";
            if (l.getCategory() == null || l.getCategory().isCapital()) {
                throw BusinessException.onField(field + ".category", "pettyCash.voucher.category");
            }
            CostCentre centre = costCentreRepo.findById(l.getCostCentreId())
                    .orElseThrow(() -> BusinessException.onField(field + ".costCentreId", "expense.line.costCentre.required"));
            StaffAdvanceLine line = new StaffAdvanceLine();
            line.setAdvance(a);
            line.setLineNo(++no);
            line.setCategory(l.getCategory());
            line.setDescription(l.getDescription().trim());
            line.setCostCentre(centre);
            line.setAmount(l.getAmount().setScale(2, RoundingMode.HALF_UP));
            line.setReceiptRef(StringUtils.hasText(l.getReceiptRef()) ? l.getReceiptRef().trim() : null);
            lines.add(line);
            spent = spent.add(line.getAmount());
        }
        BigDecimal outstanding = a.getOutstanding();
        BigDecimal settled = Advances.settlement(outstanding, spent);
        PettyCashFund fund = null;
        if (settled.signum() != 0) {
            if (dto.getSettleMethod() == null) {
                throw BusinessException.onField("settleMethod", settled.signum() > 0 ? "advance.settle.returned" : "advance.settle.paidBack",
                        num.money(settled.abs()));
            }
            fund = money(dto.getSettleMethod(), dto.getSettleFundId(), dto.getSettleReference(), settled, "settle");
        }
        a.getLines().addAll(lines);
        a.setSpent(spent);
        a.setSettled(settled);
        a.setSettleMethod(settled.signum() == 0 ? null : dto.getSettleMethod());
        a.setSettleFund(fund);
        a.setSettleReference(settled.signum() != 0 && StringUtils.hasText(dto.getSettleReference()) ? dto.getSettleReference().trim() : null);
        a.setRetiredOn(day);
        a.setRetiredAt(LocalDateTime.now(clock));
        a.setRetiredBy(AppUserPrincipal.currentUsername());
        a.setRetiredById(AppUserPrincipal.current().map(AppUserPrincipal::getId).orElse(null));
        JournalEntry entry = postingService.advanceRetirement(a);
        a.setRetireJournalId(entry.getId());
        a.setStatus(StaffAdvanceStatus.RETIRED);
        AlertOnce.clear(alertRepo, OVERDUE_PREFIX + a.getId(), LocalDateTime.now(clock));
        if (fund != null) {
            pettyCash.refreshLow(fund, pettyCash.balance(fund));
        }
        return a;
    }

    /**
     * A pay run posted (PayRunService.approve): each line that names an advance recovers its deduction from it, at most what is
     * still owed; an advance recovered in full is closed (RECOVERED). The run's journal credited Staff Advances already.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recoverFromPayroll(List<PayRunLine> lines) {
        for (PayRunLine l : lines) {
            if (l.getAdvanceId() == null || l.getAdvanceDeduction().signum() <= 0) {
                continue;
            }
            StaffAdvance a = lock(l.getAdvanceId());
            if (a.getStatus() != StaffAdvanceStatus.PAID) {
                continue;
            }
            BigDecimal take = l.getAdvanceDeduction().min(a.getOutstanding());
            a.setRecovered(a.getRecovered().add(take));
            if (a.getOutstanding().signum() == 0) {
                a.setStatus(StaffAdvanceStatus.RECOVERED);
                AlertOnce.clear(alertRepo, OVERDUE_PREFIX + a.getId(), LocalDateTime.now(clock));
            }
        }
    }

    /** Advances not retired by their day: ALERT_EXPENSE holders and who asked are told once each (CMP 08). */
    @Scheduled(fixedDelayString = "${app.alerts.advance-every:PT6H}", initialDelayString = "${app.alerts.first-check:PT1M}")
    @Transactional
    public int checkOverdue() {
        LocalDateTime now = LocalDateTime.now(clock);
        int told = 0;
        for (StaffAdvance a : repo.findByStatusAndDueOnBeforeOrderByDueOnAsc(StaffAdvanceStatus.PAID, today())) {
            if (AlertOnce.raise(alertRepo, OVERDUE_PREFIX + a.getId(), now)) {
                String[] args = {a.getNumber(), a.getEmployee().getFullName(), num.money(a.getOutstanding()), a.getDueOn().format(DAY)};
                notifier.holders("ALERT_EXPENSE", null, NotificationKind.EXPENSE, "notify.advance.overdue", "notify.advance.overdueText", link(a), args);
                notifier.user(a.getRequestedById(), NotificationKind.EXPENSE, "notify.advance.overdue", "notify.advance.overdueText", link(a), args);
                told++;
            }
        }
        return told;
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Checks how money moves for an advance (paid out: signed negative; returned: positive): a method, its reference, or the petty
     * cash fund (locked) holding enough and within the Settings' payment. Returns the fund, or null.
     */
    private PettyCashFund money(AdvancePayMethod method, UUID fundId, String reference, BigDecimal signed, String prefix) {
        String methodField = "pay".equals(prefix) ? "payMethod" : "settleMethod";
        if (method == null) {
            throw BusinessException.onField(methodField, "advance.method.required");
        }
        if (method.needsReference() && !StringUtils.hasText(reference)) {
            throw BusinessException.onField("pay".equals(prefix) ? "payReference" : "settleReference", "pettyCash.reference.required");
        }
        if (method != AdvancePayMethod.PETTY_CASH) {
            return null;
        }
        if (fundId == null) {
            throw BusinessException.onField("pay".equals(prefix) ? "payFundId" : "settleFundId", "advance.fund.required");
        }
        PettyCashFund fund = pettyCash.lockForPayment(fundId);
        if (signed.signum() < 0) {
            String refusal = PettyCash.paymentRefusal(signed.negate(), pettyCash.maxPayment(), pettyCash.balance(fund));
            if ("TOO_MUCH".equals(refusal)) {
                throw BusinessException.onField(methodField, "pettyCash.voucher.tooMuch", num.money(pettyCash.maxPayment()));
            }
            if ("NOT_ENOUGH".equals(refusal)) {
                throw BusinessException.onField(methodField, "advance.fund.notEnough", fund.getCode(), num.money(pettyCash.balance(fund)));
            }
        }
        return fund;
    }

    private void requireNoneOpen(Employee employee, UUID except) {
        if (!settings.getBoolean(SettingKey.ADVANCE_BLOCK_OPEN)) {
            return;
        }
        List<StaffAdvance> open = openOf(employee.getId(), except);
        if (!open.isEmpty()) {
            throw BusinessException.onField("employeeId", "advance.openExists", employee.getFullName(), open.get(0).getNumber());
        }
    }

    private void decide(StaffAdvance a, StaffAdvanceStatus status, String note) {
        Optional<AppUserPrincipal> me = AppUserPrincipal.current();
        a.setDecidedBy(me.map(AppUserPrincipal::getUsername).orElse("system"));
        a.setDecidedById(me.map(AppUserPrincipal::getId).orElse(null));
        a.setDecidedAt(LocalDateTime.now(clock));
        a.setDecisionNote(note);
        a.setStatus(status);
    }

    private static String link(StaffAdvance a) {
        return "/payroll/advances/" + a.getId();
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

    private StaffAdvance lock(UUID id) {
        repo.lockById(id).orElseThrow(() -> new NotFoundException("StaffAdvance", id));
        return findDetailed(id);
    }

    private String baseCurrency() {
        return currencyRepo.findByBaseCurrencyTrue().map(com.ntaganira.heritier.iWarehouse.entity.Currency::getCode).orElse("RWF");
    }
}
