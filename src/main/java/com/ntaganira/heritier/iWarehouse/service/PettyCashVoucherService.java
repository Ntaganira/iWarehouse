package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.config.NumberFormats;
import com.ntaganira.heritier.iWarehouse.dto.PettyCashVoucherDto;
import com.ntaganira.heritier.iWarehouse.entity.*;
import com.ntaganira.heritier.iWarehouse.enums.*;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.CostCentreRepository;
import com.ntaganira.heritier.iWarehouse.repository.PettyCashVoucherRepository;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import jakarta.persistence.criteria.JoinType;
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

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : PettyCashVoucherService.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Petty cash vouchers (CMP 05, 07, F-13 D, F-16; PCV-WH-2026-000001): recorded by the fund's custodian
 *               (PERM_RECORD_PETTY_CASH) with the payee, purpose, category (no capital item) and cost centre, at most the
 *               Settings' payment, dated up to today in an open month; submitted with the original receipt attached (when the
 *               Settings ask); approved by another person (PERM_APPROVE_PETTY_CASH, the head of department; never who recorded
 *               it), which locks the fund, checks it holds the amount and posts it (PostingService.pettyCashVoucher: Dr the
 *               category naming the cost centre and the site / Cr Petty Cash). Rejected or cancelled with a reason; a posted
 *               voucher is reversed with a reason (PERM_REVERSE_PETTY_CASH), never edited.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class PettyCashVoucherService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final PettyCashVoucherRepository repo;
    private final CostCentreRepository costCentreRepo;
    private final PettyCashService pettyCash;
    private final AttachmentService attachments;
    private final PostingService postingService;
    private final JournalService journalService;
    private final DocumentNumberService numbers;
    private final PeriodLock periods;
    private final SettingService settings;
    private final Notifier notifier;
    private final DelegationOfAuthority authority;
    private final NumberFormats num;
    private final Clock clock;

    public PettyCashVoucherService(PettyCashVoucherRepository repo, CostCentreRepository costCentreRepo, PettyCashService pettyCash,
                                   AttachmentService attachments, PostingService postingService, JournalService journalService,
                                   DocumentNumberService numbers, PeriodLock periods, SettingService settings, Notifier notifier,
                                   DelegationOfAuthority authority, NumberFormats num, Clock clock) {
        this.repo = repo;
        this.costCentreRepo = costCentreRepo;
        this.pettyCash = pettyCash;
        this.attachments = attachments;
        this.postingService = postingService;
        this.journalService = journalService;
        this.numbers = numbers;
        this.periods = periods;
        this.settings = settings;
        this.notifier = notifier;
        this.authority = authority;
        this.num = num;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- reading

    public Page<PettyCashVoucher> findPage(String search, String status, int page, int size) {
        Specification<PettyCashVoucher> spec = (root, query, cb) -> {
            Predicate p = cb.conjunction();
            if (StringUtils.hasText(search)) {
                String term = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                var fund = root.join("fund", JoinType.LEFT);
                p = cb.and(p, cb.or(cb.like(cb.lower(root.get("number")), term), cb.like(cb.lower(root.get("payee")), term),
                        cb.like(cb.lower(root.get("purpose")), term), cb.like(cb.lower(fund.get("code")), term)));
            }
            if ("waiting".equals(status)) {
                p = cb.and(p, cb.equal(root.get("status"), PettyCashVoucherStatus.SUBMITTED));
            } else if (StringUtils.hasText(status)) {
                try {
                    p = cb.and(p, cb.equal(root.get("status"), PettyCashVoucherStatus.valueOf(status)));
                } catch (IllegalArgumentException e) {
                    // unknown: no filter
                }
            }
            return p;
        };
        return repo.findAll(spec, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "voucherDate").and(Sort.by(Sort.Direction.DESC, "number"))));
    }

    public PettyCashVoucher findDetailed(UUID id) {
        return repo.findDetailedById(id).orElseThrow(() -> new NotFoundException("PettyCashVoucher", id));
    }

    public long waitingCount() {
        return repo.countByStatus(PettyCashVoucherStatus.SUBMITTED);
    }

    public List<JournalEntry> journals(PettyCashVoucher v) {
        List<JournalEntry> journals = new ArrayList<>();
        if (v.getJournalId() != null) {
            journals.add(journalService.findById(v.getJournalId()));
        }
        if (v.getReversalJournalId() != null) {
            journals.add(journalService.findById(v.getReversalJournalId()));
        }
        return journals;
    }

    /** The funds the signed-in user keeps (only the custodian records vouchers). */
    public List<PettyCashService.FundRow> myFunds() {
        return pettyCash.activeFunds().stream().filter(r -> pettyCash.isCustodian(r.fund())).toList();
    }

    public List<CostCentre> costCentres() {
        return costCentreRepo.findByEnabledTrueOrderByCodeAsc();
    }

    /** What petty cash may pay for: any operating category, never a capital item. */
    public List<ExpenseCategory> categories() {
        return Arrays.stream(ExpenseCategory.values()).filter(c -> !c.isCapital()).toList();
    }

    public boolean isRecorder(PettyCashVoucher v) {
        return AppUserPrincipal.current().map(u -> u.getId().equals(v.getRecordedById())).orElse(false);
    }

    /** Who recorded it, or keeps the fund, does not approve it. */
    public boolean tookPart(PettyCashVoucher v) {
        return isRecorder(v) || pettyCash.isCustodian(v.getFund());
    }

    /** Whether the signed-in user may decide it as head of its department: the cost centre's manager when named, or the COO and above. */
    public boolean isHead(PettyCashVoucher v) {
        Long manager = v.getCostCentre().getManagerUserId();
        return Expenditure.mayActAsHead(manager == null ? Set.of() : Set.of(manager), AppUserPrincipal.current().map(AppUserPrincipal::getId).orElse(null),
                authority.currentLevel().orElse(null));
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    public PettyCashVoucherDto newForm(UUID fundId) {
        PettyCashVoucherDto dto = new PettyCashVoucherDto();
        dto.setVoucherDate(today());
        List<PettyCashService.FundRow> mine = myFunds();
        dto.setFundId(fundId != null ? fundId : mine.size() == 1 ? mine.get(0).fund().getId() : null);
        return dto;
    }

    public PettyCashVoucherDto formOf(PettyCashVoucher v) {
        PettyCashVoucherDto dto = new PettyCashVoucherDto();
        dto.setFundId(v.getFund().getId());
        dto.setVoucherDate(v.getVoucherDate());
        dto.setPayee(v.getPayee());
        dto.setPurpose(v.getPurpose());
        dto.setCategory(v.getCategory());
        dto.setCostCentreId(v.getCostCentre().getId());
        dto.setAmount(v.getAmount());
        dto.setReceiptRef(v.getReceiptRef());
        return dto;
    }

    // ---------------------------------------------------------------- recording

    /** Saves a draft: a new voucher by the fund's custodian, or their draft changed. */
    @Transactional
    public PettyCashVoucher save(UUID id, PettyCashVoucherDto dto) {
        PettyCashVoucher v;
        if (id == null) {
            Optional<AppUserPrincipal> me = AppUserPrincipal.current();
            v = new PettyCashVoucher();
            v.setNumber(numbers.next(DocumentType.PETTY_CASH_VOUCHER));
            v.setRecordedBy(me.map(AppUserPrincipal::getUsername).orElse("system"));
            v.setRecordedById(me.map(AppUserPrincipal::getId).orElse(null));
            v.setRecordedAt(LocalDateTime.now(clock));
        } else {
            v = lock(id);
            if (!v.isDraft()) {
                throw BusinessException.of("pettyCash.voucher.notDraft", v.getNumber());
            }
            if (!isRecorder(v)) {
                throw BusinessException.of("pettyCash.voucher.notYours", v.getNumber(), v.getRecordedBy());
            }
        }
        PettyCashFund fund = id == null ? pettyCash.findDetailed(dto.getFundId()) : v.getFund();
        if (!fund.isActive()) {
            throw BusinessException.onField("fundId", "pettyCash.closed", fund.getCode());
        }
        if (!pettyCash.isCustodian(fund)) {
            throw BusinessException.onField("fundId", "pettyCash.voucher.custodianOnly", fund.getCode(), fund.getCustodianName());
        }
        if (dto.getCategory() == null || dto.getCategory().isCapital()) {
            throw BusinessException.onField("category", "pettyCash.voucher.category");
        }
        CostCentre centre = costCentreRepo.findById(dto.getCostCentreId())
                .orElseThrow(() -> BusinessException.onField("costCentreId", "expense.line.costCentre.required"));
        if (!centre.isEnabled()) {
            throw BusinessException.onField("costCentreId", "expense.line.costCentre.disabled", centre.getCode());
        }
        if (dto.getVoucherDate().isAfter(today())) {
            throw BusinessException.onField("voucherDate", "pettyCash.date.future");
        }
        periods.requireOpen(dto.getVoucherDate(), "voucherDate");
        BigDecimal amount = dto.getAmount().setScale(2, RoundingMode.HALF_UP);
        BigDecimal max = pettyCash.maxPayment();
        if (max != null && max.signum() > 0 && amount.compareTo(max) > 0) {
            throw BusinessException.onField("amount", "pettyCash.voucher.tooMuch", num.money(max));
        }
        v.setFund(fund);
        v.setVoucherDate(dto.getVoucherDate());
        v.setPayee(dto.getPayee().trim());
        v.setPurpose(dto.getPurpose().trim());
        v.setCategory(dto.getCategory());
        v.setCostCentre(centre);
        v.setAmount(amount);
        v.setReceiptRef(StringUtils.hasText(dto.getReceiptRef()) ? dto.getReceiptRef().trim() : null);
        return repo.save(v);
    }

    /** Its recorder submits it with the original receipt attached (CMP 07); the approvers are told. */
    @Transactional
    public PettyCashVoucher submit(UUID id) {
        PettyCashVoucher v = lock(id);
        if (!v.isDraft()) {
            throw BusinessException.of("pettyCash.voucher.notDraft", v.getNumber());
        }
        if (!isRecorder(v)) {
            throw BusinessException.of("pettyCash.voucher.notYours", v.getNumber(), v.getRecordedBy());
        }
        periods.requireOpen(v.getVoucherDate(), "voucherDate");
        if (settings.getBoolean(SettingKey.PETTY_CASH_RECEIPT_REQUIRED) && attachments.count(AttachmentOwner.PETTY_CASH_VOUCHER, v.getId()) == 0) {
            throw BusinessException.of("pettyCash.voucher.receiptRequired", v.getNumber());
        }
        v.setSubmittedAt(LocalDateTime.now(clock));
        v.setStatus(PettyCashVoucherStatus.SUBMITTED);
        Long manager = v.getCostCentre().getManagerUserId();
        if (manager != null && !manager.equals(v.getRecordedById())) {                // the department's head, when named
            notifier.user(manager, NotificationKind.APPROVAL, "notify.pettyCash.waiting", "notify.pettyCash.waitingText", link(v), v.getNumber(),
                    v.getFund().getCode(), num.money(v.getAmount()), v.getPayee(), v.getPurpose());
        } else {
            notifier.holders("APPROVE_PETTY_CASH", v.getRecordedById(), NotificationKind.APPROVAL, "notify.pettyCash.waiting", "notify.pettyCash.waitingText",
                    link(v), v.getNumber(), v.getFund().getCode(), num.money(v.getAmount()), v.getPayee(), v.getPurpose());
        }
        return v;
    }

    /** Another person approves it: the fund must hold the amount; it posts at once (F-16). */
    @Transactional
    public PettyCashVoucher approve(UUID id, String note) {
        PettyCashVoucher v = lock(id);
        if (!v.isWaiting()) {
            throw BusinessException.of("pettyCash.voucher.notWaiting", v.getNumber());
        }
        if (tookPart(v)) {
            throw BusinessException.of("pettyCash.voucher.ownStep", v.getNumber());
        }
        requireHead(v);
        PettyCashFund fund = pettyCash.lockForPayment(v.getFund().getId());
        periods.requireOpen(v.getVoucherDate(), "voucherDate");
        BigDecimal balance = pettyCash.balance(fund);
        String refusal = PettyCash.paymentRefusal(v.getAmount(), pettyCash.maxPayment(), balance);
        if ("TOO_MUCH".equals(refusal)) {
            throw BusinessException.of("pettyCash.voucher.tooMuch", num.money(pettyCash.maxPayment()));
        }
        if ("NOT_ENOUGH".equals(refusal)) {
            throw BusinessException.of("pettyCash.voucher.notEnough", v.getNumber(), fund.getCode(), num.money(balance));
        }
        String clean = note(note);
        Optional<AppUserPrincipal> me = AppUserPrincipal.current();
        JournalEntry entry = postingService.pettyCashVoucher(v);
        LocalDateTime now = LocalDateTime.now(clock);
        v.setDecidedBy(me.map(AppUserPrincipal::getUsername).orElse("system"));
        v.setDecidedById(me.map(AppUserPrincipal::getId).orElse(null));
        v.setDecidedAt(now);
        v.setDecisionNote(clean);
        v.setJournalId(entry.getId());
        v.setPostedAt(now);
        v.setStatus(PettyCashVoucherStatus.APPROVED);
        notifier.user(v.getRecordedById(), NotificationKind.DECISION, "notify.pettyCash.approved", "notify.decidedBy", link(v), v.getNumber(),
                v.getDecidedBy());
        pettyCash.refreshLow(fund, balance.subtract(v.getAmount()));
        return v;
    }

    /** Rejected with a reason by someone who took no part in it. */
    @Transactional
    public PettyCashVoucher reject(UUID id, String reason) {
        PettyCashVoucher v = lock(id);
        if (!v.isWaiting()) {
            throw BusinessException.of("pettyCash.voucher.notWaiting", v.getNumber());
        }
        if (tookPart(v)) {
            throw BusinessException.of("pettyCash.voucher.ownStep", v.getNumber());
        }
        requireHead(v);
        close(v, PettyCashVoucherStatus.REJECTED, reason(reason));
        notifier.user(v.getRecordedById(), NotificationKind.DECISION, "notify.pettyCash.rejected", "notify.rejectedBy", link(v), v.getNumber(),
                v.getDecidedBy(), v.getDecisionNote());
        return v;
    }

    /** Its recorder cancels it, with a reason, before it is approved. */
    @Transactional
    public PettyCashVoucher cancel(UUID id, String reason) {
        PettyCashVoucher v = lock(id);
        if (!v.isDraft() && !v.isWaiting()) {
            throw BusinessException.of("pettyCash.voucher.notWaiting", v.getNumber());
        }
        if (!isRecorder(v)) {
            throw BusinessException.of("pettyCash.voucher.notYours", v.getNumber(), v.getRecordedBy());
        }
        close(v, PettyCashVoucherStatus.CANCELLED, reason(reason));
        return v;
    }

    /** Reverses a posted voucher on a day from its date to today, with a reason: the cash is back in the fund's books. */
    @Transactional
    public PettyCashVoucher reverse(UUID id, LocalDate date, String reason) {
        PettyCashVoucher v = lock(id);
        if (!v.isPosted()) {
            throw BusinessException.of("pettyCash.voucher.notPosted", v.getNumber());
        }
        PettyCashFund fund = pettyCash.lockForPayment(v.getFund().getId());
        LocalDate day = date == null ? today() : date;
        if (day.isAfter(today())) {
            throw BusinessException.of("expense.reversal.future");
        }
        if (day.isBefore(v.getVoucherDate())) {
            throw BusinessException.of("expense.reversal.early", v.getVoucherDate().format(DAY));
        }
        periods.requireOpen(day);
        String clean = reason(reason);
        BigDecimal balance = pettyCash.balance(fund);
        JournalEntry reversal = journalService.reverse(journalService.findById(v.getJournalId()), day, "Reversal of " + v.getNumber() + ": " + clean);
        v.setReversalJournalId(reversal.getId());
        v.setReversalDate(day);
        v.setReversalReason(clean);
        v.setReversedBy(AppUserPrincipal.currentUsername());
        v.setReversedAt(LocalDateTime.now(clock));
        v.setStatus(PettyCashVoucherStatus.REVERSED);
        pettyCash.refreshLow(fund, balance.add(v.getAmount()));
        return v;
    }

    // ---------------------------------------------------------------- helpers

    private void requireHead(PettyCashVoucher v) {
        if (!isHead(v)) {
            throw BusinessException.of("pettyCash.voucher.notHead", v.getNumber(), v.getCostCentre().getCode(), v.getCostCentre().getManagerName());
        }
    }

    private void close(PettyCashVoucher v, PettyCashVoucherStatus status, String note) {
        Optional<AppUserPrincipal> me = AppUserPrincipal.current();
        v.setDecidedBy(me.map(AppUserPrincipal::getUsername).orElse("system"));
        v.setDecidedById(me.map(AppUserPrincipal::getId).orElse(null));
        v.setDecidedAt(LocalDateTime.now(clock));
        v.setDecisionNote(note);
        v.setStatus(status);
    }

    private static String link(PettyCashVoucher v) {
        return "/petty-cash/vouchers/" + v.getId();
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

    private PettyCashVoucher lock(UUID id) {
        repo.lockById(id).orElseThrow(() -> new NotFoundException("PettyCashVoucher", id));
        return findDetailed(id);
    }
}
