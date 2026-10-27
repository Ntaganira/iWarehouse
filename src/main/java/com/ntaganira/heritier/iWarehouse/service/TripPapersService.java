package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.SalesInvoice;
import com.ntaganira.heritier.iWarehouse.entity.SalesPayment;
import com.ntaganira.heritier.iWarehouse.entity.StockUnit;
import com.ntaganira.heritier.iWarehouse.entity.Trip;
import com.ntaganira.heritier.iWarehouse.entity.TripBreakage;
import com.ntaganira.heritier.iWarehouse.entity.TripCount;
import com.ntaganira.heritier.iWarehouse.entity.TripDeposit;
import com.ntaganira.heritier.iWarehouse.entity.TripLine;
import com.ntaganira.heritier.iWarehouse.entity.VehicleCost;
import com.ntaganira.heritier.iWarehouse.enums.BreakageStatus;
import com.ntaganira.heritier.iWarehouse.enums.PaymentMethod;
import com.ntaganira.heritier.iWarehouse.enums.SalesInvoiceStatus;
import com.ntaganira.heritier.iWarehouse.enums.VehicleCostPayment;
import com.ntaganira.heritier.iWarehouse.enums.VehicleCostStatus;
import com.ntaganira.heritier.iWarehouse.repository.SalesInvoiceRepository;
import com.ntaganira.heritier.iWarehouse.repository.SalesPaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : TripPapersService.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : A trip's papers (F-09, F-10, F-11; N.08, N.12, N.20): the stock issue note (the manifest at cost with
 *               the load request), the daily return of a day on the road (its sales with their customers, pieces and how
 *               they were paid, the cash, MoMo and credit, what was banked, the costs paid from the float, the cash still
 *               held, and the van's equation: loaded = sold + broken + still on board, with the day's counts) and what the
 *               clearance certificate states. Read only.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class TripPapersService {

    private final SalesInvoiceRepository invoiceRepo;
    private final SalesPaymentRepository paymentRepo;
    private final TripService tripService;
    private final TripDepositService depositService;
    private final TripCountService countService;
    private final BreakageService breakageService;
    private final VehicleCostService costService;
    private final Clock clock;

    public TripPapersService(SalesInvoiceRepository invoiceRepo, SalesPaymentRepository paymentRepo, TripService tripService,
                             TripDepositService depositService, TripCountService countService, BreakageService breakageService,
                             VehicleCostService costService, Clock clock) {
        this.invoiceRepo = invoiceRepo;
        this.paymentRepo = paymentRepo;
        this.tripService = tripService;
        this.depositService = depositService;
        this.countService = countService;
        this.breakageService = breakageService;
        this.costService = costService;
        this.clock = clock;
    }

    /** A unit of the stock issue note, with what it cost. */
    public record IssueRow(TripLine line, StockUnit unit) {

        public BigDecimal getCost() {
            return unit == null || unit.getUnitCost() == null ? BigDecimal.ZERO : unit.getUnitCost();
        }
    }

    /** The stock issue note: the manifest's units in order, their pieces, m² and value at cost. */
    public record IssueNote(Trip trip, List<IssueRow> rows, BigDecimal area, BigDecimal value) {
    }

    /** A sale of the day, with its pieces and how it was paid. */
    public record DaySale(SalesInvoice invoice, int pieces, BigDecimal cash, BigDecimal momo, BigDecimal credit) {
    }

    /** The daily return of a day on the road (F-10). */
    public record DailyReturn(Trip trip, LocalDate day, List<LocalDate> days, List<DaySale> sales, List<TripDeposit> deposits,
                              List<VehicleCost> costs, BigDecimal cashToDate, BigDecimal bankedToDate, BigDecimal costsToDate, int loaded,
                              int soldToDate, int brokenToDate, List<TripCount> counts) {

        public BigDecimal getCash() {
            return sum(sales.stream().map(DaySale::cash).toList());
        }

        public BigDecimal getMomo() {
            return sum(sales.stream().map(DaySale::momo).toList());
        }

        public BigDecimal getCredit() {
            return sum(sales.stream().map(DaySale::credit).toList());
        }

        public BigDecimal getTotalSales() {
            return sum(sales.stream().map(s -> s.invoice().getTotalAmount()).toList());
        }

        public int getPieces() {
            return sales.stream().mapToInt(DaySale::pieces).sum();
        }

        public BigDecimal getBanked() {
            return sum(deposits.stream().map(TripDeposit::getAmount).toList());
        }

        public BigDecimal getCostsFromFloat() {
            return sum(costs.stream().map(VehicleCost::getAmount).toList());
        }

        /** The cash the driver still holds at the end of the day: cash taken so far less what was banked and spent. */
        public BigDecimal getCashHeld() {
            return cashToDate.subtract(bankedToDate).subtract(costsToDate);
        }

        /** Still on board at the end of the day: loaded less sold and broken (the van's equation, SDP 6). */
        public int getOnBoard() {
            return loaded - soldToDate - brokenToDate;
        }

        private static BigDecimal sum(List<BigDecimal> values) {
            return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    public IssueNote issueNote(Trip trip) {
        Map<UUID, StockUnit> units = tripService.units(trip);
        List<IssueRow> rows = TripService.sortedLines(trip).stream().map(l -> new IssueRow(l, units.get(l.getStockUnitId()))).toList();
        BigDecimal area = rows.stream().filter(r -> r.unit() != null).map(r -> r.unit().getAreaM2()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal value = rows.stream().map(IssueRow::getCost).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new IssueNote(trip, rows, area, value);
    }

    /** The days a trip was on the road: from its departure to its return (or today). */
    public List<LocalDate> days(Trip trip) {
        List<LocalDate> days = new ArrayList<>();
        if (trip.getDepartedAt() == null) {
            return days;
        }
        LocalDate last = trip.getReturnedAt() != null ? trip.getReturnedAt().toLocalDate() : LocalDate.now(clock);
        for (LocalDate d = trip.getDepartedAt().toLocalDate(); !d.isAfter(last); d = d.plusDays(1)) {
            days.add(d);
        }
        return days;
    }

    public DailyReturn dailyReturn(Trip trip, LocalDate day) {
        List<SalesInvoice> invoices = invoiceRepo.findByTrip_IdAndStatusOrderByNumber(trip.getId(), SalesInvoiceStatus.POSTED);
        Map<UUID, List<SalesPayment>> paid = invoices.isEmpty() ? Map.of()
                : paymentRepo.findByInvoiceIdIn(invoices.stream().map(SalesInvoice::getId).toList()).stream()
                .collect(Collectors.groupingBy(SalesPayment::getInvoiceId));
        List<DaySale> sales = new ArrayList<>();
        BigDecimal cashToDate = BigDecimal.ZERO;
        int soldToDate = 0;
        for (SalesInvoice inv : invoices) {
            LocalDate on = inv.getInvoiceDate();
            if (on == null || on.isAfter(day)) {
                continue;
            }
            List<SalesPayment> payments = paid.getOrDefault(inv.getId(), List.of());
            BigDecimal cash = sum(payments, PaymentMethod.CASH);
            cashToDate = cashToDate.add(cash);
            soldToDate += inv.getLines().size();
            if (on.equals(day)) {
                sales.add(new DaySale(inv, inv.getLines().size(), cash, sum(payments, PaymentMethod.MOBILE_MONEY), sum(payments, PaymentMethod.CREDIT)));
            }
        }
        List<TripDeposit> allDeposits = depositService.ofTrip(trip.getId());
        BigDecimal bankedToDate = allDeposits.stream().filter(d -> !d.getDepositedOn().isAfter(day)).map(TripDeposit::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<VehicleCost> floatCosts = costService.ofTrip(trip.getId()).stream()
                .filter(c -> c.getPayment() == VehicleCostPayment.DRIVER_FLOAT && c.getStatus() == VehicleCostStatus.POSTED).toList();
        BigDecimal costsToDate = floatCosts.stream().filter(c -> !c.getCostDate().isAfter(day)).map(VehicleCost::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int brokenToDate = (int) breakageService.ofTrip(trip.getId()).stream()
                .filter(b -> b.getStatus() == BreakageStatus.ACCEPTED && b.getHappenedAt() != null && !b.getHappenedAt().toLocalDate().isAfter(day))
                .count();
        List<TripCount> counts = countService.ofTrip(trip.getId()).stream().filter(c -> c.getCountedAt().toLocalDate().equals(day)).toList();
        int loaded = trip.getLoadedPieces() == null ? trip.getLines().size() : trip.getLoadedPieces();
        return new DailyReturn(trip, day, days(trip), sales, allDeposits.stream().filter(d -> d.getDepositedOn().equals(day)).toList(),
                floatCosts.stream().filter(c -> c.getCostDate().equals(day)).toList(), cashToDate, bankedToDate, costsToDate, loaded, soldToDate,
                brokenToDate, counts);
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    private static BigDecimal sum(List<SalesPayment> payments, PaymentMethod method) {
        return payments.stream().filter(p -> p.getMethod() == method && !p.isBalancePayment()).map(SalesPayment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
