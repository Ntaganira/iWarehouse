package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.Account;
import com.ntaganira.heritier.iWarehouse.entity.FxRevaluation;
import com.ntaganira.heritier.iWarehouse.entity.FxRevaluationLine;
import com.ntaganira.heritier.iWarehouse.entity.JournalEntry;
import com.ntaganira.heritier.iWarehouse.entity.Supplier;
import com.ntaganira.heritier.iWarehouse.enums.AccountKey;
import com.ntaganira.heritier.iWarehouse.enums.DocumentType;
import com.ntaganira.heritier.iWarehouse.enums.JournalSource;
import com.ntaganira.heritier.iWarehouse.enums.RateSource;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.*;
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
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : FxRevaluationService.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : Month-end revaluation of open foreign balances (ACC-08, unrealised FX). What is owed in a foreign
 *               currency on Accounts Payable (per supplier), Goods Received Not Invoiced (per supplier) and Accrued
 *               Import Charges at a month's last day, from the journal lines up to that day, is revalued at that
 *               day's rate (rateFor, kept on each line); PostingService.fxRevaluation posts the differences to
 *               Unrealised FX Gain/Loss on that day and reverses them the next. A month is revalued once, after
 *               it has ended; revaluations and their reversals add up to nothing on any later day, so earlier
 *               months never change what a later month finds open.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class FxRevaluationService {

    /** The accounts revalued: liabilities a foreign document can leave open (receipts, invoices, import bills). */
    static final List<AccountKey> REVALUED = List.of(AccountKey.PAYABLE, AccountKey.GRNI, AccountKey.IMPORT_ACCRUAL);

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final FxRevaluationRepository repo;
    private final FxRevaluationLineRepository lineRepo;
    private final JournalLineRepository journalLineRepo;
    private final JournalEntryRepository entryRepo;
    private final AccountRepository accountRepo;
    private final SupplierRepository supplierRepo;
    private final ExchangeRateService rates;
    private final PostingService postingService;
    private final DocumentNumberService numbers;
    private final PeriodLock periods;
    private final Clock clock;

    public FxRevaluationService(FxRevaluationRepository repo, FxRevaluationLineRepository lineRepo, JournalLineRepository journalLineRepo,
                                JournalEntryRepository entryRepo, AccountRepository accountRepo, SupplierRepository supplierRepo,
                                ExchangeRateService rates, PostingService postingService, DocumentNumberService numbers, PeriodLock periods,
                                Clock clock) {
        this.repo = repo;
        this.lineRepo = lineRepo;
        this.journalLineRepo = journalLineRepo;
        this.entryRepo = entryRepo;
        this.accountRepo = accountRepo;
        this.supplierRepo = supplierRepo;
        this.rates = rates;
        this.postingService = postingService;
        this.numbers = numbers;
        this.periods = periods;
        this.clock = clock;
    }

    /**
     * An open foreign balance at the month's end: its account, supplier (none on Accrued Import Charges), currency, what is
     * owed in it and in RWF as booked; the rate (date, source) and, at it, the RWF revalued and the gain or loss: all null
     * when the rate is missing. Named as FxRevaluationLine, so a page shows both alike.
     */
    public record Row(Account account, Supplier supplier, String currencyCode, BigDecimal fxOwed, BigDecimal baseOwed,
                      BigDecimal rate, LocalDate rateDate, RateSource rateSource, BigDecimal revaluedOwed, BigDecimal gainLoss) {
    }

    /** A month to revalue: its last day, the open balances, the rates refused (missing or stale) and the total gain or loss. */
    public record Preview(YearMonth month, LocalDate periodEnd, List<Row> rows, List<BusinessException> missingRates) {

        public BigDecimal getGainLoss() {
            return rows.stream().map(Row::gainLoss).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        /** Every rate is there and something moves. */
        public boolean isReady() {
            return missingRates.isEmpty() && rows.stream().anyMatch(r -> r.gainLoss() != null && r.gainLoss().signum() != 0);
        }
    }

    // ---------------------------------------------------------------- reading

    /** The months that can be revalued, newest first: ended, not revalued, not closed. */
    public List<YearMonth> months() {
        LocalDate closedThrough = periods.closedThrough();
        return FxRevaluations.months(entryRepo.firstEntryDate(), today(), repo.findPeriodEnds()).stream()
                .filter(m -> !Periods.isClosed(m.atEndOfMonth(), closedThrough)).toList();
    }

    /** Whether a month's foreign balances are revalued (ACC-10 asks before closing it). */
    public enum MonthState { DONE, NOT_NEEDED, DUE }

    /**
     * DONE once revalued; DUE while something would move at its last rate, or a rate is missing for what is open; NOT_NEEDED
     * when nothing foreign is open or nothing would move.
     */
    public MonthState state(YearMonth month) {
        if (repo.existsByPeriodEnd(month.atEndOfMonth())) {
            return MonthState.DONE;
        }
        Preview preview = compute(month);
        return !preview.missingRates().isEmpty() || preview.isReady() ? MonthState.DUE : MonthState.NOT_NEEDED;
    }

    /** The revaluation of a month, if it was revalued. */
    public Optional<FxRevaluation> ofMonth(YearMonth month) {
        return repo.findByPeriodEnd(month.atEndOfMonth());
    }

    /** What revaluing a month would post. Refused before the month has ended and once it is revalued. */
    public Preview preview(YearMonth month) {
        requireOpen(month);
        return compute(month);
    }

    public Page<FxRevaluation> findPage(int page, int size) {
        return repo.findAll(PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "periodEnd")));
    }

    public FxRevaluation findById(UUID id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException("FxRevaluation", id));
    }

    public List<FxRevaluationLine> lines(UUID id) {
        return lineRepo.findByRevaluationIdOrderByLineNo(id);
    }

    /** The revaluation's journal and its reversal. */
    public List<JournalEntry> journals(UUID id) {
        return entryRepo.findBySourceTypeInAndSourceIdOrderByPostedAtAscNumberAsc(List.of(JournalSource.FX_REVALUATION), id);
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    // ---------------------------------------------------------------- posting

    /**
     * Revalues a month: every open balance at its last day's rate, kept on the lines; posts the differences on that day and
     * their reversal the next. Taking the number locks the FXR sequence, so two people never revalue the same month.
     */
    @Transactional
    public FxRevaluation post(YearMonth month) {
        String number = numbers.next(DocumentType.FX_REVALUATION);
        requireOpen(month);
        Preview preview = compute(month);
        if (!preview.missingRates().isEmpty()) {
            throw preview.missingRates().get(0);
        }
        if (!preview.isReady()) {
            throw BusinessException.of("fxRevaluation.nothing", preview.periodEnd().format(DAY));
        }
        FxRevaluation revaluation = new FxRevaluation();
        revaluation.setNumber(number);
        revaluation.setPeriodEnd(preview.periodEnd());
        revaluation.setGainLoss(preview.getGainLoss());
        revaluation.setPostedAt(LocalDateTime.now(clock));
        revaluation.setPostedBy(AppUserPrincipal.currentUsername());
        repo.save(revaluation);
        List<FxRevaluationLine> lines = new ArrayList<>();
        int no = 1;
        for (Row row : preview.rows()) {
            FxRevaluationLine line = new FxRevaluationLine();
            line.setRevaluationId(revaluation.getId());
            line.setLineNo(no++);
            line.setAccount(row.account());
            line.setSupplier(row.supplier());
            line.setCurrencyCode(row.currencyCode());
            line.setFxOwed(row.fxOwed());
            line.setBaseOwed(row.baseOwed());
            line.setRate(row.rate());
            line.setRateDate(row.rateDate());
            line.setRateSource(row.rateSource());
            line.setRevaluedOwed(row.revaluedOwed());
            line.setGainLoss(row.gainLoss());
            lines.add(lineRepo.save(line));
        }
        postingService.fxRevaluation(revaluation, lines);
        return revaluation;
    }

    // ---------------------------------------------------------------- helpers

    private void requireOpen(YearMonth month) {
        if (month == null || !FxRevaluations.hasEnded(month, today())) {
            throw BusinessException.of("fxRevaluation.notEnded");
        }
        if (repo.existsByPeriodEnd(month.atEndOfMonth())) {
            throw BusinessException.of("fxRevaluation.done", month.atEndOfMonth().format(DAY));
        }
        periods.requireOpen(month.atEndOfMonth());
    }

    /** The open foreign balances at the month's last day, each at that day's rate, by account code, supplier and currency. */
    private Preview compute(YearMonth month) {
        LocalDate end = month.atEndOfMonth();
        Map<UUID, Account> accounts = accountRepo.findBySystemKeyIsNotNull().stream()
                .filter(a -> REVALUED.contains(a.getSystemKey()))
                .collect(Collectors.toMap(Account::getId, Function.identity()));
        List<Object[]> balances = journalLineRepo.foreignBalances(accounts.keySet(), end);
        Map<UUID, Supplier> suppliers = supplierRepo.findAllById(balances.stream().map(r -> (UUID) r[1]).filter(Objects::nonNull)
                .collect(Collectors.toSet())).stream().collect(Collectors.toMap(Supplier::getId, Function.identity()));
        Map<String, ExchangeRateService.AppliedRate> applied = new HashMap<>();
        Map<String, BusinessException> missing = new TreeMap<>();
        List<Row> rows = new ArrayList<>();
        for (Object[] b : balances) {
            String currency = (String) b[2];
            BigDecimal owed = (BigDecimal) b[3];
            BigDecimal booked = (BigDecimal) b[4];
            if (owed.signum() == 0 && booked.signum() == 0) {
                continue;                                               // settled: nothing open
            }
            ExchangeRateService.AppliedRate rate = applied.get(currency);
            if (rate == null && !missing.containsKey(currency)) {
                try {
                    rate = rates.rateFor(currency, end);
                    applied.put(currency, rate);
                } catch (BusinessException e) {
                    missing.put(currency, e);
                }
            }
            Supplier supplier = b[1] == null ? null : suppliers.get((UUID) b[1]);
            if (rate == null) {
                rows.add(new Row(accounts.get((UUID) b[0]), supplier, currency, owed, booked, null, null, null, null, null));
            } else {
                FxRevaluations.Revalued revalued = FxRevaluations.revalue(owed, booked, rate.rate());
                rows.add(new Row(accounts.get((UUID) b[0]), supplier, currency, owed, booked, rate.rate(), rate.rateDate(), rate.source(),
                        revalued.revalued(), revalued.gainLoss()));
            }
        }
        rows.sort(Comparator.comparing((Row r) -> r.account().getCode())
                .thenComparing(r -> r.supplier() == null ? "" : r.supplier().getName())
                .thenComparing(Row::currencyCode));
        return new Preview(month, end, rows, List.copyOf(missing.values()));
    }
}
