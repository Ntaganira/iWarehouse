package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.config.NumberFormats;
import com.ntaganira.heritier.iWarehouse.entity.StockCount;
import com.ntaganira.heritier.iWarehouse.entity.StockCountLine;
import com.ntaganira.heritier.iWarehouse.entity.StockUnit;
import com.ntaganira.heritier.iWarehouse.entity.StockVariance;
import com.ntaganira.heritier.iWarehouse.enums.CountOutcome;
import com.ntaganira.heritier.iWarehouse.enums.DocumentType;
import com.ntaganira.heritier.iWarehouse.enums.NotificationKind;
import com.ntaganira.heritier.iWarehouse.enums.SettingKey;
import com.ntaganira.heritier.iWarehouse.enums.VarianceCause;
import com.ntaganira.heritier.iWarehouse.enums.VarianceStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.StockCountLineRepository;
import com.ntaganira.heritier.iWarehouse.repository.StockUnitRepository;
import com.ntaganira.heritier.iWarehouse.repository.StockVarianceRepository;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : StockVarianceService.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Count variances (IMP 14, 16; VR-010; BP Table 9): opened when a count closes with differences (units
 *               missing, found, misplaced, not expected), their value at cost against the value counted, the class
 *               (StockVariances) and whether they are above the tolerance; ALERT_VARIANCE holders told of those above it,
 *               ALERT_MATERIAL_VARIANCE holders (the Board, the Internal Controller) of material ones. Resolved with the
 *               root cause, the corrective action and the responsible officer (PERM_RESOLVE_VARIANCE) within the
 *               Settings' working days; checkOverdue escalates the late ones once.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class StockVarianceService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final StockVarianceRepository repo;
    private final StockCountLineRepository lineRepo;
    private final StockUnitRepository unitRepo;
    private final DocumentNumberService numbers;
    private final SettingService settings;
    private final Notifier notifier;
    private final NumberFormats num;
    private final Clock clock;

    public StockVarianceService(StockVarianceRepository repo, StockCountLineRepository lineRepo, StockUnitRepository unitRepo,
                                DocumentNumberService numbers, SettingService settings, Notifier notifier,
                                NumberFormats num, Clock clock) {
        this.repo = repo;
        this.lineRepo = lineRepo;
        this.unitRepo = unitRepo;
        this.numbers = numbers;
        this.settings = settings;
        this.notifier = notifier;
        this.num = num;
        this.clock = clock;
    }

    /** A line of a variance: the count's result line and its unit (none for a label no unit has). */
    public record Line(StockCountLine line, StockUnit unit) {

        /** The unit's cost: what it takes off (missing) or puts back (found). */
        public BigDecimal getValue() {
            return unit == null || unit.getUnitCost() == null ? BigDecimal.ZERO : unit.getUnitCost();
        }
    }

    /** What resolving records (VR-010). */
    public record Form(VarianceCause rootCause, String correctiveAction, String responsibleOfficer, String note) {
    }

    // ---------------------------------------------------------------- opening

    /**
     * Opens the variance of a count just closed when it found differences; none otherwise. Inside the count's closing
     * (MANDATORY): its lines, the units by id, the units it expected.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public StockVariance open(StockCount count, List<StockCounting.Line> lines, Map<UUID, StockUnit> units, List<StockUnit> expected) {
        int missing = 0, found = 0, misplaced = 0, unknown = 0;
        BigDecimal missingValue = BigDecimal.ZERO;
        BigDecimal foundValue = BigDecimal.ZERO;
        for (StockCounting.Line l : lines) {
            StockUnit unit = l.unit() == null ? null : units.get(l.unit().id());
            BigDecimal cost = unit == null || unit.getUnitCost() == null ? BigDecimal.ZERO : unit.getUnitCost();
            switch (l.outcome()) {
                case MISSING -> {
                    missing++;
                    missingValue = missingValue.add(cost);
                }
                case FOUND_LOST -> {
                    found++;
                    foundValue = foundValue.add(cost);
                }
                case MISPLACED -> misplaced++;
                case UNKNOWN, ELSEWHERE, NOT_IN_STOCK -> unknown++;
                default -> {
                    // matched
                }
            }
        }
        if (missing + found + misplaced + unknown == 0) {
            return null;
        }
        BigDecimal expectedValue = expected.stream().map(u -> u.getUnitCost() == null ? BigDecimal.ZERO : u.getUnitCost())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal percent = StockVariances.percent(missingValue, foundValue, expectedValue);
        StockVariance v = new StockVariance();
        v.setNumber(numbers.next(DocumentType.STOCK_VARIANCE));
        v.setCount(count);
        v.setMissingUnits(missing);
        v.setFoundUnits(found);
        v.setMisplacedUnits(misplaced);
        v.setUnknownUnits(unknown);
        v.setMissingValue(missingValue.setScale(2));
        v.setFoundValue(foundValue.setScale(2));
        v.setExpectedValue(expectedValue.setScale(2));
        v.setVariancePercent(percent);
        v.setClassification(StockVariances.classify(percent, settings.getDecimal(SettingKey.COUNT_VARIANCE_MODERATE_PERCENT),
                settings.getDecimal(SettingKey.COUNT_VARIANCE_MATERIAL_PERCENT)));
        v.setAboveTolerance(StockVariances.aboveTolerance(percent, settings.getDecimal(SettingKey.COUNT_VARIANCE_TOLERANCE_PERCENT)));
        LocalDate today = LocalDate.now(clock);
        v.setDueOn(StockVariances.due(today, settings.getInt(SettingKey.COUNT_VARIANCE_DAYS)));
        v.setStatus(VarianceStatus.OPEN);
        if (v.isMaterial()) {
            v.setBoardNotifiedAt(LocalDateTime.now(clock));
        }
        repo.save(v);
        Long me = AppUserPrincipal.current().map(AppUserPrincipal::getId).orElse(null);
        String pct = percent == null ? "—" : percent.toPlainString() + " %";
        String link = "/stock-variances/" + v.getId();
        if (v.isAboveTolerance()) {
            notifier.holders("ALERT_VARIANCE", me, NotificationKind.VARIANCE, "notify.variance.opened", "notify.variance.openedText", link,
                    v.getNumber(), count.getNumber(), num.money(missingValue), num.money(foundValue), pct, v.getDueOn().format(DAY));
        }
        if (v.isMaterial()) {
            notifier.holders("ALERT_MATERIAL_VARIANCE", me, NotificationKind.VARIANCE, "notify.variance.material", "notify.variance.materialText",
                    link, v.getNumber(), count.getNumber(), num.money(missingValue), num.money(foundValue), pct);
        }
        return v;
    }

    // ---------------------------------------------------------------- reading

    /** status: a VarianceStatus name, "waiting" for open and escalated, or empty for all. */
    public Page<StockVariance> findPage(String search, String status, int page, int size) {
        Specification<StockVariance> spec = (root, query, cb) -> {
            Predicate p = cb.conjunction();
            if (StringUtils.hasText(search)) {
                String term = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                var count = root.join("count");
                p = cb.and(p, cb.or(cb.like(cb.lower(root.get("number")), term), cb.like(cb.lower(count.get("number")), term),
                        cb.like(cb.lower(cb.coalesce(root.get("responsibleOfficer"), "")), term)));
            }
            if ("waiting".equals(status)) {
                p = cb.and(p, root.get("status").in(VarianceStatus.OPEN, VarianceStatus.ESCALATED));
            } else if (StringUtils.hasText(status)) {
                try {
                    p = cb.and(p, cb.equal(root.get("status"), VarianceStatus.valueOf(status)));
                } catch (IllegalArgumentException e) {
                    // unknown status: no filter
                }
            }
            return p;
        };
        return repo.findAll(spec, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "number"))));
    }

    public StockVariance findDetailed(UUID id) {
        return repo.findDetailedById(id).orElseThrow(() -> new NotFoundException("StockVariance", id));
    }

    public Optional<StockVariance> ofCount(UUID countId) {
        return repo.findByCount_Id(countId);
    }

    /** The count's lines that differ from the records, with their units (missing first, then found, misplaced, the rest). */
    public List<Line> lines(StockVariance v) {
        List<StockCountLine> lines = lineRepo.findByCountIdOrderByLineNo(v.getCount().getId()).stream()
                .filter(l -> l.getOutcome() != CountOutcome.MATCHED).toList();
        List<UUID> ids = lines.stream().map(StockCountLine::getStockUnitId).filter(Objects::nonNull).toList();
        Map<UUID, StockUnit> units = ids.isEmpty() ? Map.of() : unitRepo.findByIdIn(ids).stream()
                .collect(Collectors.toMap(StockUnit::getId, Function.identity()));
        List<CountOutcome> order = List.of(CountOutcome.MISSING, CountOutcome.FOUND_LOST, CountOutcome.MISPLACED, CountOutcome.ELSEWHERE,
                CountOutcome.NOT_IN_STOCK, CountOutcome.UNKNOWN);
        return lines.stream()
                .sorted(Comparator.comparing((StockCountLine l) -> order.indexOf(l.getOutcome())).thenComparing(StockCountLine::getCode))
                .map(l -> new Line(l, l.getStockUnitId() == null ? null : units.get(l.getStockUnitId())))
                .toList();
    }

    public long waitingCount() {
        return repo.countByStatusIn(List.of(VarianceStatus.OPEN, VarianceStatus.ESCALATED));
    }

    // ---------------------------------------------------------------- resolving and escalating

    /** Records the investigation: the root cause, the corrective action, the officer responsible (VR-010). */
    @Transactional
    public StockVariance resolve(UUID id, Form form) {
        StockVariance v = repo.lockById(id).orElseThrow(() -> new NotFoundException("StockVariance", id));
        if (v.isResolved()) {
            throw BusinessException.of("variance.resolved", v.getNumber());
        }
        if (form.rootCause() == null) {
            throw BusinessException.onField("rootCause", "variance.rootCause.required");
        }
        String action = PartyRules.clean(form.correctiveAction());
        if (action == null || action.length() > 500) {
            throw BusinessException.onField("correctiveAction", "variance.action.required");
        }
        String officer = PartyRules.clean(form.responsibleOfficer());
        if (officer == null || officer.length() > 100) {
            throw BusinessException.onField("responsibleOfficer", "variance.officer.required");
        }
        String note = PartyRules.clean(form.note());
        if (note != null && note.length() > 500) {
            throw BusinessException.onField("note", "variance.note.size");
        }
        v.setRootCause(form.rootCause());
        v.setCorrectiveAction(action);
        v.setResponsibleOfficer(officer);
        v.setResolutionNote(note);
        v.setResolvedBy(AppUserPrincipal.currentUsername());
        v.setResolvedAt(LocalDateTime.now(clock));
        v.setStatus(VarianceStatus.RESOLVED);
        return v;
    }

    /** Escalates, once, each open variance past its due day; ALERT_VARIANCE holders told (IMP 14.2-14.3). How many. */
    @Scheduled(fixedDelayString = "${app.alerts.variance-every:PT6H}", initialDelayString = "${app.alerts.first-check:PT1M}")
    @Transactional
    public int checkOverdue() {
        LocalDate today = LocalDate.now(clock);
        int count = 0;
        for (StockVariance v : repo.findByStatusAndDueOnBefore(VarianceStatus.OPEN, today)) {
            v.setEscalatedAt(LocalDateTime.now(clock));
            v.setStatus(VarianceStatus.ESCALATED);
            notifier.holders("ALERT_VARIANCE", null, NotificationKind.VARIANCE, "notify.variance.escalated", "notify.variance.escalatedText",
                    "/stock-variances/" + v.getId(), v.getNumber(), v.getCount().getNumber(), v.getDueOn().format(DAY));
            count++;
        }
        return count;
    }
}
