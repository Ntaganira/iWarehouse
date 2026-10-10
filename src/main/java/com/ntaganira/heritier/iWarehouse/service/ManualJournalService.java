package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.enums.NotificationKind;
import com.ntaganira.heritier.iWarehouse.dto.ManualJournalDto;
import com.ntaganira.heritier.iWarehouse.entity.Account;
import com.ntaganira.heritier.iWarehouse.entity.JournalEntry;
import com.ntaganira.heritier.iWarehouse.entity.ManualJournal;
import com.ntaganira.heritier.iWarehouse.entity.ManualJournalLine;
import com.ntaganira.heritier.iWarehouse.enums.DocumentType;
import com.ntaganira.heritier.iWarehouse.enums.JournalSource;
import com.ntaganira.heritier.iWarehouse.enums.ManualJournalStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.AccountRepository;
import com.ntaganira.heritier.iWarehouse.repository.JournalEntryRepository;
import com.ntaganira.heritier.iWarehouse.repository.ManualJournalLineRepository;
import com.ntaganira.heritier.iWarehouse.repository.ManualJournalRepository;
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
 * - File      : ManualJournalService.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : Manual journals (ACC-05). Asked for by account (MJ-WH-2026-000001): a date up to today, a
 *               description, at least two lines, each a debit or a credit on an active account that is not a
 *               control account (ManualJournals.CONTROLLED), balanced. It waits for another person: approving
 *               posts it through JournalService.postLines (the one journal not made by a posting rule) on its own
 *               date; rejecting or withdrawing (the requester) needs a reason. A posted manual journal is never
 *               deleted: reversing it posts the opposite journal on a later day, with a reason. Deciding and
 *               reversing lock it first.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class ManualJournalService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final ManualJournalRepository repo;
    private final ManualJournalLineRepository lineRepo;
    private final AccountRepository accountRepo;
    private final JournalEntryRepository entryRepo;
    private final JournalService journalService;
    private final DocumentNumberService numbers;
    private final PeriodLock periods;
    private final Notifier notifier;
    private final Clock clock;

    public ManualJournalService(ManualJournalRepository repo, ManualJournalLineRepository lineRepo, AccountRepository accountRepo,
                                JournalEntryRepository entryRepo, JournalService journalService, DocumentNumberService numbers,
                                PeriodLock periods, Notifier notifier, Clock clock) {
        this.notifier = notifier;
        this.repo = repo;
        this.lineRepo = lineRepo;
        this.accountRepo = accountRepo;
        this.entryRepo = entryRepo;
        this.journalService = journalService;
        this.numbers = numbers;
        this.periods = periods;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- reading

    public Page<ManualJournal> findPage(String search, String status, int page, int size) {
        Specification<ManualJournal> spec = (root, query, cb) -> {
            Predicate p = cb.conjunction();
            if (StringUtils.hasText(search)) {
                String term = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                p = cb.and(p, cb.or(
                        cb.like(cb.lower(root.get("number")), term),
                        cb.like(cb.lower(root.get("description")), term),
                        cb.like(cb.lower(root.get("requestedBy")), term)));
            }
            ManualJournalStatus s = status(status);
            if (s != null) {
                p = cb.and(p, cb.equal(root.get("status"), s));
            }
            return p;
        };
        return repo.findAll(spec, PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "requestedAt").and(Sort.by(Sort.Direction.DESC, "number"))));
    }

    public ManualJournal findById(UUID id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException("ManualJournal", id));
    }

    public List<ManualJournalLine> lines(UUID id) {
        return lineRepo.findByManualJournalIdOrderByLineNo(id);
    }

    /** Its journal and, once reversed, the reversal. */
    public List<JournalEntry> journals(ManualJournal journal) {
        List<JournalEntry> journals = new ArrayList<>();
        if (journal.getJournalId() != null) {
            entryRepo.findById(journal.getJournalId()).ifPresent(journals::add);
        }
        if (journal.getReversalJournalId() != null) {
            entryRepo.findById(journal.getReversalJournalId()).ifPresent(journals::add);
        }
        return journals;
    }

    public long pendingCount() {
        return repo.countByStatus(ManualJournalStatus.PENDING_APPROVAL);
    }

    /** The accounts a manual journal may use: active and not a control account, by code. */
    public List<Account> accounts() {
        return accountRepo.findAll(Sort.by("code")).stream()
                .filter(a -> a.isEnabled() && !ManualJournals.isControlled(a.getSystemKey())).toList();
    }

    /** The control accounts, by code: posted by their documents only. */
    public List<Account> controlledAccounts() {
        return accountRepo.findAll(Sort.by("code")).stream().filter(a -> ManualJournals.isControlled(a.getSystemKey())).toList();
    }

    /** A new form, dated today: empty, or a copy of another manual journal's description and lines. */
    public ManualJournalDto newForm(UUID copyOf) {
        ManualJournalDto dto = new ManualJournalDto();
        dto.setEntryDate(today());
        if (copyOf != null) {
            ManualJournal source = findById(copyOf);
            dto.setDescription(source.getDescription());
            for (ManualJournalLine l : lines(copyOf)) {
                dto.getLines().add(new ManualJournalDto.Line(l.getAccount().getId(), l.getDebit().signum() > 0 ? l.getDebit() : null,
                        l.getCredit().signum() > 0 ? l.getCredit() : null, l.getMemo()));
            }
        }
        while (dto.getLines().size() < 2) {
            dto.getLines().add(new ManualJournalDto.Line());
        }
        return dto;
    }

    public boolean isRequester(ManualJournal journal) {
        Optional<AppUserPrincipal> user = AppUserPrincipal.current();
        if (user.isPresent() && journal.getRequestedById() != null) {
            return journal.getRequestedById().equals(user.get().getId());
        }
        return journal.getRequestedBy().equals(user.map(AppUserPrincipal::getUsername).orElse("system"));
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    /** The first day a journal may be dated on: the day after the latest closed month (null when no month is closed). */
    public LocalDate openFrom() {
        LocalDate through = periods.closedThrough();
        return through == null ? null : through.plusDays(1);
    }

    // ---------------------------------------------------------------- asking

    /** Asks for a manual journal: checked, numbered and saved waiting for approval. Blank rows are dropped by the caller. */
    @Transactional
    public ManualJournal create(ManualJournalDto dto) {
        if (dto.getEntryDate().isAfter(today())) {
            throw BusinessException.onField("entryDate", "manualJournal.date.future");
        }
        periods.requireOpen(dto.getEntryDate(), "entryDate");
        List<ManualJournalDto.Line> rows = dto.getLines();
        if (rows.size() < 2) {
            throw BusinessException.of("manualJournal.lines.required");
        }
        Map<UUID, Account> accounts = accountRepo.findAllById(rows.stream().map(ManualJournalDto.Line::getAccountId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Account::getId, Function.identity()));
        List<ManualJournals.Amounts> amounts = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            ManualJournalDto.Line row = rows.get(i);
            Account account = accounts.get(row.getAccountId());
            if (account == null) {
                throw BusinessException.onField("lines[" + i + "].accountId", "manualJournal.line.account.required");
            }
            requireUsable(account, "lines[" + i + "].accountId");
            ManualJournals.Amounts a = new ManualJournals.Amounts(row.getDebit(), row.getCredit());
            if (!a.isOneSided()) {
                throw BusinessException.onField("lines[" + i + "].debit", "manualJournal.line.oneSide");
            }
            amounts.add(a);
        }
        ManualJournals.Totals totals = ManualJournals.totals(amounts);
        if (!totals.isBalanced()) {
            throw BusinessException.of("manualJournal.unbalanced", totals.debits(), totals.credits(), totals.getDifference().abs());
        }
        Optional<AppUserPrincipal> user = AppUserPrincipal.current();
        ManualJournal journal = new ManualJournal();
        journal.setNumber(numbers.next(DocumentType.MANUAL_JOURNAL));
        journal.setEntryDate(dto.getEntryDate());
        journal.setDescription(dto.getDescription().trim());
        journal.setTotal(totals.debits().setScale(Journal.SCALE, RoundingMode.HALF_UP));
        journal.setStatus(ManualJournalStatus.PENDING_APPROVAL);
        journal.setRequestedById(user.map(AppUserPrincipal::getId).orElse(null));
        journal.setRequestedBy(user.map(AppUserPrincipal::getUsername).orElse("system"));
        journal.setRequestedAt(LocalDateTime.now(clock));
        repo.save(journal);
        for (int i = 0; i < rows.size(); i++) {
            ManualJournalLine line = new ManualJournalLine();
            line.setManualJournalId(journal.getId());
            line.setLineNo(i + 1);
            line.setAccount(accounts.get(rows.get(i).getAccountId()));
            line.setDebit(amounts.get(i).getDebit().setScale(Journal.SCALE, RoundingMode.HALF_UP));
            line.setCredit(amounts.get(i).getCredit().setScale(Journal.SCALE, RoundingMode.HALF_UP));
            line.setMemo(PartyRules.clean(rows.get(i).getMemo()));
            lineRepo.save(line);
        }
        notifier.holders("APPROVE_MANUAL_JOURNAL", journal.getRequestedById(), NotificationKind.APPROVAL, "notify.manualJournal.waiting",
                "notify.manualJournal.waitingText", "/accounting/manual-journals/" + journal.getId(), journal.getNumber(), journal.getRequestedBy(),
                journal.getDescription());
        return journal;
    }

    // ---------------------------------------------------------------- deciding

    /** Another person approves it: its journal posts on its own date. Its accounts are checked again (they may have changed). */
    @Transactional
    public ManualJournal approve(UUID id, String note) {
        ManualJournal journal = lock(id);
        requirePending(journal);
        if (isRequester(journal)) {
            throw BusinessException.of("manualJournal.ownApproval", journal.getNumber());
        }
        List<JournalService.AccountLine> lines = new ArrayList<>();
        for (ManualJournalLine l : lines(id)) {
            requireUsable(l.getAccount(), null);
            lines.add(new JournalService.AccountLine(l.getAccount(), l.getDebit(), l.getCredit(), l.getMemo()));
        }
        JournalEntry entry = journalService.postLines(JournalSource.MANUAL_JOURNAL, journal.getId(), journal.getNumber(),
                journal.getEntryDate(), journal.getDescription(), lines);
        decide(journal, ManualJournalStatus.POSTED, PartyRules.clean(note));
        journal.setJournalId(entry.getId());
        notifier.user(journal.getRequestedById(), NotificationKind.DECISION, "notify.manualJournal.approved", "notify.decidedBy",
                "/accounting/manual-journals/" + journal.getId(), journal.getNumber(), journal.getDecidedBy());
        return journal;
    }

    /** Another person rejects it, with a reason: nothing is posted. */
    @Transactional
    public ManualJournal reject(UUID id, String reason) {
        ManualJournal journal = lock(id);
        requirePending(journal);
        if (isRequester(journal)) {
            throw BusinessException.of("manualJournal.ownReject", journal.getNumber());
        }
        decide(journal, ManualJournalStatus.REJECTED, reason.trim());
        notifier.user(journal.getRequestedById(), NotificationKind.DECISION, "notify.manualJournal.rejected", "notify.rejectedBy",
                "/accounting/manual-journals/" + journal.getId(), journal.getNumber(), journal.getDecidedBy(), journal.getDecisionNote());
        return journal;
    }

    /** The requester withdraws it, with a reason. */
    @Transactional
    public ManualJournal cancel(UUID id, String reason) {
        ManualJournal journal = lock(id);
        requirePending(journal);
        if (!isRequester(journal)) {
            throw BusinessException.of("manualJournal.notYours", journal.getNumber(), journal.getRequestedBy());
        }
        decide(journal, ManualJournalStatus.CANCELLED, reason.trim());
        return journal;
    }

    // ---------------------------------------------------------------- reversing

    /**
     * Reverses a posted manual journal: the opposite journal on {@code date} (today when none), not before the journal's own
     * date nor after today, with a reason. Once only.
     */
    @Transactional
    public ManualJournal reverse(UUID id, LocalDate date, String reason) {
        ManualJournal journal = lock(id);
        if (journal.getStatus() != ManualJournalStatus.POSTED) {
            throw BusinessException.of("manualJournal.notPosted", journal.getNumber());
        }
        LocalDate day = date == null ? today() : date;
        if (day.isAfter(today())) {
            throw BusinessException.of("manualJournal.reversal.future");
        }
        if (day.isBefore(journal.getEntryDate())) {
            throw BusinessException.of("manualJournal.reversal.early", journal.getEntryDate().format(DAY));
        }
        periods.requireOpen(day);
        JournalEntry original = journalService.findById(journal.getJournalId());
        JournalEntry reversal = journalService.reverse(original, day, "Reversal of " + journal.getNumber() + ": " + reason.trim());
        journal.setStatus(ManualJournalStatus.REVERSED);
        journal.setReversalJournalId(reversal.getId());
        journal.setReversalDate(day);
        journal.setReversalReason(reason.trim());
        journal.setReversedBy(AppUserPrincipal.currentUsername());
        journal.setReversedAt(LocalDateTime.now(clock));
        return journal;
    }

    // ---------------------------------------------------------------- helpers

    private ManualJournal lock(UUID id) {
        return repo.lockById(id).orElseThrow(() -> new NotFoundException("ManualJournal", id));
    }

    private static void requirePending(ManualJournal journal) {
        if (!journal.isPending()) {
            throw BusinessException.of("manualJournal.notPending", journal.getNumber());
        }
    }

    /** An active account that is not a control account; refused next to the field when one is given. */
    private static void requireUsable(Account account, String field) {
        String name = account.getCode() + " " + account.getName();
        if (!account.isEnabled()) {
            throw field == null ? BusinessException.of("manualJournal.line.account.disabled", name)
                    : BusinessException.onField(field, "manualJournal.line.account.disabled", name);
        }
        if (ManualJournals.isControlled(account.getSystemKey())) {
            throw field == null ? BusinessException.of("manualJournal.line.account.controlled", name)
                    : BusinessException.onField(field, "manualJournal.line.account.controlled", name);
        }
    }

    private void decide(ManualJournal journal, ManualJournalStatus status, String note) {
        journal.setStatus(status);
        journal.setDecidedBy(AppUserPrincipal.currentUsername());
        journal.setDecidedAt(LocalDateTime.now(clock));
        journal.setDecisionNote(note);
    }

    private static ManualJournalStatus status(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return ManualJournalStatus.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null; // unknown status: no filter
        }
    }
}
