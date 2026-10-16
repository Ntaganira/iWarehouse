package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.*;
import com.ntaganira.heritier.iWarehouse.enums.*;
import com.ntaganira.heritier.iWarehouse.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : DamageRegisterService.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : The damage register (IMP 13.2, DMG-009): every damage of a period in one list, wherever it was recorded:
 *               damage reports (what an approval wrote off; those waiting listed with their estimate, rejected ones left
 *               out), breakage reported on trips (accepted; waiting listed), glass broken while cutting, damage written off
 *               on an adjustment that no damage report made, and customers' returns to cullet. The totals count decided
 *               damage only. Each row: the day, where it
 *               comes from, its document, the place, the units, the m², the value at cost, the cause and where it stands.
 *               Reads documents only.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class DamageRegisterService {

    /** Where a row of the register comes from. */
    public enum Source { DAMAGE_REPORT, TRIP_BREAKAGE, CUTTING, WRITE_OFF, CUSTOMER_RETURN }

    /** One damage: cause and status are message keys' suffixes (damage.cause.*, damage.status.*, breakage.status.*) or text. */
    /**
     * A row: its day, source, document, place, units, m², value at cost, cause and status. Rows still waiting for a decision
     * (a damage report or trip breakage not decided yet) show their estimate but are not counted in the totals.
     */
    public record Row(LocalDate day, Source source, String number, String link, String place, int units, BigDecimal areaM2,
                      BigDecimal value, String causeKey, String cause, String statusKey, boolean counted) {
    }

    /** A source's total. */
    public record Total(Source source, int rows, int units, BigDecimal areaM2, BigDecimal value) {
    }

    public record Register(LocalDate from, LocalDate to, List<Row> rows, List<Total> totals, Total total) {
    }

    private final DamageReportRepository damageRepo;
    private final TripBreakageRepository breakageRepo;
    private final CuttingJobOutputRepository outputRepo;
    private final CuttingJobRepository jobRepo;
    private final StockAdjustmentLineRepository adjustmentLineRepo;
    private final CreditNoteRepository creditNoteRepo;
    private final CreditNoteUnitRepository creditNoteUnitRepo;
    private final StockUnitRepository unitRepo;

    public DamageRegisterService(DamageReportRepository damageRepo, TripBreakageRepository breakageRepo, CuttingJobOutputRepository outputRepo,
                                 CuttingJobRepository jobRepo, StockAdjustmentLineRepository adjustmentLineRepo, CreditNoteRepository creditNoteRepo,
                                 CreditNoteUnitRepository creditNoteUnitRepo, StockUnitRepository unitRepo) {
        this.damageRepo = damageRepo;
        this.breakageRepo = breakageRepo;
        this.outputRepo = outputRepo;
        this.jobRepo = jobRepo;
        this.adjustmentLineRepo = adjustmentLineRepo;
        this.creditNoteRepo = creditNoteRepo;
        this.creditNoteUnitRepo = creditNoteUnitRepo;
        this.unitRepo = unitRepo;
    }

    /** The register of a period; source null for every source. */
    public Register register(LocalDate from, LocalDate to, Source source) {
        LocalDateTime start = from.atStartOfDay();
        LocalDateTime end = to.plusDays(1).atStartOfDay().minusNanos(1);
        List<Row> rows = new ArrayList<>();
        Set<UUID> fromReports = new HashSet<>();

        for (DamageReport d : damageRepo.findByOccurredOnBetweenAndStatusNotOrderByOccurredOnAscNumberAsc(from, to, DamageStatus.CANCELLED)) {
            if (d.getAdjustmentId() != null) {
                fromReports.add(d.getAdjustmentId());
            }
            if (d.getStatus() == DamageStatus.REJECTED || (source != null && source != Source.DAMAGE_REPORT)) {
                continue;
            }
            if (d.isApproved()) {
                // What the approval wrote off: units put back in stock were not damaged after all
                List<DamageReportLine> off = d.getLines().stream().filter(l -> l.getOutcome() == DamageOutcome.WRITE_OFF).toList();
                if (off.isEmpty()) {
                    continue;
                }
                rows.add(new Row(d.getOccurredOn(), Source.DAMAGE_REPORT, d.getNumber(), "/damage/" + d.getId(), d.getLocation().getCode(),
                        off.size(), off.stream().map(DamageReportLine::getAreaM2).reduce(BigDecimal.ZERO, BigDecimal::add),
                        off.stream().map(DamageReportLine::getUnitCost).reduce(BigDecimal.ZERO, BigDecimal::add), "damage.cause." + d.getCause(), null,
                        "damage.status." + d.getStatus(), true));
            } else {
                rows.add(new Row(d.getOccurredOn(), Source.DAMAGE_REPORT, d.getNumber(), "/damage/" + d.getId(), d.getLocation().getCode(),
                        d.getLines().size(), d.getAreaM2(), d.getEstimatedLoss(), "damage.cause." + d.getCause(), null,
                        "damage.status." + d.getStatus(), false));
            }
        }
        if (source == null || source == Source.TRIP_BREAKAGE) {
            List<TripBreakage> breakages = breakageRepo.findByHappenedAtBetweenOrderByHappenedAtAsc(start, end);
            Map<UUID, StockUnit> units = units(breakages.stream().map(TripBreakage::getStockUnitId).toList());
            for (TripBreakage b : breakages) {
                if (b.getStatus() == BreakageStatus.REJECTED) {
                    continue;
                }
                StockUnit u = units.get(b.getStockUnitId());
                rows.add(new Row(b.getHappenedAt().toLocalDate(), Source.TRIP_BREAKAGE, b.getTrip().getNumber(), "/trips/" + b.getTrip().getId() + "?tab=return",
                        b.getTrip().getVehicle().getPlate() + " · " + b.getPlace(), 1, u == null ? BigDecimal.ZERO : u.getAreaM2(),
                        u == null || u.getUnitCost() == null ? BigDecimal.ZERO : u.getUnitCost(), null, b.getReason(),
                        "breakage.status." + b.getStatus(), b.getStatus() == BreakageStatus.ACCEPTED));
            }
        }
        if (source == null || source == Source.CUTTING) {
            List<CuttingJobOutput> broken = outputRepo.findByKindAndCreatedAtBetweenOrderByCreatedAtAsc(CuttingOutputKind.BROKEN, start, end);
            Map<UUID, CuttingJob> jobs = jobRepo.findAllById(broken.stream().map(CuttingJobOutput::getCuttingJobId).collect(Collectors.toSet()))
                    .stream().collect(Collectors.toMap(CuttingJob::getId, Function.identity()));
            for (CuttingJobOutput o : broken) {
                CuttingJob job = jobs.get(o.getCuttingJobId());
                rows.add(new Row(o.getCreatedAt().toLocalDate(), Source.CUTTING, job == null ? "—" : job.getNumber(),
                        "/cutting-jobs/" + o.getCuttingJobId(), null, o.getQuantity(), o.getAreaM2() == null ? BigDecimal.ZERO : o.getAreaM2(),
                        o.getCost() == null ? BigDecimal.ZERO : o.getCost(), o.getReason() == null ? null : "cutting.reason." + o.getReason(),
                        o.getNote(), null, true));
            }
        }
        if (source == null || source == Source.WRITE_OFF) {
            List<StockAdjustmentLine> lines = adjustmentLineRepo.findPostedWriteOffs(WriteOffCause.DAMAGED, start, end).stream()
                    .filter(l -> !fromReports.contains(l.getAdjustment().getId())).toList();
            Map<UUID, StockUnit> units = units(lines.stream().map(StockAdjustmentLine::getStockUnitId).filter(Objects::nonNull).toList());
            for (StockAdjustmentLine l : lines) {
                StockUnit u = l.getStockUnitId() == null ? null : units.get(l.getStockUnitId());
                StockAdjustment a = l.getAdjustment();
                rows.add(new Row(a.getPostedAt().toLocalDate(), Source.WRITE_OFF, a.getNumber(), "/stock-adjustments/" + a.getId(),
                        u == null || u.getLocation() == null ? null : u.getLocation().getCode(), 1, u == null ? BigDecimal.ZERO : u.getAreaM2(),
                        l.getValueChange() == null ? BigDecimal.ZERO : l.getValueChange().abs(), null, a.getReason(), null, true));
            }
        }
        if (source == null || source == Source.CUSTOMER_RETURN) {
            Map<UUID, CreditNote> notes = creditNoteRepo.findByPostedAtBetweenOrderByPostedAtAsc(start, end).stream()
                    .collect(Collectors.toMap(CreditNote::getId, Function.identity(), (a, b) -> a, LinkedHashMap::new));
            if (!notes.isEmpty()) {
                List<CreditNoteUnit> cullet = creditNoteUnitRepo.findByCreditNoteIdInAndOutcome(notes.keySet(), ReturnOutcome.CULLET);
                Map<UUID, StockUnit> units = units(cullet.stream().map(CreditNoteUnit::getStockUnitId).toList());
                for (CreditNoteUnit cu : cullet) {
                    CreditNote n = notes.get(cu.getCreditNoteId());
                    StockUnit u = units.get(cu.getStockUnitId());
                    // Cullet has no place: the customer who returned it goes with the reason
                    String who = n.getCustomer() == null ? null : n.getCustomer().getName();
                    rows.add(new Row(n.getPostedAt().toLocalDate(), Source.CUSTOMER_RETURN, n.getNumber(), "/credit-notes/" + n.getId(),
                            null, 1, u == null ? BigDecimal.ZERO : u.getAreaM2(), cu.getUnitCost() == null ? BigDecimal.ZERO : cu.getUnitCost(),
                            "damage.cause.CUSTOMER_RETURN", who == null ? n.getReason() : who + (n.getReason() == null ? "" : " · " + n.getReason()),
                            null, true));
                }
            }
        }
        rows.sort(Comparator.comparing(Row::day).thenComparing(r -> r.source().ordinal()).thenComparing(Row::number));
        List<Total> totals = new ArrayList<>();
        for (Source s : Source.values()) {
            List<Row> of = rows.stream().filter(r -> r.source() == s).toList();
            if (!of.isEmpty()) {
                totals.add(total(s, of));
            }
        }
        return new Register(from, to, rows, totals, total(null, rows));
    }

    /** Damage of a period at cost (the KPI's damaged stock): every source. */
    public BigDecimal value(LocalDate from, LocalDate to) {
        return register(from, to, null).total().value();
    }

    /** A source's total of the decided rows (those waiting are listed, not counted). */
    private static Total total(Source source, List<Row> rows) {
        List<Row> counted = rows.stream().filter(Row::counted).toList();
        return new Total(source, counted.size(), counted.stream().mapToInt(Row::units).sum(),
                counted.stream().map(Row::areaM2).reduce(BigDecimal.ZERO, BigDecimal::add),
                counted.stream().map(Row::value).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private Map<UUID, StockUnit> units(Collection<UUID> ids) {
        return ids.isEmpty() ? Map.of() : unitRepo.findByIdIn(ids).stream().collect(Collectors.toMap(StockUnit::getId, Function.identity(), (a, b) -> a));
    }
}
