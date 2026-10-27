package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.*;
import com.ntaganira.heritier.iWarehouse.enums.*;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.repository.*;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : InventoryReportService.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The inventory reports of the Inventory Policy (sections 14 and 18; DIR-011, PF-05; M.01, M.02, M.04-M.06):
 *               - the daily inventory report: the Inventory account at the day's start and end, the movements by type,
 *                 each site's units and m² at the end with what came in and went out, the deliveries made (delivery notes
 *                 released, goods sold out of stock), the transfers executed and the exceptions (damage, adjustments,
 *                 breakage and losses, counts closed, variances opened); signed off by the Warehouse Manager (StockReviewService) and
 *                 countersigned by the Internal Controller (PERM_COUNTERSIGN_DAILY_STOCK, not who signed it off);
 *               - the weekly, monthly, quarterly and annual report: the position by site at the end and the Inventory
 *                 account at the start and the end, the movements, damage and losses, the counts closed with their
 *                 accuracy (external auditors who signed the annual counts), the variances, slow-moving and obsolete glass
 *                 now, the period's KPIs against the period before, and the reconciliation status (days signed off and
 *                 countersigned, months reconciled).
 *               A site's figures follow the unit's own place: glass in transit and on vehicles is shown apart. Reads
 *               documents, movements and journals only.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class InventoryReportService {

    static final String TRANSIT = "TRANSIT";
    static final String VEHICLES = "VEHICLES";
    static final List<MovementType> SOLD = List.of(MovementType.SALE);
    static final List<MovementType> TRANSFERS = List.of(MovementType.TRANSFER, MovementType.DISPATCH, MovementType.ARRIVAL, MovementType.LOAD,
            MovementType.UNLOAD);
    static final List<MovementType> EXCEPTIONS = List.of(MovementType.ADJUSTMENT, MovementType.LOSS, MovementType.BREAKAGE);

    private final StockMovementRepository movementRepo;
    private final StockReviewService reviewService;
    private final StockService stockService;
    private final JournalLineRepository journalLineRepo;
    private final DeliveryNoteRepository noteRepo;
    private final SalesDeliveryRepository deliveryRepo;
    private final DamageReportRepository damageRepo;
    private final DamageRegisterService damageRegister;
    private final StockCountRepository countRepo;
    private final StockCountParticipantRepository participantRepo;
    private final StockVarianceRepository varianceRepo;
    private final StockDayReviewRepository dayRepo;
    private final StockReconciliationRepository reconRepo;
    private final StockReportService stockReportService;
    private final InventoryKpiService kpiService;
    private final SettingService settings;
    private final java.time.Clock clock;

    public InventoryReportService(StockMovementRepository movementRepo, StockReviewService reviewService, StockService stockService,
                                  JournalLineRepository journalLineRepo, DeliveryNoteRepository noteRepo, SalesDeliveryRepository deliveryRepo,
                                  DamageReportRepository damageRepo, DamageRegisterService damageRegister, StockCountRepository countRepo,
                                  StockCountParticipantRepository participantRepo, StockVarianceRepository varianceRepo,
                                  StockDayReviewRepository dayRepo, StockReconciliationRepository reconRepo, StockReportService stockReportService,
                                  InventoryKpiService kpiService, SettingService settings, java.time.Clock clock) {
        this.movementRepo = movementRepo;
        this.reviewService = reviewService;
        this.stockService = stockService;
        this.journalLineRepo = journalLineRepo;
        this.noteRepo = noteRepo;
        this.deliveryRepo = deliveryRepo;
        this.damageRepo = damageRepo;
        this.damageRegister = damageRegister;
        this.countRepo = countRepo;
        this.participantRepo = participantRepo;
        this.varianceRepo = varianceRepo;
        this.dayRepo = dayRepo;
        this.reconRepo = reconRepo;
        this.stockReportService = stockReportService;
        this.kpiService = kpiService;
        this.settings = settings;
        this.clock = clock;
    }

    /** A site's position at the end and what came in and went out (key: the site's id, TRANSIT or VEHICLES). */
    public record SiteRow(String key, String code, String name, int units, BigDecimal areaM2, int inUnits, BigDecimal inM2, BigDecimal inCost,
                          int outUnits, BigDecimal outM2, BigDecimal outCost) {
    }

    /** A document behind movements: its type, reference, units, m² and cost. */
    public record Doc(MovementType type, String refType, UUID refId, String refNumber, long units, BigDecimal areaM2, BigDecimal cost) {
    }

    /** An exception or delivery of the day: what kind (a message key), its number and link, a detail (or its message key) and a value. */
    public record Item(String kindKey, String number, String link, String detail, BigDecimal value, String detailKey) {

        public Item(String kindKey, String number, String link, String detail, BigDecimal value) {
            this(kindKey, number, link, detail, value, null);
        }
    }

    /** The sites' rows and the business's total: what came into stock and left it (moves between sites are in neither). */
    public record Sites(List<SiteRow> rows, SiteRow total) {
    }

    public record Day(LocalDate day, BigDecimal openingValue, BigDecimal closingValue, List<StockReviewService.ByType> byType, Sites sites,
                      List<Item> deliveries, long handOvers, List<Doc> sold, List<Doc> transfers, List<Doc> adjustments, List<Item> exceptions) {

        public BigDecimal getChange() {
            return closingValue.subtract(openingValue);
        }
    }

    /** A count closed in the period: its figures and, for an annual count, the external auditors who signed. */
    public record CountRow(UUID id, String number, StockCountType type, String place, LocalDateTime closedAt, int expected, int matched,
                           int missing, int misplaced, int extra, BigDecimal accuracy, List<String> auditors) {
    }

    public record Variances(int opened, int resolved, int open, int escalated, int material, BigDecimal missingValue, BigDecimal foundValue) {
    }

    /** A month's stock reconciliation: signed off (and when) or not, above tolerance. */
    public record MonthStatus(YearMonth month, boolean ended, LocalDateTime signedAt, String signedBy, boolean aboveTolerance) {
    }

    public record Reconciliation(int daysDue, int daysSigned, int daysCountersigned, List<MonthStatus> months) {
    }

    public record Report(InventoryPeriods.Period period, LocalDate end, BigDecimal openingValue, BigDecimal closingValue, Sites sites,
                         List<StockReviewService.ByType> byType, DamageRegisterService.Register damage, long lostUnits,
                         BigDecimal lostValue, List<CountRow> counts, int countedExpected, int countedMatched, BigDecimal accuracy, Variances variances,
                         StockReportService.SlowReport slow, int obsoleteDays, StockAgeing.SlowRow obsolete, InventoryKpiService.Measure kpis,
                         InventoryKpiService.Measure previousKpis, Reconciliation reconciliation) {

        public BigDecimal getChange() {
            return closingValue.subtract(openingValue);
        }
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    // ---------------------------------------------------------------- the day (IMP 14.1, 18; DIR-011)

    public Day day(LocalDate day) {
        LocalDateTime from = day.atStartOfDay();
        LocalDateTime to = day.plusDays(1).atStartOfDay().minusNanos(1);
        Map<UUID, Location> byId = stockService.locationsById();
        Sites sites = sites(day.plusDays(1).atStartOfDay(), from, to, byId);

        List<Item> deliveries = new ArrayList<>();
        for (DeliveryNote n : noteRepo.findByStatusAndReleasedAtBetween(DeliveryNoteStatus.RELEASED, from, to)) {
            SalesInvoice invoice = n.getInvoice();
            String who = invoice.getCustomer() == null ? "" : invoice.getCustomer().getName();
            deliveries.add(new Item("inventoryReport.delivery.note", n.getNumber(), "/delivery-notes/" + n.getId(),
                    invoice.getNumber() + (who.isEmpty() ? "" : " · " + who), null));
        }
        deliveries.sort(Comparator.comparing(Item::number));
        List<Item> exceptions = new ArrayList<>();
        for (DamageReport d : damageRepo.findByOccurredOnBetweenAndStatusNotOrderByOccurredOnAscNumberAsc(day, day, DamageStatus.CANCELLED)) {
            exceptions.add(new Item("inventoryReport.exception.damage", d.getNumber(), "/damage/" + d.getId(),
                    d.getLocation().getCode() + " · " + d.getLines().size(), d.getEstimatedLoss()));
        }
        for (StockCount c : countRepo.findByStatusAndClosedAtBetweenOrderByClosedAtAsc(StockCountStatus.CLOSED, from, to)) {
            int differences = nz(c.getMissingUnits()) + nz(c.getMisplacedUnits()) + nz(c.getExtraUnits());
            exceptions.add(new Item("inventoryReport.exception.count", c.getNumber(), "/stock-counts/" + c.getId(),
                    c.getLocation().getCode() + " · " + differences, null));
        }
        for (StockVariance v : varianceRepo.findByCreatedAtBetweenOrderByCreatedAtAsc(from, to)) {
            exceptions.add(new Item("inventoryReport.exception.variance", v.getNumber(), "/stock-variances/" + v.getId(),
                    null, v.getNetValue(), "variance.class." + v.getClassification()));
        }
        return new Day(day, journalLineRepo.balanceAt(AccountKey.INVENTORY, day.minusDays(1)), journalLineRepo.balanceAt(AccountKey.INVENTORY, day),
                reviewService.byType(from, to), sites, deliveries, deliveryRepo.countByDeliveredAtBetween(from, to),
                docs(from, to, SOLD), docs(from, to, TRANSFERS), docs(from, to, EXCEPTIONS), exceptions);
    }

    /** The Internal Controller countersigns a day the Warehouse Manager signed off (IMP 14.1, M.01): not the same person. */
    @Transactional
    public StockDayReview countersign(LocalDate day, String note) {
        StockDayReview review = dayRepo.findByDay(day).orElseThrow(() -> BusinessException.of("inventoryReport.countersign.notSigned", day.toString()));
        if (review.isCountersigned()) {
            throw BusinessException.of("inventoryReport.countersign.done", day.toString());
        }
        AppUserPrincipal me = AppUserPrincipal.current().orElseThrow(() -> BusinessException.of("till.noUser"));
        if (me.getId().equals(review.getReviewedById())) {
            throw BusinessException.of("inventoryReport.countersign.samePerson", day.toString());
        }
        String clean = PartyRules.clean(note);
        if (clean != null && clean.length() > 500) {
            throw BusinessException.onField("note", "stockReview.noteSize");
        }
        review.setCountersignedBy(me.getUsername());
        review.setCountersignedById(me.getId());
        review.setCountersignNote(clean);
        review.setCountersignedAt(LocalDateTime.now(clock));
        return review;
    }

    // ---------------------------------------------------------------- a week, month, quarter or year (IMP 18)

    public Report report(InventoryPeriods.Kind kind, LocalDate day) {
        LocalDate today = today();
        InventoryPeriods.Period period = InventoryPeriods.of(kind, day.isAfter(today) ? today : day);
        LocalDate end = period.end(today);
        LocalDateTime from = period.from().atStartOfDay();
        LocalDateTime to = end.plusDays(1).atStartOfDay().minusNanos(1);
        Map<UUID, Location> byId = stockService.locationsById();
        Sites sites = sites(end.plusDays(1).atStartOfDay(), from, to, byId);

        List<Object[]> lost = movementRepo.leftForBetween(from, to, List.of(StockStatus.LOST));
        long lostUnits = lost.isEmpty() || lost.get(0)[0] == null ? 0 : ((Number) lost.get(0)[0]).longValue();
        BigDecimal lostValue = lost.isEmpty() || lost.get(0)[1] == null ? BigDecimal.ZERO : ((BigDecimal) lost.get(0)[1]).setScale(2, RoundingMode.HALF_UP);

        List<StockCount> closed = countRepo.findByStatusAndClosedAtBetweenOrderByClosedAtAsc(StockCountStatus.CLOSED, from, to);
        Map<UUID, List<String>> auditors = new HashMap<>();
        List<UUID> annual = closed.stream().filter(c -> c.getCountType() == StockCountType.ANNUAL).map(StockCount::getId).toList();
        if (!annual.isEmpty()) {
            for (StockCountParticipant p : participantRepo.findByCountIdInAndRole(annual, CountRole.EXTERNAL_AUDITOR)) {
                if (p.isSigned()) {
                    auditors.computeIfAbsent(p.getCountId(), k -> new ArrayList<>()).add(p.getName());
                }
            }
        }
        List<CountRow> counts = new ArrayList<>();
        int expected = 0, matched = 0;
        for (StockCount c : closed) {
            int e = nz(c.getExpectedUnits()), m = nz(c.getMatchedUnits());
            expected += e;
            matched += m;
            counts.add(new CountRow(c.getId(), c.getNumber(), c.getCountType(), c.getLocation().getCode(), c.getClosedAt(), e, m,
                    nz(c.getMissingUnits()), nz(c.getMisplacedUnits()), nz(c.getExtraUnits()), StockVariances.accuracy(m, e),
                    auditors.getOrDefault(c.getId(), List.of())));
        }

        List<StockVariance> opened = varianceRepo.findByCreatedAtBetweenOrderByCreatedAtAsc(from, to);
        int resolved = 0, open = 0, escalated = 0, material = 0;
        BigDecimal missing = BigDecimal.ZERO, found = BigDecimal.ZERO;
        for (StockVariance v : opened) {
            switch (v.getStatus()) {
                case RESOLVED -> resolved++;
                case ESCALATED -> escalated++;
                default -> open++;
            }
            if (v.isMaterial()) {
                material++;
            }
            missing = missing.add(v.getMissingValue());
            found = found.add(v.getFoundValue());
        }

        int obsoleteDays = settings.getInt(SettingKey.STOCK_OBSOLETE_DAYS);
        StockReportService.SlowReport slow = stockReportService.slowMoving(stockReportService.slowMovingDays(), null);
        StockReportService.SlowReport obsolete = stockReportService.slowMoving(obsoleteDays, null);

        InventoryPeriods.Period before = InventoryPeriods.previous(period);
        return new Report(period, end, journalLineRepo.balanceAt(AccountKey.INVENTORY, period.from().minusDays(1)),
                journalLineRepo.balanceAt(AccountKey.INVENTORY, end), sites, reviewService.byType(from, to),
                damageRegister.register(period.from(), end, null), lostUnits, lostValue, counts, expected, matched, StockVariances.accuracy(matched, expected),
                new Variances(opened.size(), resolved, open, escalated, material, missing, found), slow, obsoleteDays, obsolete.total(),
                kpiService.measure(period.from(), period.to()), kpiService.measure(before.from(), before.to()),
                reconciliation(period.from(), end, today));
    }

    private Reconciliation reconciliation(LocalDate from, LocalDate end, LocalDate today) {
        Map<LocalDate, StockDayReview> reviews = new HashMap<>();
        dayRepo.findByDayBetween(from, end).forEach(r -> reviews.put(r.getDay(), r));
        int due = 0, signed = 0, countersigned = 0;
        LocalDateTime first = movementRepo.firstMovementAt();
        LocalDate live = first == null ? today : first.toLocalDate();
        for (LocalDate d = from; !d.isAfter(end); d = d.plusDays(1)) {
            if (!WorkingDays.isWorking(d) || !d.isBefore(today) || d.isBefore(live)) {
                continue;
            }
            due++;
            StockDayReview r = reviews.get(d);
            if (r != null) {
                signed++;
                if (r.isCountersigned()) {
                    countersigned++;
                }
            }
        }
        List<MonthStatus> months = new ArrayList<>();
        for (YearMonth m = YearMonth.from(from); !m.isAfter(YearMonth.from(end)); m = m.plusMonths(1)) {
            Optional<StockReconciliation> r = reconRepo.findByPeriodStart(m.atDay(1));
            months.add(new MonthStatus(m, m.atEndOfMonth().isBefore(today), r.map(StockReconciliation::getReviewedAt).orElse(null),
                    r.map(StockReconciliation::getReviewedBy).orElse(null), r.map(StockReconciliation::isAboveTolerance).orElse(false)));
        }
        return new Reconciliation(due, signed, countersigned, months);
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Each site's units and m² at a moment, with what came in and went out between two moments. A unit counts on its
     * place's site while on hand; in transit and on vehicles apart.
     */
    Sites sites(LocalDateTime at, LocalDateTime from, LocalDateTime to, Map<UUID, Location> byId) {
        Map<String, long[]> counts = new LinkedHashMap<>();
        Map<String, BigDecimal[]> sums = new LinkedHashMap<>();
        long[] all = new long[3];
        BigDecimal[] allSums = zeros();
        for (Object[] r : movementRepo.positionAt(at)) {
            UUID loc = (UUID) r[0];
            StockStatus status = r[1] == null ? null : StockStatus.valueOf((String) r[1]);
            String key = bucket(status, loc, byId);
            if (key == null) {
                continue;
            }
            counts.computeIfAbsent(key, k -> new long[3])[0] += ((Number) r[2]).longValue();
            sums.computeIfAbsent(key, k -> zeros())[0] = sums.get(key)[0].add((BigDecimal) r[3]);
            all[0] += ((Number) r[2]).longValue();
            allSums[0] = allSums[0].add((BigDecimal) r[3]);
        }
        for (Object[] r : movementRepo.flowsBetween(from, to)) {
            String out = bucket((StockStatus) r[2], (UUID) r[1], byId);
            String in = bucket((StockStatus) r[4], (UUID) r[3], byId);
            if (Objects.equals(out, in)) {
                continue;
            }
            BigDecimal area = (BigDecimal) r[5];
            BigDecimal cost = r[6] == null ? BigDecimal.ZERO : (BigDecimal) r[6];
            if (out == null) {
                all[1]++;
                allSums[1] = allSums[1].add(area);
                allSums[2] = allSums[2].add(cost);
            } else if (in == null) {
                all[2]++;
                allSums[3] = allSums[3].add(area);
                allSums[4] = allSums[4].add(cost);
            }
            if (in != null) {
                counts.computeIfAbsent(in, k -> new long[3])[1]++;
                BigDecimal[] s = sums.computeIfAbsent(in, k -> zeros());
                s[1] = s[1].add(area);
                s[2] = s[2].add(cost);
            }
            if (out != null) {
                counts.computeIfAbsent(out, k -> new long[3])[2]++;
                BigDecimal[] s = sums.computeIfAbsent(out, k -> zeros());
                s[3] = s[3].add(area);
                s[4] = s[4].add(cost);
            }
        }
        List<SiteRow> rows = new ArrayList<>();
        for (String key : counts.keySet()) {
            long[] c = counts.get(key);
            BigDecimal[] s = sums.getOrDefault(key, zeros());
            Location site = TRANSIT.equals(key) || VEHICLES.equals(key) ? null : byId.get(UUID.fromString(key));
            rows.add(new SiteRow(key, site == null ? key : site.getCode(), site == null ? null : site.getName(), (int) c[0], s[0], (int) c[1], s[1],
                    s[2].setScale(2, RoundingMode.HALF_UP), (int) c[2], s[3], s[4].setScale(2, RoundingMode.HALF_UP)));
        }
        rows.sort(Comparator.comparing((SiteRow r) -> TRANSIT.equals(r.key()) || VEHICLES.equals(r.key())).thenComparing(SiteRow::code));
        return new Sites(rows, new SiteRow("", "", null, (int) all[0], allSums[0], (int) all[1], allSums[1], allSums[2].setScale(2, RoundingMode.HALF_UP),
                (int) all[2], allSums[3], allSums[4].setScale(2, RoundingMode.HALF_UP)));
    }

    private static String bucket(StockStatus status, UUID location, Map<UUID, Location> byId) {
        if (status == null || !status.isOnHand()) {
            return null;
        }
        if (status == StockStatus.IN_TRANSIT) {
            return TRANSIT;
        }
        if (status == StockStatus.ON_VEHICLE) {
            return VEHICLES;
        }
        Location l = location == null ? null : byId.get(location);
        if (l == null) {
            return null;
        }
        UUID site = SiteTransferService.siteOf(l, byId);
        return site == null ? null : site.toString();
    }

    private List<Doc> docs(LocalDateTime from, LocalDateTime to, Collection<MovementType> types) {
        List<Doc> docs = new ArrayList<>();
        for (Object[] r : movementRepo.documentsBetween(from, to, types)) {
            docs.add(new Doc((MovementType) r[0], (String) r[1], (UUID) r[2], (String) r[3], ((Number) r[4]).longValue(), (BigDecimal) r[5],
                    ((BigDecimal) r[6]).setScale(2, RoundingMode.HALF_UP)));
        }
        return docs;
    }

    private static BigDecimal[] zeros() {
        return new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
    }

    private static int nz(Integer n) {
        return n == null ? 0 : n;
    }
}
