package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.enums.SettingKey;
import com.ntaganira.heritier.iWarehouse.entity.AuditCase;
import com.ntaganira.heritier.iWarehouse.entity.AuditCaseLine;
import com.ntaganira.heritier.iWarehouse.entity.FloatClearance;
import com.ntaganira.heritier.iWarehouse.entity.Trip;
import com.ntaganira.heritier.iWarehouse.entity.Vehicle;
import com.ntaganira.heritier.iWarehouse.enums.PaymentMethod;
import com.ntaganira.heritier.iWarehouse.enums.TripStatus;
import com.ntaganira.heritier.iWarehouse.enums.VehicleCostCategory;
import com.ntaganira.heritier.iWarehouse.enums.VehicleCostStatus;
import com.ntaganira.heritier.iWarehouse.repository.AuditCaseRepository;
import com.ntaganira.heritier.iWarehouse.repository.FloatClearanceRepository;
import com.ntaganira.heritier.iWarehouse.repository.SalesPaymentRepository;
import com.ntaganira.heritier.iWarehouse.repository.TripRepository;
import com.ntaganira.heritier.iWarehouse.repository.VehicleCostRepository;
import com.ntaganira.heritier.iWarehouse.repository.VehicleRepository;
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
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
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
    private final VehicleCostRepository costRepo;
    private final VehicleRepository vehicleRepo;
    private final Clock clock;
    private final SettingService settings;

    public FleetReportService(TripRepository tripRepo, SalesPaymentRepository paymentRepo, FloatClearanceRepository clearanceRepo,
                              AuditCaseRepository caseRepo, FloatService floatService, VehicleCostRepository costRepo,
                              VehicleRepository vehicleRepo, Clock clock, SettingService settings) {
        this.settings = settings;
        this.tripRepo = tripRepo;
        this.paymentRepo = paymentRepo;
        this.clearanceRepo = clearanceRepo;
        this.caseRepo = caseRepo;
        this.floatService = floatService;
        this.costRepo = costRepo;
        this.vehicleRepo = vehicleRepo;
        this.clock = clock;
    }

    public record Report(List<FleetAnalysis.TripRow> trips, List<FleetAnalysis.DriverRow> drivers, FleetAnalysis.DriverRow total,
                         List<FleetAnalysis.DiscrepancyRow> discrepancies, long openDiscrepancies, List<FleetAnalysis.FloatRow> floats,
                         BigDecimal floatsOutstanding, List<FleetAnalysis.VehicleRow> vehicles, FleetAnalysis.VehicleRow vehicleTotal,
                         List<SalesReportService.Option> driverOptions, List<SalesReportService.Option> vehicleOptions,
                         FleetAnalysis.LoadKpis loadKpis) {
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

        // What each vehicle cost to run: its posted costs dated in the period, beside its trips' km and sales
        List<FleetAnalysis.TripLeg> legs = trips.stream().map(t -> new FleetAnalysis.TripLeg(t.getVehicle().getId(), t.getVehicle().getPlate(),
                t.getDistanceKm(), sales.getOrDefault(t.getId(), BigDecimal.ZERO))).toList();
        Map<UUID, String> plates = vehicleRepo.findAll().stream().collect(Collectors.toMap(Vehicle::getId, Vehicle::getPlate));
        List<FleetAnalysis.CostLine> costs = costRepo.totals(VehicleCostStatus.POSTED, from, to).stream()
                .filter(r -> vehicleId == null || vehicleId.equals(r[0]))
                .map(r -> new FleetAnalysis.CostLine((UUID) r[0], plates.get((UUID) r[0]), ((VehicleCostCategory) r[1]).group(),
                        (BigDecimal) r[2], (BigDecimal) r[3])).toList();
        List<FleetAnalysis.VehicleRow> byVehicle = FleetAnalysis.byVehicle(legs, costs);
        List<SalesReportService.Option> vehicleOptions = new ArrayList<>(vehicles);
        costs.stream().map(c -> new SalesReportService.Option(c.vehicleId().toString(), c.plate()))
                .filter(o -> !vehicleOptions.contains(o)).distinct().forEach(vehicleOptions::add);
        vehicleOptions.sort(Comparator.comparing(SalesReportService.Option::label));
        // Loads that left in the period with their vehicle's previous trip closed first, and shortages against the load (Annex D)
        List<FleetAnalysis.Departure> departures = tripRepo.findByDepartedAtBetweenOrderByDepartedAtAsc(from.atStartOfDay(), to.plusDays(1).atStartOfDay())
                .stream().filter(t -> (driverId == null || t.getDriver().getId().equals(driverId))
                        && (vehicleId == null || t.getVehicle().getId().equals(vehicleId)))
                .map(t -> {
                    List<Trip> before = tripRepo.findByVehicle_IdAndDepartedAtBeforeOrderByDepartedAtDesc(t.getVehicle().getId(), t.getDepartedAt());
                    return new FleetAnalysis.Departure(t.getDepartedAt(), !before.isEmpty(), before.isEmpty() ? null : before.get(0).getClosedAt());
                }).toList();
        FleetAnalysis.DriverRow total = FleetAnalysis.total(rows);
        FleetAnalysis.LoadKpis loadKpis = FleetAnalysis.loads(departures, total.loaded(), total.missing(),
                settings.getDecimal(SettingKey.FLEET_SHORTAGE_TOLERANCE_PERCENT));
        return new Report(rows, FleetAnalysis.byDriver(rows), FleetAnalysis.total(rows), discrepancies,
                discrepancies.stream().filter(d -> d.outcome() == null).count(), floats,
                floats.stream().map(FleetAnalysis.FloatRow::amount).reduce(BigDecimal.ZERO, BigDecimal::add), byVehicle,
                FleetAnalysis.totalVehicles(byVehicle), drivers, vehicleOptions, loadKpis);
    }
}
