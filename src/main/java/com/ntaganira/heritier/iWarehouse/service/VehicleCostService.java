package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.enums.SettingKey;
import com.ntaganira.heritier.iWarehouse.dto.VehicleCostDto;
import com.ntaganira.heritier.iWarehouse.entity.*;
import com.ntaganira.heritier.iWarehouse.enums.*;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.*;
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
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : VehicleCostService.java
 * - Date      : 2026. 10. 12.
 * - User      : Hntaganira
 * - Desc      : Vehicle costs (owner, 2026-10-12): fuel, services, repairs, spare parts, tyres, washing, parking. Recorded
 *               for an active vehicle (and a trip of it), dated up to today in an open month (VC-WH-2026-000001). Within
 *               the VEHICLE_COST_APPROVAL_LIMIT setting it posts at once (PostingService.vehicleCost), above it another
 *               person with APPROVE_VEHICLE_COST approves it, never who recorded it, or rejects it; who recorded it may
 *               withdraw it. Paid from the vault, the bank or mobile money (with the reference), owed to an RWF garage
 *               (its supplier account), or by the trip's driver from its cash: at most what is left of the trip's cash
 *               sales, while its float is not cleared (FloatService then expects that much less). A cost may do a service
 *               plan of its vehicle: posting it moves the plan's last time. A posted cost is reversed with a reason
 *               (JournalService.reverse), never edited. Deciding and reversing lock it first.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class VehicleCostService {

    private static final java.time.format.DateTimeFormatter DAY_FMT = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final List<VehicleCostStatus> COMMITTED = List.of(VehicleCostStatus.PENDING_APPROVAL, VehicleCostStatus.POSTED);
    private static final List<TripStatus> OPEN_TRIPS = List.of(TripStatus.PLANNED, TripStatus.DEPARTED, TripStatus.RETURNED);

    private final VehicleCostRepository repo;
    private final VehicleRepository vehicleRepo;
    private final TripRepository tripRepo;
    private final SupplierRepository supplierRepo;
    private final CurrencyRepository currencyRepo;
    private final VehicleServicePlanRepository planRepo;
    private final FloatClearanceRepository clearanceRepo;
    private final FloatService floatService;
    private final PostingService postingService;
    private final JournalService journalService;
    private final DocumentNumberService numbers;
    private final PeriodLock periods;
    private final SettingService settings;
    private final Notifier notifier;
    private final Clock clock;
    private final DelegationOfAuthority authority;

    public VehicleCostService(VehicleCostRepository repo, VehicleRepository vehicleRepo, TripRepository tripRepo, SupplierRepository supplierRepo,
                              CurrencyRepository currencyRepo, VehicleServicePlanRepository planRepo, FloatClearanceRepository clearanceRepo,
                              FloatService floatService, PostingService postingService, JournalService journalService,
                              DocumentNumberService numbers, PeriodLock periods, SettingService settings, Notifier notifier, Clock clock,
                              DelegationOfAuthority authority) {
        this.authority = authority;
        this.repo = repo;
        this.vehicleRepo = vehicleRepo;
        this.tripRepo = tripRepo;
        this.supplierRepo = supplierRepo;
        this.currencyRepo = currencyRepo;
        this.planRepo = planRepo;
        this.clearanceRepo = clearanceRepo;
        this.floatService = floatService;
        this.postingService = postingService;
        this.journalService = journalService;
        this.numbers = numbers;
        this.periods = periods;
        this.settings = settings;
        this.notifier = notifier;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- reading

    public Page<VehicleCost> findPage(String search, String status, UUID vehicleId, String category, int page, int size) {
        Specification<VehicleCost> spec = (root, query, cb) -> {
            Predicate p = cb.conjunction();
            if (StringUtils.hasText(search)) {
                String term = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                var supplier = root.join("supplier", JoinType.LEFT);
                var vehicle = root.join("vehicle", JoinType.LEFT);
                p = cb.and(p, cb.or(
                        cb.like(cb.lower(root.get("number")), term),
                        cb.like(cb.lower(root.get("description")), term),
                        cb.like(cb.lower(root.get("supplierRef")), term),
                        cb.like(cb.lower(supplier.get("name")), term),
                        cb.like(cb.lower(vehicle.get("plate")), term)));
            }
            VehicleCostStatus s = parse(VehicleCostStatus.class, status);
            if (s != null) {
                p = cb.and(p, cb.equal(root.get("status"), s));
            }
            VehicleCostCategory c = parse(VehicleCostCategory.class, category);
            if (c != null) {
                p = cb.and(p, cb.equal(root.get("category"), c));
            }
            if (vehicleId != null) {
                p = cb.and(p, cb.equal(root.get("vehicle").get("id"), vehicleId));
            }
            return p;
        };
        return repo.findAll(spec, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "costDate").and(Sort.by(Sort.Direction.DESC, "number"))));
    }

    public VehicleCost findDetailed(UUID id) {
        return repo.findDetailedById(id).orElseThrow(() -> new NotFoundException("VehicleCost", id));
    }

    public List<VehicleCost> ofTrip(UUID tripId) {
        return repo.findByTrip_IdOrderByCostDateDescNumberDesc(tripId);
    }

    public Page<VehicleCost> ofVehicle(UUID vehicleId, int page, int size) {
        return repo.findByVehicle_Id(vehicleId, PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "costDate").and(Sort.by(Sort.Direction.DESC, "number"))));
    }

    /** Its journal and, once reversed, the reversal. */
    public List<JournalEntry> journals(VehicleCost cost) {
        List<JournalEntry> journals = new ArrayList<>();
        if (cost.getJournalId() != null) {
            journals.add(journalService.findById(cost.getJournalId()));
        }
        if (cost.getReversalJournalId() != null) {
            journals.add(journalService.findById(cost.getReversalJournalId()));
        }
        return journals;
    }

    public long pendingCount() {
        return repo.countByStatus(VehicleCostStatus.PENDING_APPROVAL);
    }

    /** What the cost needs from the Delegation of Authority and whether the signed-in user may approve it. */
    public DelegationOfAuthority.Check authorityOf(VehicleCost cost) {
        return authority.check(AuthorityDecision.VEHICLE_COST, cost.getAmount());
    }

    public BigDecimal approvalLimit() {
        return settings.getDecimal(SettingKey.VEHICLE_COST_APPROVAL_LIMIT);
    }

    public boolean isRequester(VehicleCost cost) {
        Optional<AppUserPrincipal> user = AppUserPrincipal.current();
        if (user.isPresent() && cost.getRequestedById() != null) {
            return cost.getRequestedById().equals(user.get().getId());
        }
        return cost.getRequestedBy().equals(user.map(AppUserPrincipal::getUsername).orElse("system"));
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    /** The posted costs of a trip and the litres of its fuel: (amount with VAT, litres). */
    public record TripTotals(BigDecimal amount, BigDecimal litres, long count) {
    }

    public TripTotals tripTotals(List<VehicleCost> costs) {
        BigDecimal amount = BigDecimal.ZERO;
        BigDecimal litres = BigDecimal.ZERO;
        long count = 0;
        for (VehicleCost c : costs) {
            if (c.isPosted()) {
                amount = amount.add(c.getAmount());
                litres = litres.add(c.getLitres() == null ? BigDecimal.ZERO : c.getLitres());
                count++;
            }
        }
        return new TripTotals(amount, litres, count);
    }

    /** What the drivers paid from some trips' cash, posted (FloatService expects that much less cash back). */
    public Map<UUID, BigDecimal> paidFromFloat(Collection<UUID> tripIds) {
        return sums(tripIds, List.of(VehicleCostStatus.POSTED));
    }

    /** What is left of a trip's cash for its driver to pay costs from (its cash sales less what costs took, waiting or posted). */
    public BigDecimal floatLeft(UUID tripId) {
        BigDecimal banked = floatService.bankedOf(List.of(tripId)).getOrDefault(tripId, BigDecimal.ZERO);
        return VehicleCosts.floatLeft(floatService.cashOf(tripId).subtract(banked), sums(List.of(tripId), COMMITTED).getOrDefault(tripId, BigDecimal.ZERO));
    }

    private Map<UUID, BigDecimal> sums(Collection<UUID> tripIds, Collection<VehicleCostStatus> statuses) {
        Map<UUID, BigDecimal> paid = new HashMap<>();
        if (!tripIds.isEmpty()) {
            for (Object[] row : repo.paidFromFloat(tripIds, VehicleCostPayment.DRIVER_FLOAT, statuses)) {
                paid.put((UUID) row[0], (BigDecimal) row[1]);
            }
        }
        return paid;
    }

    // ---------------------------------------------------------------- the form

    /** A new form for today: a vehicle or a trip given (from their pages) fill it in; a trip on the road suggests its float. */
    public VehicleCostDto newForm(UUID vehicleId, UUID tripId) {
        VehicleCostDto dto = new VehicleCostDto();
        dto.setCostDate(today());
        dto.setVehicleId(vehicleId);
        dto.setPayment(VehicleCostPayment.CASH_VAULT);
        if (tripId != null) {
            tripRepo.findById(tripId).ifPresent(t -> {
                dto.setTripId(t.getId());
                dto.setVehicleId(t.getVehicle().getId());
                if (t.isDeparted()) {
                    dto.setPayment(VehicleCostPayment.DRIVER_FLOAT);
                }
            });
        }
        return dto;
    }

    public List<Vehicle> vehicles() {
        return vehicleRepo.findByEnabledTrueOrderByPlate();
    }

    /** Trips a cost may be for: planned, on the road or back with their float not cleared, and the one already chosen. */
    public List<Trip> trips(UUID chosen) {
        List<Trip> trips = new ArrayList<>(tripRepo.findNotCleared(OPEN_TRIPS));
        if (chosen != null && trips.stream().noneMatch(t -> t.getId().equals(chosen))) {
            tripRepo.findById(chosen).ifPresent(t -> trips.add(0, t));
        }
        return trips;
    }

    /** Garages and stations a cost may be owed to: active suppliers that invoice in RWF, by name. */
    public List<Supplier> garages() {
        String base = baseCurrency();
        return supplierRepo.findAll(Sort.by("name")).stream().filter(s -> s.isEnabled() && base.equals(s.getCurrencyCode())).toList();
    }

    /** The active service plans of every vehicle, by name (the form shows those of the chosen vehicle). */
    public List<VehicleServicePlan> plans() {
        return planRepo.findByEnabledTrueOrderByName();
    }

    // ---------------------------------------------------------------- recording

    /** Records a cost: checked, numbered and saved; posted at once within the approval limit, otherwise the approvers are told. */
    @Transactional
    public VehicleCost record(VehicleCostDto dto) {
        Vehicle vehicle = vehicleRepo.findById(dto.getVehicleId()).orElseThrow(() -> new NotFoundException("Vehicle", dto.getVehicleId()));
        if (!vehicle.isEnabled()) {
            throw BusinessException.onField("vehicleId", "vehicleCost.vehicle.disabled", vehicle.getPlate());
        }
        if (dto.getCostDate().isAfter(today())) {
            throw BusinessException.onField("costDate", "vehicleCost.date.future");
        }
        periods.requireOpen(dto.getCostDate(), "costDate");
        Trip trip = null;
        if (dto.getTripId() != null) {
            trip = tripRepo.findById(dto.getTripId()).orElseThrow(() -> new NotFoundException("Trip", dto.getTripId()));
            if (!trip.getVehicle().getId().equals(vehicle.getId())) {
                throw BusinessException.onField("tripId", "vehicleCost.trip.otherVehicle", trip.getNumber(), trip.getVehicle().getPlate());
            }
            if (trip.isCancelled()) {
                throw BusinessException.onField("tripId", "vehicleCost.trip.cancelled", trip.getNumber());
            }
        }
        // Today's reading cannot be below the latest one, of the trips or of another cost
        Integer lastKm = dto.getOdometerKm() != null && dto.getCostDate().equals(today()) ? currentKm(vehicle) : null;
        if (lastKm != null && dto.getOdometerKm() < lastKm) {
            throw BusinessException.onField("odometerKm", "vehicleCost.odometer.belowLast", dto.getOdometerKm(), lastKm);
        }
        BigDecimal amount = dto.getAmount();
        BigDecimal vat = dto.getVatAmount() == null ? BigDecimal.ZERO : dto.getVatAmount();
        if (vat.compareTo(VehicleCosts.maxVat(amount)) > 0) {
            throw BusinessException.onField("vatAmount", "vehicleCost.vat.tooHigh", VehicleCosts.maxVat(amount), amount);
        }
        VehicleCostPayment payment = dto.getPayment();
        String paymentRef = clean(dto.getPaymentRef());
        if (payment.needsReference() && paymentRef == null) {
            throw BusinessException.onField("paymentRef", "vehicleCost.reference.required");
        }
        Supplier supplier = null;
        if (dto.getSupplierId() != null) {
            supplier = supplierRepo.findById(dto.getSupplierId()).orElseThrow(() -> new NotFoundException("Supplier", dto.getSupplierId()));
            if (!supplier.isEnabled()) {
                throw BusinessException.onField("supplierId", "vehicleCost.supplier.disabled", supplier.getName());
            }
            if (!baseCurrency().equals(supplier.getCurrencyCode())) {
                throw BusinessException.onField("supplierId", "vehicleCost.supplier.currency", supplier.getName(), supplier.getCurrencyCode());
            }
        }
        if (payment == VehicleCostPayment.CREDIT && supplier == null) {
            throw BusinessException.onField("supplierId", "vehicleCost.supplier.required");
        }
        Driver driver = null;
        if (payment == VehicleCostPayment.DRIVER_FLOAT) {
            // Decision D9: costs are paid from a float only up to the Settings' day (from then on, by Finance)
            String until = settings.get(SettingKey.FLEET_COSTS_FROM_FLOAT_UNTIL);
            if (until != null && !until.isBlank() && dto.getCostDate().isAfter(LocalDate.parse(until.trim()))) {
                throw BusinessException.onField("payment", "vehicleCost.float.stopped", LocalDate.parse(until.trim()).format(DAY_FMT));
            }
            if (trip == null) {
                throw BusinessException.onField("tripId", "vehicleCost.float.trip");
            }
            tripRepo.lockById(trip.getId());                         // one float spent at a time
            if (!trip.isDeparted() && !trip.isReturned()) {
                throw BusinessException.onField("tripId", "vehicleCost.float.notAway", trip.getNumber());
            }
            if (clearanceRepo.existsByTrip_Id(trip.getId())) {
                throw BusinessException.onField("tripId", "vehicleCost.float.cleared", trip.getNumber());
            }
            BigDecimal left = floatLeft(trip.getId());
            if (amount.compareTo(left) > 0) {
                throw BusinessException.onField("amount", "vehicleCost.float.tooMuch", amount, left, trip.getNumber());
            }
            driver = trip.getDriver();
        }
        VehicleServicePlan plan = null;
        if (dto.getServicePlanId() != null) {
            plan = planRepo.findById(dto.getServicePlanId()).orElseThrow(() -> new NotFoundException("VehicleServicePlan", dto.getServicePlanId()));
            if (!plan.getVehicleId().equals(vehicle.getId())) {
                throw BusinessException.onField("servicePlanId", "vehicleCost.plan.otherVehicle", plan.getName(), vehicle.getPlate());
            }
            if (!plan.isEnabled()) {
                throw BusinessException.onField("servicePlanId", "vehicleCost.plan.disabled", plan.getName());
            }
            if (!dto.getCategory().canDoService()) {
                throw BusinessException.onField("servicePlanId", "vehicleCost.plan.category", plan.getName());
            }
        }

        Optional<AppUserPrincipal> user = AppUserPrincipal.current();
        VehicleCost cost = new VehicleCost();
        cost.setNumber(numbers.next(DocumentType.VEHICLE_COST));
        cost.setVehicle(vehicle);
        cost.setTrip(trip);
        cost.setCostDate(dto.getCostDate());
        cost.setCategory(dto.getCategory());
        cost.setDescription(dto.getDescription().trim());
        cost.setOdometerKm(dto.getOdometerKm());
        cost.setLitres(dto.getCategory() == VehicleCostCategory.FUEL ? dto.getLitres() : null);
        cost.setAmount(amount.setScale(Journal.SCALE));
        cost.setVatAmount(vat.setScale(Journal.SCALE));
        cost.setPayment(payment);
        cost.setPaymentRef(paymentRef == null ? null : paymentRef.toUpperCase(Locale.ROOT));
        cost.setSupplier(supplier);
        cost.setSupplierRef(clean(dto.getSupplierRef()));
        cost.setDriver(driver);
        cost.setServicePlan(plan);
        // Saved waiting; posting sets POSTED with its journal and time together (chk_vehicle_costs_posted)
        cost.setStatus(VehicleCostStatus.PENDING_APPROVAL);
        cost.setRequestedById(user.map(AppUserPrincipal::getId).orElse(null));
        cost.setRequestedBy(user.map(AppUserPrincipal::getUsername).orElse("system"));
        cost.setRequestedAt(LocalDateTime.now(clock));
        repo.save(cost);
        if (VehicleCosts.needsApproval(cost.getAmount(), approvalLimit())) {
            notifier.holders("APPROVE_VEHICLE_COST", cost.getRequestedById(), NotificationKind.APPROVAL, "notify.vehicleCost.waiting",
                    "notify.vehicleCost.waitingText", "/vehicle-costs/" + cost.getId(), cost.getNumber(), cost.getRequestedBy(),
                    vehicle.getPlate(), cost.getDescription());
        } else {
            post(cost);
        }
        return cost;
    }

    // ---------------------------------------------------------------- deciding

    /** Another person approves it: it posts on its own day. */
    @Transactional
    public VehicleCost approve(UUID id, String note) {
        VehicleCost cost = lock(id);
        requirePending(cost);
        if (isRequester(cost)) {
            throw BusinessException.of("vehicleCost.ownApproval", cost.getNumber());
        }
        // The office its amount needs (Board Paper Annex E: operating expenditure)
        authority.require(AuthorityDecision.VEHICLE_COST, cost.getAmount(), PartyRules.clean(note));
        if (cost.getPayment() == VehicleCostPayment.DRIVER_FLOAT && clearanceRepo.existsByTrip_Id(cost.getTrip().getId())) {
            throw BusinessException.of("vehicleCost.float.cleared", cost.getTrip().getNumber());
        }
        decide(cost, VehicleCostStatus.PENDING_APPROVAL, PartyRules.clean(note));
        post(cost);
        notifier.user(cost.getRequestedById(), NotificationKind.DECISION, "notify.vehicleCost.approved", "notify.decidedBy",
                "/vehicle-costs/" + cost.getId(), cost.getNumber(), cost.getDecidedBy());
        return cost;
    }

    /** Another person rejects it, with a reason: nothing is posted. */
    @Transactional
    public VehicleCost reject(UUID id, String reason) {
        VehicleCost cost = lock(id);
        requirePending(cost);
        if (isRequester(cost)) {
            throw BusinessException.of("vehicleCost.ownReject", cost.getNumber());
        }
        decide(cost, VehicleCostStatus.REJECTED, reason.trim());
        notifier.user(cost.getRequestedById(), NotificationKind.DECISION, "notify.vehicleCost.rejected", "notify.rejectedBy",
                "/vehicle-costs/" + cost.getId(), cost.getNumber(), cost.getDecidedBy(), cost.getDecisionNote());
        return cost;
    }

    /** Who recorded it withdraws it, with a reason. */
    @Transactional
    public VehicleCost cancel(UUID id, String reason) {
        VehicleCost cost = lock(id);
        requirePending(cost);
        if (!isRequester(cost)) {
            throw BusinessException.of("vehicleCost.notYours", cost.getNumber(), cost.getRequestedBy());
        }
        decide(cost, VehicleCostStatus.CANCELLED, reason.trim());
        return cost;
    }

    // ---------------------------------------------------------------- reversing

    /**
     * Reverses a posted cost: the opposite journal on {@code date} (today when none), from the cost's day to today, in an open
     * month, with a reason. A cost paid from a driver's float is reversed only while that trip's float is not cleared.
     */
    @Transactional
    public VehicleCost reverse(UUID id, LocalDate date, String reason) {
        VehicleCost cost = lock(id);
        if (!cost.isPosted()) {
            throw BusinessException.of("vehicleCost.notPosted", cost.getNumber());
        }
        LocalDate day = date == null ? today() : date;
        if (day.isAfter(today())) {
            throw BusinessException.of("vehicleCost.reversal.future");
        }
        if (day.isBefore(cost.getCostDate())) {
            throw BusinessException.of("vehicleCost.reversal.early", cost.getCostDate().format(DAY));
        }
        periods.requireOpen(day);
        if (cost.getPayment() == VehicleCostPayment.DRIVER_FLOAT && clearanceRepo.existsByTrip_Id(cost.getTrip().getId())) {
            throw BusinessException.of("vehicleCost.reversal.floatCleared", cost.getTrip().getNumber());
        }
        JournalEntry original = journalService.findById(cost.getJournalId());
        JournalEntry reversal = journalService.reverse(original, day, "Reversal of " + cost.getNumber() + ": " + reason.trim());
        cost.setStatus(VehicleCostStatus.REVERSED);
        cost.setReversalJournalId(reversal.getId());
        cost.setReversalDate(day);
        cost.setReversalReason(reason.trim());
        cost.setReversedBy(AppUserPrincipal.currentUsername());
        cost.setReversedAt(LocalDateTime.now(clock));
        return cost;
    }

    // ---------------------------------------------------------------- helpers

    /** Posts its journal, then sets POSTED with the journal and time together; the service plan it does moves forward. */
    private void post(VehicleCost cost) {
        JournalEntry entry = postingService.vehicleCost(cost);
        cost.setJournalId(entry.getId());
        cost.setPostedAt(LocalDateTime.now(clock));
        cost.setStatus(VehicleCostStatus.POSTED);
        VehicleServicePlan plan = cost.getServicePlan();
        if (plan != null && !cost.getCostDate().isBefore(plan.getLastDoneOn())) {
            plan.setLastDoneOn(cost.getCostDate());
            plan.setLastDoneKm(cost.getOdometerKm() != null ? cost.getOdometerKm() : currentKm(cost.getVehicle()));
        }
    }

    /** The vehicle's latest odometer: its trips' last reading or a higher one a cost recorded. */
    public Integer currentKm(Vehicle vehicle) {
        Integer fromCosts = null;
        for (Object[] row : repo.highestOdometers(COMMITTED)) {
            if (vehicle.getId().equals(row[0])) {
                fromCosts = (Integer) row[1];
            }
        }
        return ServicePlans.latest(vehicle.getOdometerKm(), fromCosts);
    }

    /** Every vehicle's highest odometer recorded by its costs. */
    public Map<UUID, Integer> costOdometers() {
        Map<UUID, Integer> km = new HashMap<>();
        for (Object[] row : repo.highestOdometers(COMMITTED)) {
            km.put((UUID) row[0], (Integer) row[1]);
        }
        return km;
    }

    private VehicleCost lock(UUID id) {
        repo.lockById(id).orElseThrow(() -> new NotFoundException("VehicleCost", id));
        return findDetailed(id);
    }

    private static void requirePending(VehicleCost cost) {
        if (!cost.isPending()) {
            throw BusinessException.of("vehicleCost.notPending", cost.getNumber());
        }
    }

    private void decide(VehicleCost cost, VehicleCostStatus status, String note) {
        cost.setStatus(status);
        cost.setDecidedBy(AppUserPrincipal.currentUsername());
        cost.setDecidedAt(LocalDateTime.now(clock));
        cost.setDecisionNote(note);
    }

    private String baseCurrency() {
        return currencyRepo.findByBaseCurrencyTrue().map(com.ntaganira.heritier.iWarehouse.entity.Currency::getCode).orElse("RWF");
    }

    private static String clean(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            return null;                                            // unknown: no filter
        }
    }
}
