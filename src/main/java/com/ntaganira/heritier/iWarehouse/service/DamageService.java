package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.config.NumberFormats;
import com.ntaganira.heritier.iWarehouse.dto.DamageReportDto;
import com.ntaganira.heritier.iWarehouse.dto.StockAdjustmentDto;
import com.ntaganira.heritier.iWarehouse.entity.*;
import com.ntaganira.heritier.iWarehouse.enums.*;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.CustomerRepository;
import com.ntaganira.heritier.iWarehouse.repository.DamageReportRepository;
import com.ntaganira.heritier.iWarehouse.repository.DriverRepository;
import com.ntaganira.heritier.iWarehouse.repository.StockUnitRepository;
import com.ntaganira.heritier.iWarehouse.repository.SupplierRepository;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
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
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : DamageService.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Damage reports (IMP 13, F-07, DMG-009, PF-07; decision D5). Reported by the warehouse or sales
 *               (PERM_REPORT_DAMAGE) with the units by label: glass on a rack, held by nothing; the report holds them
 *               until it is decided (damaged glass isolated: nothing sells, loads, cuts or moves it). Inspected by
 *               Dispatch when the Settings ask (PERM_INSPECT_DAMAGE, not the reporter); approved or rejected by the Director
 *               of Supply Chain (PERM_APPROVE_DAMAGE, neither the reporter nor the inspector) within the Delegation of
 *               Authority for stock write-offs (the estimated loss). Approving writes the units decided so off on one
 *               adjustment, already approved (StockAdjustmentService.createApproved), puts the others back, and posts what
 *               a customer, an employee, an insurer, a supplier or a driver pays back (PostingService.damageRecovery).
 *               Inspectors or approvers are told of each report; ALERT_DAMAGE holders (the Internal Controller, Security)
 *               the same day of a loss at or above the Settings' value (the Board's tolerance).
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class DamageService {

    private final DamageReportRepository repo;
    private final StockUnitRepository unitRepo;
    private final StockService stockService;
    private final StockAdjustmentService adjustmentService;
    private final PostingService postingService;
    private final DocumentNumberService numbers;
    private final SettingService settings;
    private final Notifier notifier;
    private final NumberFormats num;
    private final DelegationOfAuthority authority;
    private final CustomerRepository customerRepo;
    private final SupplierRepository supplierRepo;
    private final DriverRepository driverRepo;
    private final Clock clock;

    public DamageService(DamageReportRepository repo, StockUnitRepository unitRepo, StockService stockService,
                         StockAdjustmentService adjustmentService, PostingService postingService, DocumentNumberService numbers,
                         SettingService settings, Notifier notifier, NumberFormats num, DelegationOfAuthority authority,
                         CustomerRepository customerRepo, SupplierRepository supplierRepo, DriverRepository driverRepo, Clock clock) {
        this.repo = repo;
        this.unitRepo = unitRepo;
        this.stockService = stockService;
        this.adjustmentService = adjustmentService;
        this.postingService = postingService;
        this.numbers = numbers;
        this.settings = settings;
        this.notifier = notifier;
        this.num = num;
        this.authority = authority;
        this.customerRepo = customerRepo;
        this.supplierRepo = supplierRepo;
        this.driverRepo = driverRepo;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- reading

    /** status: a DamageStatus name, "waiting" for reported and inspected, or empty for all. */
    public Page<DamageReport> findPage(String search, String status, int page, int size) {
        Specification<DamageReport> spec = (root, query, cb) -> {
            Predicate p = cb.conjunction();
            if (StringUtils.hasText(search)) {
                String term = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                p = cb.and(p, cb.or(cb.like(cb.lower(root.get("number")), term), cb.like(cb.lower(root.get("description")), term),
                        cb.like(cb.lower(root.get("reportedBy")), term), cb.like(cb.lower(root.get("location").get("code")), term)));
            }
            if ("waiting".equals(status)) {
                p = cb.and(p, root.get("status").in(DamageStatus.REPORTED, DamageStatus.INSPECTED));
            } else if (StringUtils.hasText(status)) {
                try {
                    p = cb.and(p, cb.equal(root.get("status"), DamageStatus.valueOf(status)));
                } catch (IllegalArgumentException e) {
                    // unknown status: no filter
                }
            }
            return p;
        };
        return repo.findAll(spec, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "reportedAt").and(Sort.by(Sort.Direction.DESC, "number"))));
    }

    public DamageReport findDetailed(UUID id) {
        return repo.findDetailedById(id).orElseThrow(() -> new NotFoundException("DamageReport", id));
    }

    /** Units per report, for a list. */
    public Map<UUID, Long> unitCounts(Collection<UUID> ids) {
        Map<UUID, Long> counts = new HashMap<>();
        if (!ids.isEmpty()) {
            repo.countLines(ids).forEach(r -> counts.put((UUID) r[0], ((Number) r[1]).longValue()));
        }
        return counts;
    }

    public long waitingCount() {
        return repo.countByStatusIn(List.of(DamageStatus.REPORTED, DamageStatus.INSPECTED));
    }

    public boolean inspectionRequired() {
        return settings.getBoolean(SettingKey.DAMAGE_INSPECTION);
    }

    /** The office its loss needs under the Delegation of Authority (stock write-offs), for the page. */
    public DelegationOfAuthority.Check authorityOf(DamageReport report) {
        return authority.check(AuthorityDecision.STOCK_ADJUSTMENT, report.getEstimatedLoss());
    }

    /** Units by id (the lines' current state, for the page). */
    public Map<UUID, StockUnit> unitsOf(DamageReport report) {
        List<UUID> ids = report.getLines().stream().map(DamageReportLine::getStockUnitId).toList();
        return ids.isEmpty() ? Map.of() : unitRepo.findByIdIn(ids).stream().collect(Collectors.toMap(StockUnit::getId, Function.identity()));
    }

    // ---------------------------------------------------------------- reporting

    @Transactional
    public DamageReport report(DamageReportDto dto) {
        Map<UUID, Location> byId = stockService.locationsById();
        Location place = dto.getLocationId() == null ? null : byId.get(dto.getLocationId());
        if (place == null || !place.isEnabled() || place.getType() == LocationType.VEHICLE) {
            throw BusinessException.onField("locationId", "damage.location.required");
        }
        LocalDate today = LocalDate.now(clock);
        if (dto.getOccurredOn() == null || dto.getOccurredOn().isAfter(today)) {
            throw BusinessException.onField("occurredOn", "damage.occurredOn.future");
        }
        String description = PartyRules.clean(dto.getDescription());
        if (description == null) {
            throw BusinessException.onField("description", "damage.description.required");
        }
        List<String> codes = StockTransferService.parseCodes(dto.getCodes());
        if (codes.isEmpty()) {
            throw BusinessException.onField("codes", "damage.codes.required");
        }
        Map<String, StockUnit> units = unitRepo.findByCodeIn(codes).stream().collect(Collectors.toMap(StockUnit::getCode, Function.identity()));
        Map<UUID, String> holds = stockService.holds(units.values().stream().map(StockUnit::getId).toList());
        List<StockUnit> damaged = new ArrayList<>();
        for (String code : new LinkedHashSet<>(codes)) {
            StockUnit unit = units.get(code);
            if (unit == null) {
                throw BusinessException.onField("codes", "damage.unit.unknown", code);
            }
            if (!StockCounting.ON_RACK.contains(unit.getStatus())) {
                throw BusinessException.onField("codes", "damage.unit.notInStock", code,
                        new org.springframework.context.support.DefaultMessageSourceResolvable("stock.status." + unit.getStatus()));
            }
            if (holds.containsKey(unit.getId())) {
                throw BusinessException.onField("codes", "damage.unit.held", code, holds.get(unit.getId()));
            }
            damaged.add(unit);
        }
        BigDecimal loss = damaged.stream().map(u -> u.getUnitCost() == null ? BigDecimal.ZERO : u.getUnitCost())
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2);

        DamageReport report = new DamageReport();
        report.setNumber(numbers.next(DocumentType.DAMAGE_REPORT));
        report.setLocation(place);
        report.setOccurredOn(dto.getOccurredOn());
        report.setCause(dto.getCause() == null ? DamageCause.UNKNOWN : dto.getCause());
        report.setDescription(description);
        report.setPeoplePresent(PartyRules.clean(dto.getPeoplePresent()));
        report.setRepairable(dto.isRepairable());
        report.setEstimatedLoss(loss);
        report.setCreditNoteId(dto.getCreditNoteId());
        recovery(report, dto, loss);
        AppUserPrincipal me = AppUserPrincipal.current().orElseThrow(() -> BusinessException.of("till.noUser"));
        report.setReportedBy(me.getUsername());
        report.setReportedById(me.getId());
        report.setReportedAt(LocalDateTime.now(clock));
        report.setStatus(DamageStatus.REPORTED);
        int no = 0;
        for (StockUnit unit : damaged) {
            DamageReportLine line = new DamageReportLine();
            line.setReport(report);
            line.setLineNo(++no);
            line.setStockUnitId(unit.getId());
            line.setUnitCode(unit.getCode());
            line.setProduct(unit.getProduct());
            line.setLocationId(unit.getLocation() == null ? null : unit.getLocation().getId());
            line.setAreaM2(unit.getAreaM2());
            line.setUnitCost(unit.getUnitCost() == null ? BigDecimal.ZERO.setScale(2) : unit.getUnitCost());
            report.getLines().add(line);
        }
        repo.save(report);

        String link = "/damage/" + report.getId();
        String perm = inspectionRequired() ? "INSPECT_DAMAGE" : "APPROVE_DAMAGE";
        notifier.holders(perm, me.getId(), NotificationKind.DAMAGE, "notify.damage.reported",
                damaged.size() == 1 ? "notify.damage.reportedTextOne" : "notify.damage.reportedText", link,
                report.getNumber(), place.getCode(), String.valueOf(damaged.size()), num.money(loss), me.getUsername());
        if (loss.compareTo(settings.getDecimal(SettingKey.DAMAGE_ALERT_VALUE)) >= 0) {
            notifier.holders("ALERT_DAMAGE", me.getId(), NotificationKind.DAMAGE, "notify.damage.alert", "notify.damage.alertText", link,
                    report.getNumber(), num.money(loss), place.getCode(), report.getOccurredOn().toString());
        }
        return report;
    }

    /** Who may pay the loss back (F-07 C), checked: a customer, a supplier or a driver by record, an employee or insurer by name. */
    private void recovery(DamageReport report, DamageReportDto dto, BigDecimal loss) {
        RecoveryFrom from = dto.getRecoverFrom() == null ? RecoveryFrom.NONE : dto.getRecoverFrom();
        report.setRecoverFrom(from);
        if (from == RecoveryFrom.NONE) {
            return;
        }
        BigDecimal amount = dto.getRecoverAmount();
        if (amount == null || amount.signum() <= 0) {
            throw BusinessException.onField("recoverAmount", "damage.recoverAmount.required");
        }
        if (amount.compareTo(loss) > 0) {
            throw BusinessException.onField("recoverAmount", "damage.recoverAmount.tooMuch", num.money(loss));
        }
        report.setRecoverAmount(amount.setScale(2, java.math.RoundingMode.HALF_UP));
        switch (from) {
            case CUSTOMER -> {
                Customer c = dto.getRecoverCustomerId() == null ? null : customerRepo.findById(dto.getRecoverCustomerId())
                        .filter(Customer::isEnabled).filter(x -> !x.isDefaultCustomer()).orElse(null);
                if (c == null) {
                    throw BusinessException.onField("recoverCustomerId", "damage.recoverCustomer.required");
                }
                report.setRecoverCustomerId(c.getId());
                report.setRecoverParty(c.getName());
            }
            case SUPPLIER -> {
                Supplier s = dto.getRecoverSupplierId() == null ? null : supplierRepo.findById(dto.getRecoverSupplierId()).orElse(null);
                if (s == null) {
                    throw BusinessException.onField("recoverSupplierId", "damage.recoverSupplier.required");
                }
                report.setRecoverSupplierId(s.getId());
                report.setRecoverParty(s.getName());
            }
            case DRIVER -> {
                Driver d = dto.getRecoverDriverId() == null ? null : driverRepo.findDetailedById(dto.getRecoverDriverId()).orElse(null);
                if (d == null) {
                    throw BusinessException.onField("recoverDriverId", "damage.recoverDriver.required");
                }
                report.setRecoverDriverId(d.getId());
                report.setRecoverParty(d.getUser().getFullName());
            }
            default -> {
                String party = PartyRules.clean(dto.getRecoverParty());
                if (party == null) {
                    throw BusinessException.onField("recoverParty", "damage.recoverParty.required");
                }
                report.setRecoverParty(party);
            }
        }
    }

    // ---------------------------------------------------------------- deciding

    /** Dispatch inspects the damage (F-07 B): not whoever reported it. */
    @Transactional
    public DamageReport inspect(UUID id, String note) {
        DamageReport report = lock(id);
        if (report.getStatus() != DamageStatus.REPORTED) {
            throw BusinessException.of("damage.notReported", report.getNumber());
        }
        AppUserPrincipal me = AppUserPrincipal.current().orElseThrow(() -> BusinessException.of("till.noUser"));
        if (me.getId().equals(report.getReportedById())) {
            throw BusinessException.of("damage.ownInspection", report.getNumber());
        }
        String clean = PartyRules.clean(note);
        if (clean == null || clean.length() > 500) {
            throw BusinessException.onField("note", "damage.inspection.note");
        }
        report.setInspectedBy(me.getUsername());
        report.setInspectedById(me.getId());
        report.setInspectionNote(clean);
        report.setInspectedAt(LocalDateTime.now(clock));
        report.setStatus(DamageStatus.INSPECTED);
        notifier.holders("APPROVE_DAMAGE", me.getId(), NotificationKind.DAMAGE, "notify.damage.inspected", "notify.damage.inspectedText",
                "/damage/" + report.getId(), report.getNumber(), me.getUsername());
        return report;
    }

    /**
     * The Director of Supply Chain approves (F-07 B): each unit written off or back to stock (unset: written off). Not the
     * reporter or the inspector; within the Delegation of Authority for the estimated loss.
     */
    @Transactional
    public DamageReport approve(UUID id, Map<UUID, DamageOutcome> outcomes, String note) {
        DamageReport report = lock(id);
        requireDecidable(report);
        AppUserPrincipal me = AppUserPrincipal.current().orElseThrow(() -> BusinessException.of("till.noUser"));
        String clean = PartyRules.clean(note);
        if (clean != null && clean.length() > 500) {
            throw BusinessException.onField("note", "damage.note.size");
        }
        authority.require(AuthorityDecision.STOCK_ADJUSTMENT, report.getEstimatedLoss(), clean);
        List<String> writeOffs = new ArrayList<>();
        for (DamageReportLine line : report.getLines()) {
            DamageOutcome outcome = outcomes == null ? null : outcomes.get(line.getId());
            line.setOutcome(outcome == null ? DamageOutcome.WRITE_OFF : outcome);
            if (line.getOutcome() == DamageOutcome.WRITE_OFF) {
                writeOffs.add(line.getUnitCode());
            }
        }
        // Decided first: the report then no longer holds its units, which the write-off checks
        decided(report, DamageStatus.APPROVED, me, clean);
        if (!writeOffs.isEmpty()) {
            StockAdjustmentDto dto = new StockAdjustmentDto();
            dto.setReason("Damage report " + report.getNumber() + ": " + report.getDescription());
            for (String code : writeOffs) {
                StockAdjustmentDto.Line row = new StockAdjustmentDto.Line();
                row.setKind(AdjustmentKind.WRITE_OFF);
                row.setUnitCode(code);
                row.setCause(WriteOffCause.DAMAGED);
                dto.getLines().add(row);
            }
            if (dto.getReason().length() > 255) {
                dto.setReason(dto.getReason().substring(0, 255));
            }
            StockAdjustment adjustment = adjustmentService.createApproved(dto, report.getReportedBy(), report.getReportedById(),
                    "Approved on " + report.getNumber());
            report.setAdjustmentId(adjustment.getId());
            report.setAdjustmentNumber(adjustment.getNumber());
        }
        if (report.isRecoverable()) {
            report.setRecoveryJournal(postingService.damageRecovery(report).getNumber());
        }
        notifier.user(report.getReportedById(), NotificationKind.DECISION, "notify.damage.approved", "notify.decidedBy",
                "/damage/" + report.getId(), report.getNumber(), me.getUsername());
        return report;
    }

    /** Rejected with a reason (not damage after all): its units are released, nothing changes in stock. */
    @Transactional
    public DamageReport reject(UUID id, String reason) {
        DamageReport report = lock(id);
        requireDecidable(report);
        AppUserPrincipal me = AppUserPrincipal.current().orElseThrow(() -> BusinessException.of("till.noUser"));
        decided(report, DamageStatus.REJECTED, me, reason(reason));
        notifier.user(report.getReportedById(), NotificationKind.DECISION, "notify.damage.rejected", "notify.rejectedBy",
                "/damage/" + report.getId(), report.getNumber(), me.getUsername(), report.getDecisionNote());
        return report;
    }

    /** Whoever reported it cancels it, with a reason, while it waits. */
    @Transactional
    public DamageReport cancel(UUID id, String reason) {
        DamageReport report = lock(id);
        if (!report.isOpen()) {
            throw BusinessException.of("damage.notOpen", report.getNumber());
        }
        AppUserPrincipal me = AppUserPrincipal.current().orElseThrow(() -> BusinessException.of("till.noUser"));
        if (!me.getId().equals(report.getReportedById())) {
            throw BusinessException.of("damage.cancel.notReporter", report.getNumber());
        }
        decided(report, DamageStatus.CANCELLED, me, reason(reason));
        return report;
    }

    private void requireDecidable(DamageReport report) {
        if (!report.isOpen()) {
            throw BusinessException.of("damage.notOpen", report.getNumber());
        }
        if (inspectionRequired() && report.getStatus() != DamageStatus.INSPECTED) {
            throw BusinessException.of("damage.notInspected", report.getNumber());
        }
        Long me = AppUserPrincipal.current().map(AppUserPrincipal::getId).orElse(null);
        if (me != null && (me.equals(report.getReportedById()) || me.equals(report.getInspectedById()))) {
            throw BusinessException.of("damage.ownDecision", report.getNumber());
        }
    }

    private static String reason(String reason) {
        String clean = PartyRules.clean(reason);
        if (clean == null || clean.length() > 500) {
            throw BusinessException.onField("reason", "damage.reason.required");
        }
        return clean;
    }

    private void decided(DamageReport report, DamageStatus status, AppUserPrincipal me, String note) {
        report.setDecidedBy(me.getUsername());
        report.setDecidedById(me.getId());
        report.setDecidedAt(LocalDateTime.now(clock));
        report.setDecisionNote(note);
        report.setStatus(status);
    }

    private DamageReport lock(UUID id) {
        repo.lockById(id).orElseThrow(() -> new NotFoundException("DamageReport", id));
        return findDetailed(id);
    }
}
