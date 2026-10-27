package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.InventoryKpiReview;
import com.ntaganira.heritier.iWarehouse.entity.StockCount;
import com.ntaganira.heritier.iWarehouse.entity.StockDayReview;
import com.ntaganira.heritier.iWarehouse.entity.StockReconciliation;
import com.ntaganira.heritier.iWarehouse.entity.StockVariance;
import com.ntaganira.heritier.iWarehouse.enums.*;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.repository.*;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : InventoryKpiService.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The inventory KPIs (Inventory Policy 19, Annex D; M.07, K.17, L.14, T.12) of any period, measured from
 *               what the system holds: the counts closed (units matched against expected), the units lost at cost, the
 *               damage register's value, the Inventory account at the start and the end (average value), the Cost of
 *               Goods Sold account (turnover), signed delivery note copies, the daily reviews and monthly stock
 *               reconciliations signed off on time, the variances resolved by their due day, and the days from payment to
 *               the last hand-over or release (reconciliations are due from the first stock movement on). Targets from
 *               Settings (InventoryKpis applies them). When accuracy has been
 *               below target for the Settings' consecutive months, ALERT_KPI holders are told once and the Head of
 *               Inventory or the Managing Director records the formal review (PERM_REVIEW_INVENTORY_KPI).
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class InventoryKpiService {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MM/yyyy");
    static final String ALERT_PREFIX = "KPI_ACCURACY:";

    private final StockCountRepository countRepo;
    private final StockMovementRepository movementRepo;
    private final JournalLineRepository journalLineRepo;
    private final DamageRegisterService damageRegister;
    private final DeliveryNoteService deliveryNoteService;
    private final StockDayReviewRepository dayRepo;
    private final StockReconciliationRepository reconRepo;
    private final StockVarianceRepository varianceRepo;
    private final SalesDeliveryRepository deliveryRepo;
    private final DeliveryNoteRepository noteRepo;
    private final InventoryKpiReviewRepository reviewRepo;
    private final AlertStateRepository alertRepo;
    private final SettingService settings;
    private final Notifier notifier;
    private final Clock clock;

    public InventoryKpiService(StockCountRepository countRepo, StockMovementRepository movementRepo, JournalLineRepository journalLineRepo,
                               DamageRegisterService damageRegister, DeliveryNoteService deliveryNoteService, StockDayReviewRepository dayRepo,
                               StockReconciliationRepository reconRepo, StockVarianceRepository varianceRepo, SalesDeliveryRepository deliveryRepo,
                               DeliveryNoteRepository noteRepo, InventoryKpiReviewRepository reviewRepo, AlertStateRepository alertRepo,
                               SettingService settings, Notifier notifier, Clock clock) {
        this.countRepo = countRepo;
        this.movementRepo = movementRepo;
        this.journalLineRepo = journalLineRepo;
        this.damageRegister = damageRegister;
        this.deliveryNoteService = deliveryNoteService;
        this.dayRepo = dayRepo;
        this.reconRepo = reconRepo;
        this.varianceRepo = varianceRepo;
        this.deliveryRepo = deliveryRepo;
        this.noteRepo = noteRepo;
        this.reviewRepo = reviewRepo;
        this.alertRepo = alertRepo;
        this.settings = settings;
        this.notifier = notifier;
        this.clock = clock;
    }

    /** A period's KPIs and what they were measured from. */
    public record Measure(LocalDate from, LocalDate to, InventoryKpis.Inputs inputs, List<InventoryKpis.Kpi> kpis) {

        public InventoryKpis.Kpi get(InventoryKpis.Code code) {
            return InventoryKpis.find(kpis, code);
        }

        public YearMonth getMonth() {
            return YearMonth.from(from);
        }

        public long getMissed() {
            return kpis.stream().filter(InventoryKpis.Kpi::isMissed).count();
        }
    }

    /** Accuracy below target for the needed months, ending with {@code month}; the formal review once recorded. */
    public record Breach(YearMonth month, int monthsBelow, int needed, BigDecimal accuracy, BigDecimal target, InventoryKpiReview review) {

        public boolean isReviewed() {
            return review != null;
        }
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    public InventoryKpis.Targets targets() {
        return new InventoryKpis.Targets(settings.getDecimal(SettingKey.KPI_ACCURACY_TARGET), settings.getDecimal(SettingKey.KPI_LOSS_MAX_PERCENT),
                settings.getDecimal(SettingKey.KPI_DAMAGE_MAX_PERCENT), turnoverTarget(), settings.getDecimal(SettingKey.KPI_DELIVERY_ACCURACY_TARGET),
                settings.getDecimal(SettingKey.KPI_RECONCILIATION_TARGET), settings.getDecimal(SettingKey.KPI_VARIANCE_RESOLUTION_TARGET),
                settings.getDecimal(SettingKey.KPI_ORDER_DAYS_TARGET));
    }

    /** Set by management (IMP 19); blank means none. */
    private BigDecimal turnoverTarget() {
        String value = settings.get(SettingKey.KPI_TURNOVER_TARGET);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The KPIs of a period, measured up to today when it runs. */
    public Measure measure(LocalDate from, LocalDate to) {
        LocalDate today = today();
        LocalDate end = to.isAfter(today) ? today : to;
        LocalDateTime start = from.atStartOfDay();
        LocalDateTime stop = end.plusDays(1).atStartOfDay().minusNanos(1);

        int[] counted = counted(start, stop);
        int matched = counted[0], expected = counted[1];
        List<Object[]> lost = movementRepo.leftForBetween(start, stop, List.of(StockStatus.LOST));
        BigDecimal lostValue = lost.isEmpty() || lost.get(0)[1] == null ? BigDecimal.ZERO : ((BigDecimal) lost.get(0)[1]).setScale(2, RoundingMode.HALF_UP);
        BigDecimal damaged = damageRegister.value(from, end);
        BigDecimal opening = journalLineRepo.balanceAt(AccountKey.INVENTORY, from.minusDays(1));
        BigDecimal closing = journalLineRepo.balanceAt(AccountKey.INVENTORY, end);
        BigDecimal cogs = journalLineRepo.movementBetween(AccountKey.COGS, from, end);
        int days = (int) Math.max(1, java.time.temporal.ChronoUnit.DAYS.between(from, end) + 1);
        DeliveryNoteService.Accuracy delivery = deliveryNoteService.accuracy(from, end);

        int[] recon = reconciliations(from, end, today);
        int[] variances = variances(from, end, today);
        BigDecimal[] orders = orders(start, stop);
        InventoryKpis.Inputs inputs = new InventoryKpis.Inputs(matched, expected, lostValue, damaged, opening, closing, cogs, days,
                delivery.copiesBack(), delivery.withIssue(), recon[0], recon[1], variances[0], variances[1], orders[0].intValue(), orders[1]);
        return new Measure(from, to, inputs, InventoryKpis.of(inputs, targets()));
    }

    /** The last {@code n} months up to {@code last}, oldest first. */
    public List<Measure> months(YearMonth last, int n) {
        List<Measure> list = new ArrayList<>();
        for (int i = n - 1; i >= 0; i--) {
            YearMonth m = last.minusMonths(i);
            list.add(measure(m.atDay(1), m.atEndOfMonth()));
        }
        return list;
    }

    /** Reconciliations due (working days whose report was due, months whose reconciliation was due) and done on time. */
    private int[] reconciliations(LocalDate from, LocalDate end, LocalDate today) {
        int due = 0, onTime = 0;
        LocalDateTime first = movementRepo.firstMovementAt();
        if (first == null) {
            return new int[]{0, 0};
        }
        LocalDate live = first.toLocalDate();
        Map<LocalDate, StockDayReview> reviews = new HashMap<>();
        dayRepo.findByDayBetween(from, end).forEach(r -> reviews.put(r.getDay(), r));
        for (LocalDate d = from; !d.isAfter(end); d = d.plusDays(1)) {
            LocalDate dueOn = InventoryKpis.dayReviewDue(d);
            if (!WorkingDays.isWorking(d) || !dueOn.isBefore(today) || d.isBefore(live)) {
                continue;
            }
            due++;
            StockDayReview r = reviews.get(d);
            if (r != null && InventoryKpis.onTime(r.getReviewedAt().toLocalDate(), dueOn)) {
                onTime++;
            }
        }
        int workingDay = settings.getInt(SettingKey.RECONCILIATION_DUE_WORKING_DAY);
        for (YearMonth m = YearMonth.from(from); !m.isAfter(YearMonth.from(end)); m = m.plusMonths(1)) {
            LocalDate dueOn = InventoryKpis.monthReviewDue(m, workingDay);
            if (m.atEndOfMonth().isAfter(end) || !dueOn.isBefore(today) || m.atEndOfMonth().isBefore(live)) {
                continue;
            }
            due++;
            Optional<StockReconciliation> r = reconRepo.findByPeriodStart(m.atDay(1));
            if (r.isPresent() && InventoryKpis.onTime(r.get().getReviewedAt().toLocalDate(), dueOn)) {
                onTime++;
            }
        }
        return new int[]{due, onTime};
    }

    /** Variances due in the period (by today) and those resolved by their due day. */
    private int[] variances(LocalDate from, LocalDate end, LocalDate today) {
        int due = 0, onTime = 0;
        for (StockVariance v : varianceRepo.findByDueOnBetween(from, end)) {
            boolean resolved = v.getResolvedAt() != null;
            if (!resolved && !v.getDueOn().isBefore(today)) {
                continue;
            }
            due++;
            if (resolved && InventoryKpis.onTime(v.getResolvedAt().toLocalDate(), v.getDueOn())) {
                onTime++;
            }
        }
        return new int[]{due, onTime};
    }

    /** Invoices whose last hand-over or release fell in the period: how many, and their days from payment added up. */
    private BigDecimal[] orders(LocalDateTime start, LocalDateTime stop) {
        Map<UUID, LocalDateTime[]> byInvoice = new HashMap<>();
        for (List<Object[]> rows : List.of(deliveryRepo.lastHandOverSince(start), noteRepo.lastReleaseSince(start))) {
            for (Object[] r : rows) {
                UUID id = (UUID) r[0];
                LocalDateTime paid = (LocalDateTime) r[1];
                LocalDateTime last = (LocalDateTime) r[2];
                LocalDateTime[] known = byInvoice.computeIfAbsent(id, k -> new LocalDateTime[]{paid, last});
                if (last != null && (known[1] == null || last.isAfter(known[1]))) {
                    known[1] = last;
                }
            }
        }
        long count = 0;
        BigDecimal total = BigDecimal.ZERO;
        for (LocalDateTime[] v : byInvoice.values()) {
            if (v[0] == null || v[1] == null || v[1].isAfter(stop)) {
                continue;
            }
            long minutes = Math.max(0, Duration.between(v[0], v[1]).toMinutes());
            total = total.add(BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(1440), 4, RoundingMode.HALF_UP));
            count++;
        }
        return new BigDecimal[]{BigDecimal.valueOf(count), total};
    }

    // ---------------------------------------------------------------- the formal review (IMP 19)

    /** The latest ended month closing a run of months below target long enough for a review, if any. */
    public Optional<Breach> breach() {
        int needed = Math.max(1, settings.getInt(SettingKey.KPI_ACCURACY_MONTHS));
        BigDecimal target = settings.getDecimal(SettingKey.KPI_ACCURACY_TARGET);
        YearMonth last = YearMonth.from(today()).minusMonths(1);
        List<BigDecimal> accuracies = new ArrayList<>();
        for (int i = Math.max(needed, 12) - 1; i >= 0; i--) {
            YearMonth m = last.minusMonths(i);
            accuracies.add(accuracy(m));
        }
        int run = InventoryKpis.monthsBelow(accuracies, target);
        if (run < needed) {
            return Optional.empty();
        }
        return Optional.of(new Breach(last, run, needed, accuracies.get(accuracies.size() - 1), target,
                reviewRepo.findByPeriodStart(last.atDay(1)).orElse(null)));
    }

    private BigDecimal accuracy(YearMonth m) {
        int[] counted = counted(m.atDay(1).atStartOfDay(), m.atEndOfMonth().plusDays(1).atStartOfDay().minusNanos(1));
        return InventoryKpis.percent(counted[0], counted[1]);
    }

    /** Units matched and expected in the counts closed between two moments, as each count kept them when it closed. */
    private int[] counted(LocalDateTime from, LocalDateTime to) {
        int matched = 0, expected = 0;
        for (StockCount c : countRepo.findByStatusAndClosedAtBetweenOrderByClosedAtAsc(StockCountStatus.CLOSED, from, to)) {
            matched += c.getMatchedUnits() == null ? 0 : c.getMatchedUnits();
            expected += c.getExpectedUnits() == null ? 0 : c.getExpectedUnits();
        }
        return new int[]{matched, expected};
    }

    /** Tells ALERT_KPI holders once when accuracy has stayed below target long enough, until the review is recorded. */
    @Scheduled(fixedDelayString = "${app.alerts.kpi-every:PT6H}", initialDelayString = "${app.alerts.first-check:PT1M}")
    @Transactional
    public boolean checkAccuracy() {
        Optional<Breach> breach = breach();
        if (breach.isEmpty() || breach.get().isReviewed()) {
            return false;
        }
        Breach b = breach.get();
        if (!AlertOnce.raise(alertRepo, ALERT_PREFIX + b.month(), LocalDateTime.now(clock))) {
            return false;
        }
        notifier.holders("ALERT_KPI", null, NotificationKind.KPI, "notify.kpi.accuracy", "notify.kpi.accuracyText", "/reports/kpis",
                b.month().format(MONTH), String.valueOf(b.monthsBelow()), b.accuracy() == null ? "-" : b.accuracy().toPlainString(),
                b.target().stripTrailingZeros().toPlainString());
        return true;
    }

    /** Records the formal review of the months below target (the Head of Inventory or the Managing Director). */
    @Transactional
    public InventoryKpiReview review(YearMonth month, String note) {
        Breach b = breach().filter(x -> x.month().equals(month))
                .orElseThrow(() -> BusinessException.of("kpi.review.notDue", month.format(MONTH)));
        if (b.isReviewed()) {
            throw BusinessException.of("kpi.review.done", month.format(MONTH));
        }
        String clean = PartyRules.clean(note);
        if (clean == null || clean.length() > 1000) {
            throw BusinessException.onField("note", "kpi.review.note");
        }
        Optional<AppUserPrincipal> user = AppUserPrincipal.current();
        InventoryKpiReview review = new InventoryKpiReview();
        review.setPeriodStart(month.atDay(1));
        review.setMonthsBelow(b.monthsBelow());
        review.setAccuracy(b.accuracy());
        review.setTarget(b.target());
        review.setNote(clean);
        review.setReviewedBy(user.map(AppUserPrincipal::getUsername).orElse("system"));
        review.setReviewedById(user.map(AppUserPrincipal::getId).orElse(null));
        review.setReviewedAt(LocalDateTime.now(clock));
        reviewRepo.save(review);
        AlertOnce.clear(alertRepo, ALERT_PREFIX + month, LocalDateTime.now(clock));
        return review;
    }

    public Page<InventoryKpiReview> reviews(int page, int size) {
        return reviewRepo.findAllByOrderByPeriodStartDesc(PageRequest.of(page, size));
    }
}
