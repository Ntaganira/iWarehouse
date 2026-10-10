package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.AccountingPeriod;
import com.ntaganira.heritier.iWarehouse.entity.FxRevaluation;
import com.ntaganira.heritier.iWarehouse.enums.ManualJournalStatus;
import com.ntaganira.heritier.iWarehouse.enums.PeriodStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.AccountingPeriodRepository;
import com.ntaganira.heritier.iWarehouse.repository.JournalEntryRepository;
import com.ntaganira.heritier.iWarehouse.repository.ManualJournalRepository;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : PeriodService.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : Monthly period close (ACC-10). Months close in order (Periods.nextToClose), each once it has ended and
 *               its books are complete: no manual journal dated in it or before waits for approval, and its foreign
 *               balances are revalued when anything would move (FxRevaluationService.state). Closing locks the
 *               journal table, so no journal is half-posted on the month meanwhile; afterwards PeriodLock and a
 *               trigger refuse any journal dated on it. Only the latest closed month is reopened, with a reason, so
 *               the closed months stay the first ones.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class PeriodService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final AccountingPeriodRepository repo;
    private final JournalEntryRepository entryRepo;
    private final ManualJournalRepository manualJournalRepo;
    private final FxRevaluationService fxRevaluationService;
    private final EntityManager em;
    private final Clock clock;

    public PeriodService(AccountingPeriodRepository repo, JournalEntryRepository entryRepo, ManualJournalRepository manualJournalRepo,
                         FxRevaluationService fxRevaluationService, EntityManager em, Clock clock) {
        this.repo = repo;
        this.entryRepo = entryRepo;
        this.manualJournalRepo = manualJournalRepo;
        this.fxRevaluationService = fxRevaluationService;
        this.em = em;
        this.clock = clock;
    }

    /** What closing a month waits for: the manual journals still to decide and the FX revaluation. */
    public record Checklist(YearMonth month, long manualJournalsWaiting, FxRevaluationService.MonthState revaluation,
                            FxRevaluation revaluationDone) {

        public LocalDate getPeriodEnd() {
            return month.atEndOfMonth();
        }

        public boolean isReady() {
            return manualJournalsWaiting == 0 && revaluation != FxRevaluationService.MonthState.DUE;
        }
    }

    // ---------------------------------------------------------------- reading

    /** The last day of the latest closed month, or null. */
    public LocalDate closedThrough() {
        return repo.closedThrough();
    }

    /** The month to close next, once it has ended. */
    public Optional<YearMonth> next() {
        return Periods.nextToClose(entryRepo.firstEntryDate(), repo.closedThrough(), today());
    }

    /**
     * The month after the latest closed one (or the ledger's first month), even if it has not ended: the page says from when it
     * can be closed. Empty before the first journal.
     */
    public Optional<YearMonth> following() {
        LocalDate through = repo.closedThrough();
        if (through != null) {
            return Optional.of(YearMonth.from(through).plusMonths(1));
        }
        LocalDate first = entryRepo.firstEntryDate();
        return first == null ? Optional.empty() : Optional.of(YearMonth.from(first));
    }

    public Checklist checklist(YearMonth month) {
        LocalDate end = month.atEndOfMonth();
        long waiting = manualJournalRepo.countByStatusAndEntryDateLessThanEqual(ManualJournalStatus.PENDING_APPROVAL, end);
        return new Checklist(month, waiting, fxRevaluationService.state(month), fxRevaluationService.ofMonth(month).orElse(null));
    }

    public Page<AccountingPeriod> findPage(int page, int size) {
        return repo.findAll(PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "periodEnd")));
    }

    public AccountingPeriod findById(UUID id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException("AccountingPeriod", id));
    }

    /** Only the latest closed month can be reopened. */
    public boolean canReopen(AccountingPeriod period) {
        return period.isClosed() && period.getPeriodEnd().equals(repo.closedThrough());
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    // ---------------------------------------------------------------- closing

    /**
     * Closes the month to close next. The journal table is locked first (SHARE: journals being posted finish, new ones wait),
     * so the checks and the close see every journal of the month and none slips in before the close commits.
     */
    @Transactional
    public AccountingPeriod close(YearMonth month) {
        em.createNativeQuery("LOCK TABLE journal_entries IN SHARE MODE").executeUpdate();
        Optional<YearMonth> next = next();
        if (month == null || next.isEmpty() || !next.get().equals(month)) {
            throw BusinessException.of("period.notNext", month == null ? "" : month.atEndOfMonth().format(DAY));
        }
        Checklist checklist = checklist(month);
        if (checklist.manualJournalsWaiting() > 0) {
            throw BusinessException.of("period.journalsWaiting", checklist.manualJournalsWaiting());
        }
        if (checklist.revaluation() == FxRevaluationService.MonthState.DUE) {
            throw BusinessException.of("period.revaluationDue", month.atEndOfMonth().format(DAY));
        }
        AccountingPeriod period = repo.findByPeriodEnd(month.atEndOfMonth()).orElseGet(() -> {
            AccountingPeriod p = new AccountingPeriod();
            p.setPeriodEnd(month.atEndOfMonth());
            return p;
        });
        period.setStatus(PeriodStatus.CLOSED);
        period.setClosedBy(AppUserPrincipal.currentUsername());
        period.setClosedAt(LocalDateTime.now(clock));
        return repo.save(period);
    }

    /** Reopens the latest closed month, with a reason: documents can be posted on it again until it is closed again. */
    @Transactional
    public AccountingPeriod reopen(UUID id, String reason) {
        AccountingPeriod period = findById(id);
        if (!canReopen(period)) {
            throw BusinessException.of("period.notLatest");
        }
        period.setStatus(PeriodStatus.REOPENED);
        period.setReopenedBy(AppUserPrincipal.currentUsername());
        period.setReopenedAt(LocalDateTime.now(clock));
        period.setReopenReason(reason.trim());
        return period;
    }
}
