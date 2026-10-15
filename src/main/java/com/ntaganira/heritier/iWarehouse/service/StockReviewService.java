package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.StockDayReview;
import com.ntaganira.heritier.iWarehouse.entity.StockMovement;
import com.ntaganira.heritier.iWarehouse.entity.StockReconciliation;
import com.ntaganira.heritier.iWarehouse.entity.StockUnit;
import com.ntaganira.heritier.iWarehouse.enums.*;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.repository.*;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : StockReviewService.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : Reviews of the stock movements. Daily (IMP 12): the day's movements by type and one by one, signed off once.
 *               Monthly (BP 7.4, Table 9, Annex I step 5): the month's movements by type; units loaded on vehicles against
 *               those sold, back, broken, missing or still on board; units sent to other sites against those arrived or in
 *               transit; the Transaction Tickets of the month still open; glass written off, broken or lost at cost against
 *               the Inventory account at the month's end, flagged above the Settings' tolerance. Signing an ended month off
 *               keeps those figures; one above tolerance tells those who see the reconciliation (the Internal Controller).
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class StockReviewService {

    private final StockMovementRepository movementRepo;
    private final StockDayReviewRepository dayRepo;
    private final StockReconciliationRepository reconRepo;
    private final TripRepository tripRepo;
    private final SiteTransferUnitRepository siteUnitRepo;
    private final TransactionTicketRepository ticketRepo;
    private final JournalLineRepository journalLineRepo;
    private final StockUnitRepository unitRepo;
    private final SettingService settingService;
    private final Notifier notifier;
    private final Clock clock;

    public StockReviewService(StockMovementRepository movementRepo, StockDayReviewRepository dayRepo, StockReconciliationRepository reconRepo,
                              TripRepository tripRepo, SiteTransferUnitRepository siteUnitRepo, TransactionTicketRepository ticketRepo,
                              JournalLineRepository journalLineRepo, StockUnitRepository unitRepo, SettingService settingService,
                              Notifier notifier, Clock clock) {
        this.unitRepo = unitRepo;
        this.movementRepo = movementRepo;
        this.dayRepo = dayRepo;
        this.reconRepo = reconRepo;
        this.tripRepo = tripRepo;
        this.siteUnitRepo = siteUnitRepo;
        this.ticketRepo = ticketRepo;
        this.journalLineRepo = journalLineRepo;
        this.settingService = settingService;
        this.notifier = notifier;
        this.clock = clock;
    }

    /** Movements of one type: how many, their m² and cost. */
    public record ByType(MovementType type, long count, BigDecimal areaM2, BigDecimal cost) {
    }

    /** Units loaded on vehicles in a month and what became of them. */
    public record Vehicles(long loaded, long sold, long returned, long broken, long missing, long onTheRoad) {

        public long getAccounted() {
            return sold + returned + broken + onTheRoad;
        }

        public long getDifference() {
            return loaded - getAccounted();
        }
    }

    /** Units sent to other sites in a month and what became of them. */
    public record Sites(long dispatched, long arrived, long inTransit) {

        public long getDifference() {
            return dispatched - arrived - inTransit;
        }
    }

    public record Month(YearMonth month, List<ByType> byType, Vehicles vehicles, Sites sites, long ticketsRaised, long ticketsOpen,
                        long lostUnits, BigDecimal lostValue, BigDecimal stockValue, BigDecimal percent, BigDecimal tolerance,
                        boolean aboveTolerance) {
    }

    // ---------------------------------------------------------------- the day (IMP 12)

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    public YearMonth thisMonth() {
        return YearMonth.now(clock);
    }

    public List<ByType> dayByType(LocalDate day) {
        return byType(day.atStartOfDay(), day.plusDays(1).atStartOfDay().minusNanos(1));
    }

    public Page<StockMovement> dayMovements(LocalDate day, MovementType type, int page, int size) {
        LocalDateTime from = day.atStartOfDay();
        LocalDateTime to = day.plusDays(1).atStartOfDay().minusNanos(1);
        return type == null ? movementRepo.findByMovedAtBetweenOrderByMovedAtDesc(from, to, PageRequest.of(page, size))
                : movementRepo.findByMovedAtBetweenAndTypeOrderByMovedAtDesc(from, to, type, PageRequest.of(page, size));
    }

    /** The units of these movements by id, with their glass (the day's list names each). */
    public Map<UUID, StockUnit> unitsOf(Collection<StockMovement> movements) {
        List<UUID> ids = movements.stream().map(StockMovement::getStockUnitId).distinct().toList();
        Map<UUID, StockUnit> byId = new HashMap<>();
        if (!ids.isEmpty()) {
            unitRepo.findByIdIn(ids).forEach(u -> byId.put(u.getId(), u));
        }
        return byId;
    }

    public Optional<StockDayReview> dayReview(LocalDate day) {
        return dayRepo.findByDay(day);
    }

    public Page<StockDayReview> dayReviews(int page, int size) {
        return dayRepo.findAllByOrderByDayDesc(PageRequest.of(page, size));
    }

    /** Signs a day's movements off, once, up to today. */
    @Transactional
    public StockDayReview reviewDay(LocalDate day, String note) {
        if (day.isAfter(today())) {
            throw BusinessException.of("stockReview.day.future");
        }
        if (dayReview(day).isPresent()) {
            throw BusinessException.of("stockReview.day.done", day.toString());
        }
        String clean = cleanNote(note);
        Optional<AppUserPrincipal> user = AppUserPrincipal.current();
        StockDayReview review = new StockDayReview();
        review.setDay(day);
        review.setMovements((int) dayByType(day).stream().mapToLong(ByType::count).sum());
        review.setNote(clean);
        review.setReviewedBy(user.map(AppUserPrincipal::getUsername).orElse("system"));
        review.setReviewedById(user.map(AppUserPrincipal::getId).orElse(null));
        review.setReviewedAt(LocalDateTime.now(clock));
        return dayRepo.save(review);
    }

    // ---------------------------------------------------------------- the month (BP 7.4, Annex I step 5)

    public Month month(YearMonth month) {
        LocalDateTime from = month.atDay(1).atStartOfDay();
        LocalDateTime to = month.atEndOfMonth().plusDays(1).atStartOfDay().minusNanos(1);
        long loaded = 0, sold = 0, returned = 0, broken = 0, missing = 0, onRoad = 0;
        for (Object[] r : tripRepo.lineOutcomesDepartedBetween(from, to)) {
            TripLineOutcome outcome = (TripLineOutcome) r[0];
            TripStatus status = (TripStatus) r[1];
            long n = ((Number) r[2]).longValue();
            if (status == TripStatus.CANCELLED || status == TripStatus.PLANNED) {
                continue;
            }
            loaded += n;
            if (outcome == null) {
                onRoad += status == TripStatus.DEPARTED ? n : 0;
            } else {
                switch (outcome) {
                    case SOLD -> sold += n;
                    case RETURNED -> returned += n;
                    case BROKEN -> broken += n;
                    case MISSING -> missing += n;
                }
            }
        }
        List<Object[]> dispatched = siteUnitRepo.dispatchedBetween(from, to);
        long sent = dispatched.isEmpty() || dispatched.get(0)[0] == null ? 0 : ((Number) dispatched.get(0)[0]).longValue();
        long arrived = dispatched.isEmpty() || dispatched.get(0)[1] == null ? 0 : ((Number) dispatched.get(0)[1]).longValue();
        long inTransit = siteUnitRepo.inTransitBetween(from, to);
        long raised = ticketRepo.countByRaisedAtBetweenAndStatusIn(from, to, List.of(TicketStatus.values()));
        long open = ticketRepo.countByRaisedAtBetweenAndStatusIn(from, to, TicketService.OPEN);
        List<Object[]> left = movementRepo.leftForBetween(from, to, List.of(StockStatus.BROKEN, StockStatus.LOST));
        long lostUnits = left.isEmpty() ? 0 : ((Number) left.get(0)[0]).longValue();
        BigDecimal lostValue = left.isEmpty() ? BigDecimal.ZERO : ((BigDecimal) left.get(0)[1]).setScale(2, RoundingMode.HALF_UP);
        LocalDate end = month.atEndOfMonth().isAfter(today()) ? today() : month.atEndOfMonth();
        BigDecimal stockValue = journalLineRepo.balanceAt(AccountKey.INVENTORY, end).setScale(2, RoundingMode.HALF_UP);
        BigDecimal percent = stockValue.signum() <= 0 ? BigDecimal.ZERO
                : lostValue.multiply(BigDecimal.valueOf(100)).divide(stockValue, 4, RoundingMode.HALF_UP);
        BigDecimal tolerance = settingService.getDecimal(SettingKey.RECONCILIATION_TOLERANCE_PERCENT);
        boolean above = percent.compareTo(tolerance) > 0;
        return new Month(month, byType(from, to), new Vehicles(loaded, sold, returned, broken, missing, onRoad), new Sites(sent, arrived, inTransit),
                raised, open, lostUnits, lostValue, stockValue, percent, tolerance, above);
    }

    public Optional<StockReconciliation> monthReview(YearMonth month) {
        return reconRepo.findByPeriodStart(month.atDay(1));
    }

    public Page<StockReconciliation> monthReviews(int page, int size) {
        return reconRepo.findAllByOrderByPeriodStartDesc(PageRequest.of(page, size));
    }

    /** Signs an ended month off, once, keeping its figures; above the tolerance, those who see the reconciliation are told. */
    @Transactional
    public StockReconciliation reviewMonth(YearMonth month, String note) {
        if (!month.isBefore(thisMonth())) {
            throw BusinessException.of("stockReview.month.notEnded", month.toString());
        }
        if (monthReview(month).isPresent()) {
            throw BusinessException.of("stockReview.month.done", month.toString());
        }
        Month m = month(month);
        String clean = cleanNote(note);
        if (m.aboveTolerance() && clean == null) {
            throw BusinessException.onField("note", "stockReview.month.noteRequired");
        }
        Optional<AppUserPrincipal> user = AppUserPrincipal.current();
        StockReconciliation r = new StockReconciliation();
        r.setPeriodStart(month.atDay(1));
        r.setLoadedUnits((int) m.vehicles().loaded());
        r.setAccountedUnits((int) m.vehicles().getAccounted());
        r.setDispatchedUnits((int) m.sites().dispatched());
        r.setArrivedUnits((int) (m.sites().arrived() + m.sites().inTransit()));
        r.setTicketsOpen((int) m.ticketsOpen());
        r.setVarianceValue(m.lostValue());
        r.setStockValue(m.stockValue());
        r.setVariancePercent(m.percent());
        r.setAboveTolerance(m.aboveTolerance());
        r.setNote(clean);
        r.setReviewedBy(user.map(AppUserPrincipal::getUsername).orElse("system"));
        r.setReviewedById(user.map(AppUserPrincipal::getId).orElse(null));
        r.setReviewedAt(LocalDateTime.now(clock));
        reconRepo.save(r);
        if (m.aboveTolerance()) {
            notifier.holders("VIEW_STOCK_RECONCILIATION", r.getReviewedById(), NotificationKind.TRANSFER, "notify.reconciliation.above",
                    "notify.reconciliation.aboveText", "/reports/stock-reconciliation?month=" + month, month.toString(),
                    m.percent().setScale(2, RoundingMode.HALF_UP).toPlainString(), m.tolerance().stripTrailingZeros().toPlainString());
        }
        return r;
    }

    // ---------------------------------------------------------------- helpers

    private List<ByType> byType(LocalDateTime from, LocalDateTime to) {
        List<ByType> list = new ArrayList<>();
        for (Object[] r : movementRepo.byTypeBetween(from, to)) {
            list.add(new ByType((MovementType) r[0], ((Number) r[1]).longValue(), (BigDecimal) r[2], (BigDecimal) r[3]));
        }
        list.sort(Comparator.comparing(ByType::type));
        return list;
    }

    private static String cleanNote(String note) {
        String clean = PartyRules.clean(note);
        if (clean != null && clean.length() > 500) {
            throw BusinessException.onField("note", "stockReview.noteSize");
        }
        return clean;
    }
}
