package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.Account;
import com.ntaganira.heritier.iWarehouse.entity.BankReconciliation;
import com.ntaganira.heritier.iWarehouse.entity.BankReconciliationLine;
import com.ntaganira.heritier.iWarehouse.entity.JournalLine;
import com.ntaganira.heritier.iWarehouse.enums.AccountKey;
import com.ntaganira.heritier.iWarehouse.enums.DocumentType;
import com.ntaganira.heritier.iWarehouse.enums.ReconciliationStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.AccountRepository;
import com.ntaganira.heritier.iWarehouse.repository.BankReconciliationLineRepository;
import com.ntaganira.heritier.iWarehouse.repository.BankReconciliationRepository;
import com.ntaganira.heritier.iWarehouse.repository.JournalLineRepository;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * - File      : ReconciliationService.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : Bank and mobile-money reconciliation (ACC-12). A statement of the Bank or Mobile Money account (its date,
 *               after the previous statement's and up to today, and its closing balance) is reconciled by ticking the
 *               account's lines up to that date not cleared yet: the previous statement's balance plus the lines
 *               ticked must give the closing balance (Reconciliations). Saved reconciled (REC) with the account's
 *               balance at the date and what was outstanding; each line it clears is kept (append-only). The latest
 *               reconciliation of an account can be cancelled with a reason: its lines are open again. Both lock the
 *               account first, so one statement of it is reconciled at a time.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class ReconciliationService {

    /** The accounts reconciled with a statement. */
    static final List<AccountKey> RECONCILED = List.of(AccountKey.BANK, AccountKey.MOBILE_MONEY);

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final BankReconciliationRepository repo;
    private final BankReconciliationLineRepository lineRepo;
    private final AccountRepository accountRepo;
    private final JournalLineRepository journalLineRepo;
    private final DocumentNumberService numbers;
    private final Clock clock;

    public ReconciliationService(BankReconciliationRepository repo, BankReconciliationLineRepository lineRepo, AccountRepository accountRepo,
                                 JournalLineRepository journalLineRepo, DocumentNumberService numbers, Clock clock) {
        this.repo = repo;
        this.lineRepo = lineRepo;
        this.accountRepo = accountRepo;
        this.journalLineRepo = journalLineRepo;
        this.numbers = numbers;
        this.clock = clock;
    }

    /**
     * A statement being reconciled: the account, the previous statement (its date and balance; none for the first), the
     * account's balance at the statement date and its lines up to then not cleared yet.
     */
    public record Draft(Account account, LocalDate statementDate, BigDecimal statementBalance, BankReconciliation previous,
                        BigDecimal bookBalance, List<JournalLine> open) {

        public BigDecimal getPreviousBalance() {
            return previous == null ? BigDecimal.ZERO : previous.getStatementBalance();
        }

        /** What the lines ticked must add up to: the statement's balance less the previous one. */
        public BigDecimal getToClear() {
            return statementBalance.subtract(getPreviousBalance());
        }
    }

    // ---------------------------------------------------------------- reading

    /** The Bank and Mobile Money accounts, active, by code. */
    public List<Account> accounts() {
        return accountRepo.findBySystemKeyIsNotNull().stream()
                .filter(a -> RECONCILED.contains(a.getSystemKey()) && a.isEnabled())
                .sorted(Comparator.comparing(Account::getCode)).toList();
    }

    /** The latest reconciliation in force of an account, if any. */
    public Optional<BankReconciliation> latest(UUID accountId) {
        return repo.findFirstByAccount_IdAndStatusOrderByStatementDateDesc(accountId, ReconciliationStatus.RECONCILED);
    }

    /** A statement to reconcile: checked (a reconciled account, a date after the previous statement's and up to today). */
    public Draft draft(UUID accountId, LocalDate statementDate, BigDecimal statementBalance) {
        Account account = reconcilable(accountRepo.findById(accountId).orElseThrow(() -> new NotFoundException("Account", accountId)));
        BankReconciliation previous = latest(accountId).orElse(null);
        if (statementDate == null || statementDate.isAfter(today())) {
            throw BusinessException.onField("statementDate", "reconciliation.date.invalid");
        }
        if (previous != null && !statementDate.isAfter(previous.getStatementDate())) {
            throw BusinessException.onField("statementDate", "reconciliation.date.early", previous.getStatementDate().format(DAY));
        }
        if (statementBalance == null || statementBalance.stripTrailingZeros().scale() > 2) {
            throw BusinessException.onField("statementBalance", "reconciliation.balance.invalid");
        }
        Set<UUID> cleared = lineRepo.clearedLineIds(accountId);
        List<JournalLine> lines = journalLineRepo.findAccountLinesUpTo(accountId, statementDate);
        BigDecimal book = lines.stream().map(l -> l.getDebit().subtract(l.getCredit())).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<JournalLine> open = lines.stream().filter(l -> !cleared.contains(l.getId())).toList();
        return new Draft(account, statementDate, statementBalance.setScale(2, RoundingMode.HALF_UP), previous, book, open);
    }

    public Page<BankReconciliation> findPage(int page, int size) {
        return repo.findAllBy(PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "statementDate").and(Sort.by(Sort.Direction.DESC, "number"))));
    }

    public BankReconciliation findDetailed(UUID id) {
        return repo.findDetailedById(id).orElseThrow(() -> new NotFoundException("BankReconciliation", id));
    }

    /** The lines a reconciliation cleared, in the order they were posted. */
    public List<BankReconciliationLine> lines(UUID id) {
        List<BankReconciliationLine> lines = new ArrayList<>(lineRepo.findByReconciliationId(id));
        lines.sort(Comparator.comparing((BankReconciliationLine l) -> l.getJournalLine().getEntry().getEntryDate())
                .thenComparing(l -> l.getJournalLine().getEntry().getNumber()).thenComparing(l -> l.getJournalLine().getLineNo()));
        return lines;
    }

    /** Only the latest reconciliation in force of its account can be cancelled. */
    public boolean canCancel(BankReconciliation reconciliation) {
        return reconciliation.isReconciled() && latest(reconciliation.getAccount().getId())
                .map(l -> l.getId().equals(reconciliation.getId())).orElse(false);
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    // ---------------------------------------------------------------- reconciling

    /**
     * Reconciles a statement with the lines ticked: they must be open lines of the account up to the statement date, and take
     * the previous statement's balance to this one exactly.
     */
    @Transactional
    public BankReconciliation reconcile(UUID accountId, LocalDate statementDate, BigDecimal statementBalance, Collection<UUID> ticked, String notes) {
        accountRepo.lockById(accountId).orElseThrow(() -> new NotFoundException("Account", accountId));
        Draft draft = draft(accountId, statementDate, statementBalance);
        Map<UUID, JournalLine> open = new LinkedHashMap<>();
        draft.open().forEach(l -> open.put(l.getId(), l));
        Set<UUID> chosen = new LinkedHashSet<>(ticked == null ? List.of() : ticked);
        if (!open.keySet().containsAll(chosen)) {
            throw BusinessException.of("reconciliation.lineNotOpen");
        }
        Reconciliations.Result result = Reconciliations.of(draft.getPreviousBalance(), draft.statementBalance(),
                open.values().stream().map(l -> new Reconciliations.Line(l.getId(), l.getDebit(), l.getCredit())).toList(), chosen);
        if (!result.isReconciled()) {
            throw BusinessException.of("reconciliation.difference", result.difference());
        }
        String note = PartyRules.clean(notes);
        if (note != null && note.length() > 255) {
            throw BusinessException.onField("notes", "reconciliation.notes.size");
        }
        BankReconciliation rec = new BankReconciliation();
        rec.setNumber(numbers.next(DocumentType.BANK_RECONCILIATION));
        rec.setAccount(draft.account());
        rec.setStatementDate(statementDate);
        rec.setStatementBalance(draft.statementBalance());
        rec.setPreviousBalance(draft.getPreviousBalance());
        rec.setClearedAmount(result.cleared());
        rec.setBookBalance(draft.bookBalance());
        rec.setOutstandingDeposits(result.outstandingDeposits());
        rec.setOutstandingPayments(result.outstandingPayments());
        rec.setNotes(note);
        rec.setStatus(ReconciliationStatus.RECONCILED);
        rec.setReconciledBy(AppUserPrincipal.currentUsername());
        rec.setReconciledAt(LocalDateTime.now(clock));
        repo.save(rec);
        for (UUID id : chosen) {
            JournalLine l = open.get(id);
            BankReconciliationLine line = new BankReconciliationLine();
            line.setReconciliationId(rec.getId());
            line.setJournalLine(l);
            line.setDebit(l.getDebit());
            line.setCredit(l.getCredit());
            lineRepo.save(line);
        }
        return rec;
    }

    /** Cancels the latest reconciliation of its account, with a reason: the lines it cleared are open again. */
    @Transactional
    public BankReconciliation cancel(UUID id, String reason) {
        BankReconciliation rec = findDetailed(id);
        accountRepo.lockById(rec.getAccount().getId());
        if (!canCancel(rec)) {
            throw BusinessException.of("reconciliation.notLatest");
        }
        rec.setStatus(ReconciliationStatus.CANCELLED);
        rec.setCancelledBy(AppUserPrincipal.currentUsername());
        rec.setCancelledAt(LocalDateTime.now(clock));
        rec.setCancelReason(reason.trim());
        return rec;
    }

    private static Account reconcilable(Account account) {
        if (!RECONCILED.contains(account.getSystemKey())) {
            throw BusinessException.onField("accountId", "reconciliation.account.invalid");
        }
        return account;
    }
}
