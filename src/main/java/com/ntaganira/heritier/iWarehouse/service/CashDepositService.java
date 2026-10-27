package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.Account;
import com.ntaganira.heritier.iWarehouse.entity.CashDeposit;
import com.ntaganira.heritier.iWarehouse.entity.TillSession;
import com.ntaganira.heritier.iWarehouse.enums.AccountKey;
import com.ntaganira.heritier.iWarehouse.enums.DocumentType;
import com.ntaganira.heritier.iWarehouse.enums.NotificationKind;
import com.ntaganira.heritier.iWarehouse.enums.SettingKey;
import com.ntaganira.heritier.iWarehouse.enums.TillStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.AccountRepository;
import com.ntaganira.heritier.iWarehouse.repository.AlertStateRepository;
import com.ntaganira.heritier.iWarehouse.repository.CashDepositRepository;
import com.ntaganira.heritier.iWarehouse.repository.TillSessionRepository;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : CashDepositService.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Takings banked (F-13, SDP 10, P.04): closed tills whose count Treasury verified (when the Settings ask
 *               it) are banked with the deposit slip's number, each till once, the amount exactly their cash less their
 *               floats: banked in full, never netted. Posted when saved (Dr Bank / Cr Main Cash Vault). A till closed
 *               before today and not banked is told once to ALERT_DAILY_CLOSE holders.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class CashDepositService {

    static final String ALERT_PREFIX = "BANKING:";

    private final CashDepositRepository repo;
    private final TillSessionRepository tillRepo;
    private final AccountRepository accountRepo;
    private final PostingService postingService;
    private final DocumentNumberService numbers;
    private final SettingService settingService;
    private final AlertStateRepository alertRepo;
    private final Notifier notifier;
    private final Clock clock;

    public CashDepositService(CashDepositRepository repo, TillSessionRepository tillRepo, AccountRepository accountRepo,
                              PostingService postingService, DocumentNumberService numbers, SettingService settingService,
                              AlertStateRepository alertRepo, Notifier notifier, Clock clock) {
        this.repo = repo;
        this.tillRepo = tillRepo;
        this.accountRepo = accountRepo;
        this.postingService = postingService;
        this.numbers = numbers;
        this.settingService = settingService;
        this.alertRepo = alertRepo;
        this.notifier = notifier;
        this.clock = clock;
    }

    /** What a deposit says: the tills it banks, the slip's number, the amount on the slip, notes. */
    public record Form(List<UUID> tillIds, String slipNumber, BigDecimal amount, String notes) {
    }

    // ---------------------------------------------------------------- reading

    /** Closed tills with cash to bank, oldest first. */
    public List<TillSession> toBank() {
        return tillRepo.findByStatusAndDepositIdIsNullOrderByClosedAtAsc(TillStatus.CLOSED).stream()
                .filter(t -> t.getCashToBank().signum() > 0).toList();
    }

    /** Treasury verifies a till's count before its cash is banked. */
    public boolean verifyRequired() {
        return settingService.getBoolean(SettingKey.CASH_VERIFY_COUNT);
    }

    public Page<CashDeposit> findPage(int page, int size) {
        return repo.findAllBy(PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "depositDate").and(Sort.by(Sort.Direction.DESC, "number"))));
    }

    public CashDeposit findDetailed(UUID id) {
        return repo.findDetailedById(id).orElseThrow(() -> new NotFoundException("CashDeposit", id));
    }

    public List<TillSession> tillsOf(UUID depositId) {
        return tillRepo.findByDepositIdOrderByClosedAtAsc(depositId);
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    // ---------------------------------------------------------------- banking

    /**
     * Banks the tills chosen: each closed, not banked, verified when the Settings ask it, with cash to bank; the slip's
     * amount is exactly theirs. The tills are locked first, so each is banked once.
     */
    @Transactional
    public CashDeposit bank(Form form) {
        String slip = PartyRules.clean(form.slipNumber());
        if (slip == null || slip.length() > 40) {
            throw BusinessException.onField("slipNumber", "deposit.slip.required");
        }
        Set<UUID> ids = form.tillIds() == null ? Set.of() : new LinkedHashSet<>(form.tillIds());
        if (ids.isEmpty()) {
            throw BusinessException.onField("tillIds", "deposit.tills.required");
        }
        String notes = PartyRules.clean(form.notes());
        if (notes != null && notes.length() > 255) {
            throw BusinessException.onField("notes", "deposit.notes.size");
        }
        List<TillSession> tills = tillRepo.lockAllById(ids);
        if (tills.size() != ids.size()) {
            throw BusinessException.onField("tillIds", "deposit.tills.required");
        }
        boolean verify = verifyRequired();
        BigDecimal expected = BigDecimal.ZERO;
        for (TillSession t : tills) {
            if (t.getStatus() != TillStatus.CLOSED) {
                throw BusinessException.onField("tillIds", "deposit.till.open", t.getNumber());
            }
            if (t.isBanked()) {
                throw BusinessException.onField("tillIds", "deposit.till.banked", t.getNumber());
            }
            if (verify && !t.isVerified()) {
                throw BusinessException.onField("tillIds", "deposit.till.notVerified", t.getNumber());
            }
            if (t.getCashToBank().signum() <= 0) {
                throw BusinessException.onField("tillIds", "deposit.till.nothing", t.getNumber());
            }
            expected = expected.add(t.getCashToBank());
        }
        if (form.amount() == null || form.amount().compareTo(expected) != 0) {
            throw BusinessException.onField("amount", "deposit.amount.netted", rwf(expected));
        }
        Account bank = accountRepo.findBySystemKey(AccountKey.BANK).orElseThrow(() -> new IllegalStateException("No bank account"));
        if (repo.existsByAccount_IdAndSlipNumber(bank.getId(), slip)) {
            throw BusinessException.onField("slipNumber", "deposit.slip.used", slip);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        CashDeposit deposit = new CashDeposit();
        deposit.setNumber(numbers.next(DocumentType.CASH_DEPOSIT));
        deposit.setDepositDate(now.toLocalDate());
        deposit.setAccount(bank);
        deposit.setSlipNumber(slip);
        deposit.setAmount(expected.setScale(2));
        deposit.setNotes(notes);
        deposit.setPostedAt(now);
        deposit.setPostedBy(AppUserPrincipal.currentUsername());
        repo.save(deposit);
        for (TillSession t : tills) {
            t.setDepositId(deposit.getId());
            AlertOnce.clear(alertRepo, ALERT_PREFIX + t.getId(), now);
        }
        postingService.cashDeposit(deposit);
        return deposit;
    }

    /**
     * Tells ALERT_DAILY_CLOSE holders once per till closed before today whose cash is not banked (SDP 10: banked the same
     * day); how many were told.
     */
    @Scheduled(fixedDelayString = "${app.alerts.banking-every:PT1H}", initialDelayString = "${app.alerts.first-check:PT1M}")
    @Transactional
    public int checkBanking() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime today = now.toLocalDate().atStartOfDay();
        int count = 0;
        for (TillSession t : toBank()) {
            if (!t.getClosedAt().isBefore(today) || !AlertOnce.raise(alertRepo, ALERT_PREFIX + t.getId(), now)) {
                continue;
            }
            notifier.holders("ALERT_DAILY_CLOSE", null, NotificationKind.CLOSE, "notify.banking.late", "notify.banking.lateText",
                    "/cash-deposits/new", t.getNumber(), t.getCashierUsername(), rwf(t.getCashToBank()));
            count++;
        }
        return count;
    }

    static String rwf(BigDecimal amount) {
        return amount.setScale(0, RoundingMode.HALF_UP).toPlainString();
    }
}
