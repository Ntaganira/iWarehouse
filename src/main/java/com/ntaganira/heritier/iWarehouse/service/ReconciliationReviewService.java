package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.Account;
import com.ntaganira.heritier.iWarehouse.entity.BankReconciliation;
import com.ntaganira.heritier.iWarehouse.enums.NotificationKind;
import com.ntaganira.heritier.iWarehouse.enums.ReconciliationStatus;
import com.ntaganira.heritier.iWarehouse.enums.SettingKey;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.AlertStateRepository;
import com.ntaganira.heritier.iWarehouse.repository.BankReconciliationRepository;
import com.ntaganira.heritier.iWarehouse.repository.JournalLineRepository;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : ReconciliationReviewService.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : Bank and MoMo reconciliations signed by a preparer and a reviewer (BP Table 9, P.09): another person
 *               reviews a reconciliation in force, once. Each account's compliance (Annex D, IMP 19, M.16): the months of
 *               the last year reconciled by the Settings' working day of the next month; a month not reconciled by then
 *               is told once to ALERT_DAILY_CLOSE holders.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class ReconciliationReviewService {

    static final String ALERT_PREFIX = "RECONCILIATION:";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MM/yyyy");

    private final BankReconciliationRepository repo;
    private final ReconciliationService reconciliationService;
    private final JournalLineRepository journalLineRepo;
    private final SettingService settingService;
    private final AlertStateRepository alertRepo;
    private final Notifier notifier;
    private final Clock clock;

    public ReconciliationReviewService(BankReconciliationRepository repo, ReconciliationService reconciliationService,
                                       JournalLineRepository journalLineRepo, SettingService settingService, AlertStateRepository alertRepo,
                                       Notifier notifier, Clock clock) {
        this.repo = repo;
        this.reconciliationService = reconciliationService;
        this.journalLineRepo = journalLineRepo;
        this.settingService = settingService;
        this.alertRepo = alertRepo;
        this.notifier = notifier;
        this.clock = clock;
    }

    /** An account and its months of the last year. */
    public record Compliance(Account account, ReconciliationCompliance.Result result) {
    }

    /** Each reconciled account's months, the last twelve ended (from the month of its first line). */
    public List<Compliance> compliance() {
        LocalDate today = LocalDate.now(clock);
        YearMonth last = YearMonth.from(today).minusMonths(1);
        int workingDay = settingService.getInt(SettingKey.RECONCILIATION_DUE_WORKING_DAY);
        List<Compliance> list = new ArrayList<>();
        for (Account account : reconciliationService.accounts()) {
            YearMonth from = firstMonth(account, last);
            list.add(new Compliance(account, ReconciliationCompliance.of(done(account), from, last, workingDay, today)));
        }
        return list;
    }

    public boolean isReconciler(BankReconciliation rec) {
        Long me = AppUserPrincipal.current().map(AppUserPrincipal::getId).orElse(null);
        return me != null && (me.equals(rec.getReconciledById())
                || rec.getReconciledById() == null && rec.getReconciledBy().equals(AppUserPrincipal.currentUsername()));
    }

    /** Reviews a reconciliation in force: once, never by who reconciled it. */
    @Transactional
    public BankReconciliation review(UUID id, String note) {
        BankReconciliation rec = repo.findDetailedById(id).orElseThrow(() -> new NotFoundException("BankReconciliation", id));
        if (rec.getStatus() != ReconciliationStatus.RECONCILED) {
            throw BusinessException.of("reconciliation.review.cancelled", rec.getNumber());
        }
        if (rec.isReviewed()) {
            throw BusinessException.of("reconciliation.review.done", rec.getNumber(), rec.getReviewedBy());
        }
        if (isReconciler(rec)) {
            throw BusinessException.of("reconciliation.review.own");
        }
        String clean = PartyRules.clean(note);
        if (clean != null && clean.length() > 255) {
            throw BusinessException.onField("reviewNote", "reconciliation.notes.size");
        }
        AppUserPrincipal user = AppUserPrincipal.current().orElseThrow(() -> BusinessException.of("till.noUser"));
        rec.setReviewNote(clean);
        rec.setReviewedBy(user.getUsername());
        rec.setReviewedById(user.getId());
        rec.setReviewedAt(LocalDateTime.now(clock));
        return rec;
    }

    /**
     * Tells ALERT_DAILY_CLOSE holders once per account whose last month is not reconciled by the Settings' working day
     * (Annex D: by the fifth working day); how many were told.
     */
    @Scheduled(fixedDelayString = "${app.alerts.reconciliation-every:PT6H}", initialDelayString = "${app.alerts.first-check:PT1M}")
    @Transactional
    public int checkLate() {
        LocalDateTime now = LocalDateTime.now(clock);
        YearMonth last = YearMonth.from(now).minusMonths(1);
        int workingDay = settingService.getInt(SettingKey.RECONCILIATION_DUE_WORKING_DAY);
        LocalDate due = ReconciliationCompliance.due(last, workingDay);
        if (!now.toLocalDate().isAfter(due)) {
            return 0;
        }
        int count = 0;
        for (Account account : reconciliationService.accounts()) {
            if (firstMonth(account, last).isAfter(last) || ReconciliationCompliance.reconciledOn(done(account), last).isPresent()
                    || !AlertOnce.raise(alertRepo, ALERT_PREFIX + account.getId() + ":" + last, now)) {
                continue;
            }
            notifier.holders("ALERT_DAILY_CLOSE", null, NotificationKind.CLOSE, "notify.reconciliation.late", "notify.reconciliation.lateText",
                    "/accounting/reconciliations/new", account.getCode() + " " + account.getName(), last.format(MONTH), due.format(DAY));
            count++;
        }
        return count;
    }

    private List<ReconciliationCompliance.Done> done(Account account) {
        return repo.findByAccount_IdAndStatus(account.getId(), ReconciliationStatus.RECONCILED).stream()
                .map(r -> new ReconciliationCompliance.Done(r.getStatementDate(), r.getReconciledAt().toLocalDate())).toList();
    }

    /** The first month to look at: eleven before the last, or the month of the account's first line if later (none: after the last). */
    private YearMonth firstMonth(Account account, YearMonth last) {
        YearMonth earliest = last.minusMonths(11);
        LocalDate first = journalLineRepo.firstDateOfAccount(account.getId());
        if (first == null) {
            return last.plusMonths(1);
        }
        YearMonth firstMonth = YearMonth.from(first);
        return firstMonth.isAfter(earliest) ? firstMonth : earliest;
    }
}
