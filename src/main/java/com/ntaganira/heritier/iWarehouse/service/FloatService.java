package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.repository.TripDepositRepository;
import com.ntaganira.heritier.iWarehouse.config.NumberFormats;
import com.ntaganira.heritier.iWarehouse.dto.FloatClearanceDto;
import com.ntaganira.heritier.iWarehouse.entity.AlertState;
import com.ntaganira.heritier.iWarehouse.entity.FloatClearance;
import com.ntaganira.heritier.iWarehouse.entity.Trip;
import com.ntaganira.heritier.iWarehouse.enums.*;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.AlertStateRepository;
import com.ntaganira.heritier.iWarehouse.repository.DriverRepository;
import com.ntaganira.heritier.iWarehouse.repository.FloatClearanceRepository;
import com.ntaganira.heritier.iWarehouse.repository.SalesPaymentRepository;
import com.ntaganira.heritier.iWarehouse.repository.TripRepository;
import com.ntaganira.heritier.iWarehouse.repository.VehicleCostRepository;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : FloatService.java
 * - Date      : 2026. 10. 11.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Driver floats (ACC-06, ACC-07). A trip's cash sales sit on its driver's float until the accountant counts
 *               the cash the driver hands over, once the trip is back and its cash declared (MPOS-07): paid into the vault or
 *               the bank, a shortage on the driver's receivable, an overage to Cash Over/Short (FloatClearances,
 *               PostingService.floatClearance; FC-WH-2026-000001, one per trip). What each driver holds is their lines on
 *               Driver Float, never a second balance. A float not cleared some hours after the trip came back (Settings) is
 *               reported to the accountant once (RPT-06).
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class FloatService {

    public static final String ALERT_PREFIX = "FLOAT:";
    private static final List<TripStatus> BACK = List.of(TripStatus.RETURNED, TripStatus.CLOSED);
    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final TripRepository tripRepo;
    private final DriverRepository driverRepo;
    private final FloatClearanceRepository repo;
    private final SalesPaymentRepository paymentRepo;
    private final VehicleCostRepository costRepo;
    private final JournalService journalService;
    private final PostingService postingService;
    private final DocumentNumberService numbers;
    private final LinePricing pricing;
    private final AlertStateRepository stateRepo;
    private final SettingService settings;
    private final Notifier notifier;
    private final NumberFormats num;
    private final Clock clock;
    private final TripDepositRepository depositRepo;

    public FloatService(TripRepository tripRepo, DriverRepository driverRepo, FloatClearanceRepository repo, SalesPaymentRepository paymentRepo,
                        VehicleCostRepository costRepo, JournalService journalService,
                        PostingService postingService, DocumentNumberService numbers, LinePricing pricing, AlertStateRepository stateRepo,
                        SettingService settings, Notifier notifier, NumberFormats num, Clock clock, TripDepositRepository depositRepo) {
        this.depositRepo = depositRepo;
        this.tripRepo = tripRepo;
        this.driverRepo = driverRepo;
        this.repo = repo;
        this.paymentRepo = paymentRepo;
        this.costRepo = costRepo;
        this.journalService = journalService;
        this.postingService = postingService;
        this.numbers = numbers;
        this.pricing = pricing;
        this.stateRepo = stateRepo;
        this.settings = settings;
        this.notifier = notifier;
        this.num = num;
        this.clock = clock;
    }

    /** A trip back whose float is not cleared: the cash it should hand back (its cash sales less what it paid vehicle costs with). */
    public record ToClear(Trip trip, BigDecimal expected) {
    }

    /** Trips back from the road with cash on the float not cleared yet, the oldest return first. */
    public List<ToClear> toClear() {
        List<Trip> trips = tripRepo.findNotCleared(BACK);
        List<UUID> ids = trips.stream().map(Trip::getId).toList();
        Map<UUID, BigDecimal> cash = cashOf(ids);
        Map<UUID, BigDecimal> paid = paidFromFloat(ids);
        Map<UUID, BigDecimal> banked = bankedOf(ids);
        return trips.stream().filter(t -> cash.getOrDefault(t.getId(), BigDecimal.ZERO).signum() > 0)
                .map(t -> new ToClear(t, cash.get(t.getId()).subtract(paid.getOrDefault(t.getId(), BigDecimal.ZERO))
                        .subtract(banked.getOrDefault(t.getId(), BigDecimal.ZERO)))).toList();
    }

    /** What the drivers paid vehicle costs with from some trips' cash, posted (VehicleCostService): no longer on the float. */
    public Map<UUID, BigDecimal> paidFromFloat(Collection<UUID> tripIds) {
        Map<UUID, BigDecimal> paid = new HashMap<>();
        if (!tripIds.isEmpty()) {
            for (Object[] row : costRepo.paidFromFloat(tripIds, VehicleCostPayment.DRIVER_FLOAT, List.of(VehicleCostStatus.POSTED))) {
                paid.put((UUID) row[0], (BigDecimal) row[1]);
            }
        }
        return paid;
    }

    /**
     * The cash a trip's driver should hand back: its cash sales less the vehicle costs paid from them and less what they
     * banked during the trip (BP 7.3 rule 4).
     */
    public BigDecimal expectedOf(UUID tripId) {
        return cashOf(tripId).subtract(paidFromFloat(List.of(tripId)).getOrDefault(tripId, BigDecimal.ZERO))
                .subtract(bankedOf(List.of(tripId)).getOrDefault(tripId, BigDecimal.ZERO));
    }

    /** What each trip's driver banked or sent by mobile money during the trip (TripDepositService): no longer on the float. */
    public Map<UUID, BigDecimal> bankedOf(Collection<UUID> tripIds) {
        Map<UUID, BigDecimal> banked = new HashMap<>();
        if (!tripIds.isEmpty()) {
            for (Object[] row : depositRepo.totalsOfTrips(tripIds)) {
                banked.put((UUID) row[0], (BigDecimal) row[1]);
            }
        }
        return banked;
    }

    /** The cash each trip's sales took: what they put on the driver's float. */
    public Map<UUID, BigDecimal> cashOf(Collection<UUID> tripIds) {
        Map<UUID, BigDecimal> cash = new HashMap<>();
        if (!tripIds.isEmpty()) {
            for (Object[] row : paymentRepo.totalsOfTrips(tripIds, PaymentMethod.CASH)) {
                cash.put((UUID) row[0], (BigDecimal) row[1]);
            }
        }
        return cash;
    }

    public BigDecimal cashOf(UUID tripId) {
        return cashOf(List.of(tripId)).getOrDefault(tripId, BigDecimal.ZERO);
    }

    public Page<FloatClearance> clearances(int page, int size) {
        return repo.findAllByOrderByClearedAtDesc(PageRequest.of(page, size));
    }

    public FloatClearance findDetailed(UUID id) {
        return repo.findDetailedById(id).orElseThrow(() -> new NotFoundException("FloatClearance", id));
    }

    public Optional<FloatClearance> ofTrip(UUID tripId) {
        return repo.findByTrip_Id(tripId);
    }

    /** What each driver holds on their float now (the ledger). */
    public Map<UUID, BigDecimal> floats() {
        return journalService.driverBalances(AccountKey.DRIVER_FLOAT);
    }

    /** What each driver owes for shortages (cash short, units charged). */
    public Map<UUID, BigDecimal> shortages() {
        return journalService.driverBalances(AccountKey.DRIVER_SHORTAGE);
    }

    /** A driver who holds cash or owes shortages: from the ledger. */
    public record DriverRow(UUID driverId, String name, String username, BigDecimal floatBalance, BigDecimal shortage) {
    }

    /** Every driver whose float or shortage account is not nil, by name. */
    public List<DriverRow> drivers() {
        Map<UUID, BigDecimal> floats = floats();
        Map<UUID, BigDecimal> shortages = shortages();
        Set<UUID> ids = new HashSet<>();
        floats.forEach((id, v) -> { if (v.signum() != 0) ids.add(id); });
        shortages.forEach((id, v) -> { if (v.signum() != 0) ids.add(id); });
        List<DriverRow> rows = new ArrayList<>();
        for (com.ntaganira.heritier.iWarehouse.entity.Driver d : driverRepo.findAllById(ids)) {
            rows.add(new DriverRow(d.getId(), d.getUser().getFullName(), d.getUsername(), floats.getOrDefault(d.getId(), BigDecimal.ZERO),
                    shortages.getOrDefault(d.getId(), BigDecimal.ZERO)));
        }
        rows.sort(Comparator.comparing(DriverRow::name, String.CASE_INSENSITIVE_ORDER));
        return rows;
    }

    /** Clears a trip's float (ACC-06, ACC-07): back from the road, its cash declared, not cleared before. */
    @Transactional
    public FloatClearance clear(UUID tripId, FloatClearanceDto dto) {
        tripRepo.lockById(tripId).orElseThrow(() -> new NotFoundException("Trip", tripId));
        Trip trip = tripRepo.findDetailedById(tripId).orElseThrow(() -> new NotFoundException("Trip", tripId));
        if (!trip.isReturned()) {
            throw BusinessException.of("float.trip.notBack", trip.getNumber());
        }
        if (repo.existsByTrip_Id(tripId)) {
            throw BusinessException.of("float.cleared", trip.getNumber());
        }
        if (!trip.isCashDeclared()) {
            throw BusinessException.of("float.notDeclared", trip.getNumber(), trip.getDriver().getUser().getFullName());
        }
        if (costRepo.existsByTrip_IdAndPaymentAndStatus(tripId, VehicleCostPayment.DRIVER_FLOAT, VehicleCostStatus.PENDING_APPROVAL)) {
            throw BusinessException.of("float.costWaiting", trip.getNumber());
        }
        int decimals = pricing.baseDecimals();
        if (dto.getCounted().stripTrailingZeros().scale() > decimals) {
            throw BusinessException.onField("counted", "float.counted.decimals", decimals);
        }
        if (dto.getDepositAccount() != AccountKey.CASH_VAULT && dto.getDepositAccount() != AccountKey.BANK) {
            throw BusinessException.onField("depositAccount", "float.account.required");
        }
        String reference = StringUtils.hasText(dto.getReference()) ? dto.getReference().trim().toUpperCase(Locale.ROOT) : null;
        if (dto.getDepositAccount() == AccountKey.BANK && reference == null) {
            throw BusinessException.onField("reference", "float.reference.bank");
        }
        BigDecimal expected = expectedOf(tripId);
        BigDecimal counted = dto.getCounted();
        FloatClearances.Split split = FloatClearances.split(expected, counted);

        FloatClearance c = new FloatClearance();
        c.setNumber(numbers.next(DocumentType.FLOAT_CLEARANCE));
        c.setTrip(trip);
        c.setDriver(trip.getDriver());
        c.setExpected(expected);
        c.setDeclared(trip.getCashDeclared());
        c.setCounted(counted);
        c.setShortage(split.shortage());
        c.setOverage(split.overage());
        c.setDepositAccount(dto.getDepositAccount());
        c.setReference(reference);
        c.setNote(StringUtils.hasText(dto.getNote()) ? dto.getNote().trim() : null);
        c.setClearedAt(LocalDateTime.now(clock));
        c.setClearedBy(AppUserPrincipal.currentUsername());
        repo.save(c);
        postingService.floatClearance(c, trip.getNumber());
        stateRepo.findById(ALERT_PREFIX + tripId).filter(s -> s.getClearedAt() == null).ifPresent(s -> s.setClearedAt(LocalDateTime.now(clock)));
        return c;
    }

    /** Tells the accountants once about each float not cleared within the Settings' hours of its trip's return (RPT-06). */
    @Scheduled(fixedDelayString = "${app.alerts.float-every:PT30M}", initialDelayString = "${app.alerts.first-check:PT1M}")
    @Transactional
    public int checkFloats() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime cutoff = now.minusHours(settings.getInt(SettingKey.FLOAT_ALERT_HOURS));
        Map<String, AlertState> raised = new HashMap<>();
        stateRepo.findByKeyStartingWithAndClearedAtIsNull(ALERT_PREFIX).forEach(s -> raised.put(s.getKey(), s));
        int count = 0;
        for (ToClear due : toClear()) {
            Trip trip = due.trip();
            if (trip.getReturnedAt() == null || trip.getReturnedAt().isAfter(cutoff)) {
                continue;
            }
            String key = ALERT_PREFIX + trip.getId();
            if (raised.remove(key) != null) {
                continue;                                           // told already
            }
            AlertState state = stateRepo.findById(key).orElseGet(AlertState::new);
            state.setKey(key);
            state.setRaisedAt(now);
            state.setClearedAt(null);
            stateRepo.save(state);
            notifier.holders("ALERT_FLOAT", null, NotificationKind.EOD, "notify.eod.float.title", "notify.eod.float.message", "/floats",
                    trip.getDriver().getUser().getFullName(), trip.getNumber(), num.money(due.expected()), trip.getReturnedAt().format(DAY_TIME));
            count++;
        }
        raised.values().forEach(s -> s.setClearedAt(now));          // cleared meanwhile
        return count;
    }
}
