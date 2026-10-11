package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.AuditCase;
import com.ntaganira.heritier.iWarehouse.entity.AuditCaseLine;
import com.ntaganira.heritier.iWarehouse.entity.FloatClearance;
import com.ntaganira.heritier.iWarehouse.entity.Trip;
import com.ntaganira.heritier.iWarehouse.enums.PaymentMethod;
import com.ntaganira.heritier.iWarehouse.enums.TripStatus;
import com.ntaganira.heritier.iWarehouse.repository.AuditCaseRepository;
import com.ntaganira.heritier.iWarehouse.repository.FloatClearanceRepository;
import com.ntaganira.heritier.iWarehouse.repository.SalesPaymentRepository;
import com.ntaganira.heritier.iWarehouse.repository.TripRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : FleetReportService.java
 * - Date      : 2026. 10. 11.
 * - User      : Hntaganira
 * - Desc      : The fleet reports (RPT-04) from the documents: the trips back in a period (by the day they came back), their
 *               sales payments, float clearances and audit cases, worked into FleetAnalysis; the floats outstanding are
 *               those not cleared today, whatever the period. Filtered by driver and vehicle.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class FleetReportService {

    private static final List<TripStatus> BACK = List.of(TripStatus.RETURNED, TripStatus.CLOSED);

    private final TripRepository tripRepo;
    private final SalesPaymentRepository paymentRepo;
    private final FloatClearanceRepository clearanceRepo;
    private final AuditCaseRepository caseRepo;
    private final FloatService floatService;
    private final Clock clock;

    public FleetReportService(TripRepository tripRepo, SalesPaymentRepository paymentRepo, FloatClearanceRepository clearanceRepo,
                              AuditCaseRepository caseRepo, FloatService floatService, Clock clock) {
        this.tripRepo = tripRepo;
        this.paymentRepo = paymentRepo;
        this.clearanceRepo = clearanceRepo;
        this.caseRepo = caseRepo;
        this.floatService = floatService;
        this.clock = clock;
    }

    public record Report(List<FleetAnalysis.TripRow> trips, List<FleetAnalysis.DriverRow> drivers, FleetAnalysis.DriverRow total,
                         List<FleetAnalysis.DiscrepancyRow> discrepancies, long openDiscrepancies, List<FleetAnalysis.FloatRow> floats,
                         BigDecimal floatsOutstanding, List<SalesReportService.Option> driverOptions, List<SalesReportService.Option> vehicleOptions) {
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    public Report report(LocalDate from, LocalDate to, UUID driverId, UUID vehicleId) {
        List<Trip> all = tripRepo.findReturnedBetween(BACK, from.atStartOfDay(), to.plusDays(1).atStartOfDay());
        List<SalesReportService.Option> drivers = all.stream().map(t -> new SalesReportService.Option(t.getDriver().getId().toString(),
                t.getDriver().getUser().getFullName())).distinct().sorted(Comparator.comparing(SalesReportService.Option::label)).toList();
        List<SalesReportService.Option> vehicles = all.stream().map(t -> new SalesReportService.Option(t.getVehicle().getId().toString(),
                t.getVehicle().getPlate())).distinct().sorted(Comparator.comparing(SalesReportService.Option::label)).toList();
        List<Trip> trips = all.stream().filter(t -> (driverId == null || t.getDriver().getId().equals(driverId))
                && (vehicleId == null || t.getVehicle().getId().equals(vehicleId))).toList();
        List<UUID> ids = trips.stream().map(Trip::getId).toList();

        Map<UUID, BigDecimal> sales = new HashMap<>();
        Map<UUID, BigDecimal> cash = new HashMap<>();
        Map<UUID, FloatClearance> clearances = new HashMap<>();
        List<AuditCase> cases = List.of();
        if (!ids.isEmpty()) {
            for (Object[] row : paymentRepo.totalsByMethodOfTrips(ids)) {
                UUID tripId = (UUID) row[0];
                BigDecimal amount = (BigDecimal) row[2];
                sales.merge(tripId, amount, BigDecimal::add);
                if (row[1] == PaymentMethod.CASH) {
                    cash.merge(tripId, amount, BigDecimal::add);
                }
            }
            clearanceRepo.findByTripIds(ids).forEach(c -> clearances.put(c.getTrip().getId(), c));
            cases = caseRepo.findByTrip_IdIn(ids);
        }
        Map<UUID, Trip> byId = trips.stream().collect(Collectors.toMap(Trip::getId, Function.identity()));
        Map<UUID, Integer> perTrip = new HashMap<>();
        List<FleetAnalysis.DiscrepancyRow> discrepancies = new ArrayList<>();
        for (AuditCase c : cases) {
            Trip t = byId.get(c.getTrip().getId());
            perTrip.put(t.getId(), c.getLines().size());
            for (AuditCaseLine l : AuditCaseService.sortedLines(c)) {
                discrepancies.add(new FleetAnalysis.DiscrepancyRow(c.getId(), c.getNumber(), c.getStatus().name(), t.getId(), t.getNumber(),
                        t.getReturnedAt().toLocalDate(), t.getDriver().getUser().getFullName(), l.getUnitCode(), l.getKind().name(),
                        l.isMissing() ? l.getUnitValue() : null, l.getOutcome() == null ? null : l.getOutcome().name()));
            }
        }
        discrepancies.sort(Comparator.comparing(FleetAnalysis.DiscrepancyRow::date).thenComparing(FleetAnalysis.DiscrepancyRow::trip)
                .thenComparing(FleetAnalysis.DiscrepancyRow::unitCode));

        List<FleetAnalysis.TripRow> rows = trips.stream().map(t -> {
            FloatClearance c = clearances.get(t.getId());
            return new FleetAnalysis.TripRow(t.getId(), t.getNumber(), t.getReturnedAt().toLocalDate(), t.getVehicle().getPlate(), t.getDriver().getId(),
                    t.getDriver().getUser().getFullName(), t.getStatus().name(), t.getLoadedPieces(), t.getSoldPieces(), t.getBrokenPieces(),
                    t.getReturnedPieces(), t.getMissingPieces(), sales.getOrDefault(t.getId(), BigDecimal.ZERO), cash.getOrDefault(t.getId(), BigDecimal.ZERO),
                    c == null ? null : c.getCounted(), c == null ? BigDecimal.ZERO : c.getShortage(), c == null ? BigDecimal.ZERO : c.getOverage(),
                    perTrip.getOrDefault(t.getId(), 0));
        }).toList();

        List<FleetAnalysis.Uncleared> uncleared = floatService.toClear().stream()
                .filter(u -> (driverId == null || u.trip().getDriver().getId().equals(driverId))
                        && (vehicleId == null || u.trip().getVehicle().getId().equals(vehicleId)))
                .map(u -> new FleetAnalysis.Uncleared(u.trip().getDriver().getId(), u.trip().getDriver().getUser().getFullName(), u.trip().getNumber(),
                        u.trip().getReturnedAt(), u.expected())).toList();
        List<FleetAnalysis.FloatRow> floats = FleetAnalysis.floats(uncleared);
        return new Report(rows, FleetAnalysis.byDriver(rows), FleetAnalysis.total(rows), discrepancies,
                discrepancies.stream().filter(d -> d.outcome() == null).count(), floats,
                floats.stream().map(FleetAnalysis.FloatRow::amount).reduce(BigDecimal.ZERO, BigDecimal::add), drivers, vehicles);
    }
}
