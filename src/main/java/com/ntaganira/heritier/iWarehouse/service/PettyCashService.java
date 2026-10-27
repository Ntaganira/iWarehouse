package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.config.NumberFormats;
import com.ntaganira.heritier.iWarehouse.dto.PettyCashFundDto;
import com.ntaganira.heritier.iWarehouse.entity.*;
import com.ntaganira.heritier.iWarehouse.enums.*;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.*;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
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
 * - File      : PettyCashService.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Petty cash funds (CMP 07, BP 5.1, F-13 D): one per site, opened by Finance (PERM_MANAGE_PETTY_CASH) with its
 *               custodian and float (at most the Settings' maximum). What a fund holds is its site's lines on the Petty Cash
 *               account, never a second balance. Treasury tops it up from the vault or the bank (PERM_REPLENISH_PETTY_CASH,
 *               never the custodian), back to its float at most (PettyCash.topUpToFloat). The custodian counts it every
 *               working day (DAILY) and Finance by surprise (SURPRISE, PERM_AUDIT_PETTY_CASH, never the custodian); a
 *               difference needs a note and posts at once to Cash Over and Short, and ALERT_EXPENSE holders are told.
 *               checkFunds (scheduled) tells them once when a fund falls below the Settings' share of its float and when a
 *               month's surprise count is due. Closing returns its cash to the vault or the bank, with a reason.
 *               Each change locks the fund first, so what it holds is read once.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class PettyCashService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MM/yyyy");
    static final String LOW_PREFIX = "PETTY_CASH_LOW:";
    static final String AUDIT_PREFIX = "PETTY_CASH_AUDIT:";

    /** A fund with what it holds now, whether it is to be topped up, its latest counts and the working days not counted. */
    public record FundRow(PettyCashFund fund, BigDecimal balance, BigDecimal trigger, boolean low, PettyCashCount lastDaily,
                          PettyCashCount lastSurprise, boolean auditedThisMonth, boolean auditDue, List<LocalDate> notCounted) {
    }

    private final PettyCashFundRepository fundRepo;
    private final PettyCashReplenishmentRepository replenishmentRepo;
    private final PettyCashCountRepository countRepo;
    private final PettyCashVoucherRepository voucherRepo;
    private final UserRepository userRepo;
    private final AlertStateRepository alertRepo;
    private final StockService stockService;
    private final PostingService postingService;
    private final JournalService journalService;
    private final DocumentNumberService numbers;
    private final PeriodLock periods;
    private final SettingService settings;
    private final Notifier notifier;
    private final NumberFormats num;
    private final Clock clock;

    public PettyCashService(PettyCashFundRepository fundRepo, PettyCashReplenishmentRepository replenishmentRepo, PettyCashCountRepository countRepo,
                            PettyCashVoucherRepository voucherRepo, UserRepository userRepo, AlertStateRepository alertRepo, StockService stockService,
                            PostingService postingService, JournalService journalService, DocumentNumberService numbers, PeriodLock periods,
                            SettingService settings, Notifier notifier, NumberFormats num, Clock clock) {
        this.fundRepo = fundRepo;
        this.replenishmentRepo = replenishmentRepo;
        this.countRepo = countRepo;
        this.voucherRepo = voucherRepo;
        this.userRepo = userRepo;
        this.alertRepo = alertRepo;
        this.stockService = stockService;
        this.postingService = postingService;
        this.journalService = journalService;
        this.numbers = numbers;
        this.periods = periods;
        this.settings = settings;
        this.notifier = notifier;
        this.num = num;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- reading

    /** Every fund, those in use first, with what each holds. */
    public List<FundRow> funds() {
        Map<UUID, BigDecimal> balances = balances();
        return fundRepo.findAllByOrderByStatusAscCodeAsc().stream().map(f -> row(f, balances)).toList();
    }

    public List<FundRow> activeFunds() {
        Map<UUID, BigDecimal> balances = balances();
        return fundRepo.findByStatusOrderByCodeAsc(PettyCashFundStatus.ACTIVE).stream().map(f -> row(f, balances)).toList();
    }

    public PettyCashFund findDetailed(UUID id) {
        return fundRepo.findDetailedById(id).orElseThrow(() -> new NotFoundException("PettyCashFund", id));
    }

    public FundRow row(PettyCashFund fund) {
        return row(fund, balances());
    }

    /** What a fund holds: its site's lines on the Petty Cash account. */
    public BigDecimal balance(PettyCashFund fund) {
        return balances().getOrDefault(fund.getLocation().getId(), BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }

    public Page<PettyCashReplenishment> replenishments(UUID fundId, int page, int size) {
        return replenishmentRepo.findByFund_IdOrderByMadeAtDesc(fundId, PageRequest.of(page, size));
    }

    public Page<PettyCashCount> counts(UUID fundId, int page, int size) {
        return countRepo.findByFund_IdOrderByCountedAtDesc(fundId, PageRequest.of(page, size));
    }

    public Page<PettyCashVoucher> vouchers(UUID fundId, int page, int size) {
        return voucherRepo.findByFund_IdOrderByVoucherDateDescNumberDesc(fundId, PageRequest.of(page, size));
    }

    public long waitingVouchers(UUID fundId) {
        return voucherRepo.countByFund_IdAndStatus(fundId, PettyCashVoucherStatus.SUBMITTED);
    }

    /** Funds in use below the Settings' share of their float (the dashboard). */
    public long lowCount() {
        return activeFunds().stream().filter(FundRow::low).count();
    }

    /** Sites without a fund in use: where a new one may be opened. */
    public List<Location> freeSites() {
        Set<UUID> taken = new HashSet<>();
        fundRepo.findByStatusOrderByCodeAsc(PettyCashFundStatus.ACTIVE).forEach(f -> taken.add(f.getLocation().getId()));
        return stockService.locationsById().values().stream()
                .filter(l -> l.isEnabled() && l.getType() == LocationType.SITE && !taken.contains(l.getId()))
                .sorted(Comparator.comparing(Location::getCode)).toList();
    }

    public List<User> users() {
        return userRepo.findAll(Sort.by("fullName")).stream().filter(User::isEnabled).toList();
    }

    public boolean isCustodian(PettyCashFund fund) {
        return AppUserPrincipal.current().map(u -> u.getId().equals(fund.getCustodianUserId())).orElse(false);
    }

    public BigDecimal maxFloat() {
        return settings.getDecimal(SettingKey.PETTY_CASH_MAX_FLOAT);
    }

    public BigDecimal maxPayment() {
        return settings.getDecimal(SettingKey.PETTY_CASH_MAX_PAYMENT);
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    public PettyCashFundDto formOf(PettyCashFund fund) {
        PettyCashFundDto dto = new PettyCashFundDto();
        dto.setLocationId(fund.getLocation().getId());
        dto.setCustodianUserId(fund.getCustodianUserId());
        dto.setImprest(fund.getImprest());
        return dto;
    }

    // ---------------------------------------------------------------- funds

    /** Opens a site's fund with its custodian and float (CMP 07: at most the Settings' maximum). It holds nothing until topped up. */
    @Transactional
    public PettyCashFund open(PettyCashFundDto dto) {
        Location site = stockService.locationsById().get(dto.getLocationId());
        if (site == null || site.getType() != LocationType.SITE || !site.isEnabled()) {
            throw BusinessException.onField("locationId", "pettyCash.site.required");
        }
        if (fundRepo.existsByLocation_IdAndStatus(site.getId(), PettyCashFundStatus.ACTIVE)) {
            throw BusinessException.onField("locationId", "pettyCash.site.taken", site.getCode());
        }
        PettyCashFund fund = new PettyCashFund();
        String code = "PC-" + site.getCode();
        for (int n = 2; fundRepo.existsByCode(code); n++) {
            code = "PC-" + site.getCode() + "-" + n;                 // a closed fund of the site kept its code
        }
        fund.setCode(code);
        fund.setLocation(site);
        apply(fund, dto);
        fund.setOpenedBy(AppUserPrincipal.currentUsername());
        fund.setOpenedAt(LocalDateTime.now(clock));
        return fundRepo.save(fund);
    }

    /** Changes a fund's custodian or float; the float never below what it holds. */
    @Transactional
    public PettyCashFund update(UUID id, PettyCashFundDto dto) {
        PettyCashFund fund = lock(id);
        requireActive(fund);
        if (dto.getImprest() != null && dto.getImprest().compareTo(balance(fund)) < 0) {
            throw BusinessException.onField("imprest", "pettyCash.imprest.belowBalance", num.money(balance(fund)));
        }
        apply(fund, dto);
        return fund;
    }

    /** Tops a fund up from the vault or the bank (CMP 07), never by its custodian, and never above its float. */
    @Transactional
    public PettyCashReplenishment topUp(UUID id, BigDecimal amount, PettyCashSource source, String reference, LocalDate date) {
        PettyCashFund fund = lock(id);
        requireActive(fund);
        if (isCustodian(fund)) {
            throw BusinessException.of("pettyCash.topUp.custodian", fund.getCode());
        }
        BigDecimal value = positive(amount, "amount", "pettyCash.amount.required");
        BigDecimal balance = balance(fund);
        BigDecimal room = PettyCash.topUpToFloat(balance, fund.getImprest());
        if (value.compareTo(room) > 0) {
            throw BusinessException.onField("amount", "pettyCash.topUp.aboveFloat", num.money(room), num.money(fund.getImprest()));
        }
        PettyCashReplenishment r = movement(fund, PettyCashReplenishmentKind.TOP_UP, value, source, reference, date, balance);
        AlertOnce.clear(alertRepo, LOW_PREFIX + fund.getId(), LocalDateTime.now(clock));
        return r;
    }

    /**
     * Counts a fund's cash (CMP 07): by its custodian, the day's count; by Finance (PERM_AUDIT_PETTY_CASH), a surprise count. A
     * difference needs a note, posts to Cash Over and Short at once and is told to ALERT_EXPENSE holders.
     */
    @Transactional
    public PettyCashCount count(UUID id, BigDecimal counted, String note) {
        PettyCashFund fund = lock(id);
        requireActive(fund);
        PettyCashCountKind kind;
        if (isCustodian(fund)) {
            kind = PettyCashCountKind.DAILY;
        } else if (AppUserPrincipal.currentHas("PERM_AUDIT_PETTY_CASH")) {
            kind = PettyCashCountKind.SURPRISE;
        } else {
            throw BusinessException.of("pettyCash.count.notCustodian", fund.getCode(), fund.getCustodianName());
        }
        if (counted == null || counted.signum() < 0) {
            throw BusinessException.onField("counted", "pettyCash.count.required");
        }
        LocalDate today = today();
        BigDecimal expected = balance(fund);
        BigDecimal cash = counted.setScale(2, RoundingMode.HALF_UP);
        BigDecimal difference = cash.subtract(expected);
        String clean = PartyRules.clean(note);
        if (difference.signum() != 0 && clean == null) {
            throw BusinessException.onField("note", "pettyCash.count.noteRequired", num.money(difference));
        }
        if (clean != null && clean.length() > 500) {
            throw BusinessException.onField("note", "expense.note.size");
        }
        Optional<AppUserPrincipal> me = AppUserPrincipal.current();
        PettyCashCount c = new PettyCashCount();
        c.setFund(fund);
        c.setKind(kind);
        c.setCountedOn(today);
        c.setCountedBy(me.map(AppUserPrincipal::getUsername).orElse("system"));
        c.setCountedById(me.map(AppUserPrincipal::getId).orElse(null));
        c.setCountedAt(LocalDateTime.now(clock));
        c.setExpected(expected);
        c.setCounted(cash);
        c.setDifference(difference);
        c.setNote(clean);
        if (difference.signum() != 0) {
            periods.requireOpen(today);
            c.setJournalId(postingService.pettyCashCount(c).getId());
            notifier.holders("ALERT_EXPENSE", c.getCountedById(), NotificationKind.EXPENSE, "notify.pettyCash.difference", "notify.pettyCash.differenceText",
                    link(fund), fund.getCode(), num.money(expected), num.money(cash), c.getCountedBy(), clean);
        }
        PettyCashCount saved = countRepo.save(c);
        if (kind == PettyCashCountKind.SURPRISE) {
            AlertOnce.clear(alertRepo, AUDIT_PREFIX + fund.getId() + ":" + today.format(MONTH), LocalDateTime.now(clock));
        }
        refreshLow(fund, cash);
        return saved;
    }

    /** Closes a fund with a reason: what it holds goes back to the vault or the bank. No voucher of it may be waiting. */
    @Transactional
    public PettyCashFund close(UUID id, String reason, PettyCashSource source, String reference) {
        PettyCashFund fund = lock(id);
        requireActive(fund);
        String clean = PartyRules.clean(reason);
        if (clean == null || clean.length() > 500) {
            throw BusinessException.onField("reason", "expense.reason.required");
        }
        if (voucherRepo.countByFund_IdAndStatus(fund.getId(), PettyCashVoucherStatus.SUBMITTED) > 0
                || voucherRepo.countByFund_IdAndStatus(fund.getId(), PettyCashVoucherStatus.DRAFT) > 0) {
            throw BusinessException.of("pettyCash.close.vouchersOpen", fund.getCode());
        }
        BigDecimal balance = balance(fund);
        if (balance.signum() > 0) {
            PettyCashReplenishment back = movement(fund, PettyCashReplenishmentKind.RETURN, balance, source, reference, today(), balance);
            fund.setCloseJournalId(back.getJournalId());
        }
        fund.setClosedBy(AppUserPrincipal.currentUsername());
        fund.setClosedAt(LocalDateTime.now(clock));
        fund.setCloseReason(clean);
        fund.setStatus(PettyCashFundStatus.CLOSED);
        AlertOnce.clear(alertRepo, LOW_PREFIX + fund.getId(), LocalDateTime.now(clock));
        return fund;
    }

    /**
     * Raises or clears a fund's low-cash alert after its cash moved (CMP 07: topped up below 25 % of its float): ALERT_EXPENSE
     * holders and the custodian are told once.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void refreshLow(PettyCashFund fund, BigDecimal balance) {
        BigDecimal percent = settings.getDecimal(SettingKey.PETTY_CASH_REPLENISH_PERCENT);
        LocalDateTime now = LocalDateTime.now(clock);
        if (PettyCash.belowTrigger(balance, fund.getImprest(), percent)) {
            if (AlertOnce.raise(alertRepo, LOW_PREFIX + fund.getId(), now)) {
                String[] args = {fund.getCode(), fund.getLocation().getCode(), num.money(balance), num.money(PettyCash.trigger(fund.getImprest(), percent))};
                notifier.holders("ALERT_EXPENSE", null, NotificationKind.EXPENSE, "notify.pettyCash.low", "notify.pettyCash.lowText", link(fund), args);
                notifier.holders("REPLENISH_PETTY_CASH", null, NotificationKind.EXPENSE, "notify.pettyCash.low", "notify.pettyCash.lowText", link(fund), args);
            }
        } else {
            AlertOnce.clear(alertRepo, LOW_PREFIX + fund.getId(), now);
        }
    }

    /** Locks a fund in use for a payment from it (a voucher, an advance) and gives what it holds. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public PettyCashFund lockForPayment(UUID id) {
        PettyCashFund fund = lock(id);
        requireActive(fund);
        return fund;
    }

    /** Funds low on cash and surprise counts due this month: told once each (CMP 07). */
    @Scheduled(fixedDelayString = "${app.alerts.petty-cash-every:PT6H}", initialDelayString = "${app.alerts.first-check:PT1M}")
    @Transactional
    public int checkFunds() {
        LocalDate today = today();
        int auditDay = settings.getInt(SettingKey.PETTY_CASH_AUDIT_DAY);
        LocalDateTime now = LocalDateTime.now(clock);
        int told = 0;
        Map<UUID, BigDecimal> balances = balances();
        for (PettyCashFund fund : fundRepo.findByStatusOrderByCodeAsc(PettyCashFundStatus.ACTIVE)) {
            FundRow row = row(fund, balances);
            if (row.low() && AlertOnce.raise(alertRepo, LOW_PREFIX + fund.getId(), now)) {
                String[] args = {fund.getCode(), fund.getLocation().getCode(), num.money(row.balance()), num.money(row.trigger())};
                notifier.holders("ALERT_EXPENSE", null, NotificationKind.EXPENSE, "notify.pettyCash.low", "notify.pettyCash.lowText", link(fund), args);
                notifier.holders("REPLENISH_PETTY_CASH", null, NotificationKind.EXPENSE, "notify.pettyCash.low", "notify.pettyCash.lowText", link(fund), args);
                told++;
            }
            if (PettyCash.auditDue(row.auditedThisMonth(), today, auditDay)
                    && AlertOnce.raise(alertRepo, AUDIT_PREFIX + fund.getId() + ":" + today.format(MONTH), now)) {
                notifier.holders("AUDIT_PETTY_CASH", null, NotificationKind.EXPENSE, "notify.pettyCash.audit", "notify.pettyCash.auditText", link(fund),
                        fund.getCode(), fund.getLocation().getCode(), today.format(MONTH));
                told++;
            }
        }
        return told;
    }

    // ---------------------------------------------------------------- helpers

    private PettyCashReplenishment movement(PettyCashFund fund, PettyCashReplenishmentKind kind, BigDecimal amount, PettyCashSource source,
                                            String reference, LocalDate date, BigDecimal balance) {
        if (source == null) {
            throw BusinessException.onField("source", "pettyCash.source.required");
        }
        String ref = StringUtils.hasText(reference) ? reference.trim() : null;
        if (source.needsReference() && ref == null) {
            throw BusinessException.onField("reference", "pettyCash.reference.required");
        }
        if (ref != null && ref.length() > 60) {
            throw BusinessException.onField("reference", "pettyCash.reference.size");
        }
        LocalDate day = date == null ? today() : date;
        if (day.isAfter(today())) {
            throw BusinessException.onField("txnDate", "pettyCash.date.future");
        }
        periods.requireOpen(day, "txnDate");
        Optional<AppUserPrincipal> me = AppUserPrincipal.current();
        PettyCashReplenishment r = new PettyCashReplenishment();
        r.setNumber(numbers.next(DocumentType.PETTY_CASH_REPLENISHMENT));
        r.setFund(fund);
        r.setKind(kind);
        r.setSource(source);
        r.setReference(ref);
        r.setAmount(amount);
        r.setTxnDate(day);
        r.setBalanceBefore(balance);
        r.setMadeBy(me.map(AppUserPrincipal::getUsername).orElse("system"));
        r.setMadeById(me.map(AppUserPrincipal::getId).orElse(null));
        r.setMadeAt(LocalDateTime.now(clock));
        r.setJournalId(postingService.pettyCashReplenishment(r).getId());
        return replenishmentRepo.save(r);
    }

    private void apply(PettyCashFund fund, PettyCashFundDto dto) {
        User custodian = userRepo.findById(dto.getCustodianUserId()).filter(User::isEnabled)
                .orElseThrow(() -> BusinessException.onField("custodianUserId", "pettyCash.custodian.required"));
        BigDecimal imprest = positive(dto.getImprest(), "imprest", "pettyCash.imprest.required");
        BigDecimal max = maxFloat();
        if (max != null && max.signum() > 0 && imprest.compareTo(max) > 0) {
            throw BusinessException.onField("imprest", "pettyCash.imprest.tooHigh", num.money(max));
        }
        fund.setCustodianUserId(custodian.getId());
        fund.setCustodianName(custodian.getUsername());
        fund.setImprest(imprest);
    }

    private FundRow row(PettyCashFund fund, Map<UUID, BigDecimal> balances) {
        BigDecimal balance = balances.getOrDefault(fund.getLocation().getId(), BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        BigDecimal percent = settings.getDecimal(SettingKey.PETTY_CASH_REPLENISH_PERCENT);
        LocalDate today = today();
        LocalDate monthStart = today.withDayOfMonth(1);
        boolean audited = countRepo.existsByFund_IdAndKindAndCountedOnBetween(fund.getId(), PettyCashCountKind.SURPRISE, monthStart, today);
        List<LocalDate> notCounted = List.of();
        if (fund.isActive()) {
            LocalDate from = fund.getOpenedAt().toLocalDate().isAfter(monthStart) ? fund.getOpenedAt().toLocalDate() : monthStart;
            LocalDate to = today.minusDays(1);                          // today is counted at the close of the day
            if (!from.isAfter(to)) {
                notCounted = PettyCash.daysNotCounted(countRepo.countedDays(fund.getId(), PettyCashCountKind.DAILY, from, to), from, to);
            }
        }
        return new FundRow(fund, balance, PettyCash.trigger(fund.getImprest(), percent), fund.isActive() && PettyCash.belowTrigger(balance, fund.getImprest(), percent),
                countRepo.findFirstByFund_IdAndKindOrderByCountedAtDesc(fund.getId(), PettyCashCountKind.DAILY).orElse(null),
                countRepo.findFirstByFund_IdAndKindOrderByCountedAtDesc(fund.getId(), PettyCashCountKind.SURPRISE).orElse(null),
                audited, fund.isActive() && PettyCash.auditDue(audited, today, settings.getInt(SettingKey.PETTY_CASH_AUDIT_DAY)), notCounted);
    }

    private Map<UUID, BigDecimal> balances() {
        return journalService.locationBalances(AccountKey.PETTY_CASH);
    }

    private PettyCashFund lock(UUID id) {
        fundRepo.lockById(id).orElseThrow(() -> new NotFoundException("PettyCashFund", id));
        return findDetailed(id);
    }

    private static void requireActive(PettyCashFund fund) {
        if (!fund.isActive()) {
            throw BusinessException.of("pettyCash.closed", fund.getCode());
        }
    }

    private static BigDecimal positive(BigDecimal value, String field, String key) {
        if (value == null || value.signum() <= 0) {
            throw BusinessException.onField(field, key);
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    static String link(PettyCashFund fund) {
        return "/petty-cash/funds/" + fund.getId();
    }
}
