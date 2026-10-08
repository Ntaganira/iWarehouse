package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.dto.CuttingJobDto;
import com.ntaganira.heritier.iWarehouse.dto.CuttingResultDto;
import com.ntaganira.heritier.iWarehouse.entity.*;
import com.ntaganira.heritier.iWarehouse.enums.*;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.*;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import jakarta.persistence.criteria.Join;
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
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : CuttingJobService.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Cutting jobs (PRD-01..09). A job lists the pieces wanted, for stock or a customer
 *               (PRD-01). The operator takes a source unit that fits them (PRD-02); recording the cut
 *               consumes it and creates a unit per cut piece and per usable off-cut, on their racks
 *               (PRD-03, PRD-04); smaller leftovers and the trim are cullet (PRD-05); areas must balance
 *               within 1% (PRD-06); the source cost is shared by area, cullet and breakage are expensed
 *               and the MAC follows (PRD-07, PRD-08). Also the yield report (PRD-09).
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class CuttingJobService {

    /** How many units the "take a sheet" page suggests. */
    static final int SUGGESTIONS = 12;

    private static final List<CuttingJobStatus> TAKEN = List.of(CuttingJobStatus.IN_PROGRESS, CuttingJobStatus.COMPLETED);

    private final CuttingJobRepository repo;
    private final CuttingJobLineRepository lineRepo;
    private final CuttingJobOutputRepository outputRepo;
    private final ProductRepository productRepo;
    private final CustomerRepository customerRepo;
    private final ProcessingServiceRepository serviceRepo;
    private final StockUnitRepository unitRepo;
    private final StockService stockService;
    private final DocumentNumberService numbers;
    private final SettingService settingService;
    private final Clock clock;

    public CuttingJobService(CuttingJobRepository repo, CuttingJobLineRepository lineRepo, CuttingJobOutputRepository outputRepo,
                             ProductRepository productRepo, CustomerRepository customerRepo,
                             ProcessingServiceRepository serviceRepo, StockUnitRepository unitRepo, StockService stockService,
                             DocumentNumberService numbers, SettingService settingService, Clock clock) {
        this.repo = repo;
        this.lineRepo = lineRepo;
        this.outputRepo = outputRepo;
        this.productRepo = productRepo;
        this.customerRepo = customerRepo;
        this.serviceRepo = serviceRepo;
        this.unitRepo = unitRepo;
        this.stockService = stockService;
        this.numbers = numbers;
        this.settingService = settingService;
        this.clock = clock;
    }

    /** What a job needs from its source: the largest piece either way round and the total area. */
    public record Need(int pieces, BigDecimal areaM2, int shortSideMm, int longSideMm) {
    }

    /** A place pieces or off-cuts can go, with what its rack holds now. */
    public record LocationChoice(Location location, Location rack, RackLoad load) {
    }

    /** The off-cut threshold in force (PRD-04). */
    public record Threshold(BigDecimal minAreaM2, int minSideMm) {
    }

    /** What recording a cut did. */
    public record CutResult(CuttingJob job, List<StockUnit> pieces, List<StockUnit> offcuts, Cutting.Balance balance,
                            BigDecimal spoilageCost) {
    }

    /** What a completed job produced: the ledger rows, and the units they created by id. */
    public record Outcome(List<CuttingJobOutput> outputs, Map<UUID, StockUnit> units, BigDecimal sourceAreaM2) {

        public List<CuttingJobOutput> of(CuttingOutputKind kind) {
            return outputs.stream().filter(o -> o.getKind() == kind).toList();
        }

        /** Where the source went: one row per kind of output, in order (PRD-06, PRD-07). */
        public List<BalanceRow> getRows() {
            List<BalanceRow> rows = new ArrayList<>();
            for (CuttingOutputKind kind : CuttingOutputKind.values()) {
                List<CuttingJobOutput> of = of(kind);
                BigDecimal area = of.stream().map(CuttingJobOutput::getAreaM2).reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal cost = of.stream().map(CuttingJobOutput::getCost).reduce(BigDecimal.ZERO, BigDecimal::add);
                int count = of.stream().mapToInt(CuttingJobOutput::getQuantity).sum();
                rows.add(new BalanceRow(kind, count, area, Cutting.yieldPercent(area, sourceAreaM2), cost));
            }
            return rows;
        }

        public BigDecimal getTotalArea() {
            return outputs.stream().map(CuttingJobOutput::getAreaM2).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        public BigDecimal getTotalCost() {
            return outputs.stream().map(CuttingJobOutput::getCost).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    /** m², share of the source and cost of one kind of output. */
    public record BalanceRow(CuttingOutputKind kind, int count, BigDecimal areaM2, BigDecimal sharePercent, BigDecimal cost) {
    }

    /** The yield report of a period (PRD-09). */
    public record YieldReport(LocalDate from, LocalDate to, CuttingYield.Row total, List<CuttingYield.Row> byOperator,
                              List<CuttingYield.Row> byProduct, List<CuttingYield.Breakage> breakage) {
    }

    // ---------------------------------------------------------------- reading

    /** status: a CuttingJobStatus name or empty for all; purpose likewise. */
    public Page<CuttingJob> findPage(String search, String status, String purpose, int page, int size) {
        Specification<CuttingJob> spec = (root, query, cb) -> {
            Predicate p = cb.conjunction();
            if (StringUtils.hasText(search)) {
                String term = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                Join<CuttingJob, Customer> customer = root.join("customer", JoinType.LEFT);
                p = cb.and(p, cb.or(
                        cb.like(cb.lower(root.get("number")), term),
                        cb.like(cb.lower(cb.coalesce(root.get("customerRef"), "")), term),
                        cb.like(cb.lower(cb.coalesce(root.get("sourceCode"), "")), term),
                        cb.like(cb.lower(cb.coalesce(root.get("notes"), "")), term),
                        cb.like(cb.lower(cb.coalesce(customer.get("name"), "")), term)));
            }
            CuttingJobStatus st = parse(CuttingJobStatus.class, status);
            if (st != null) {
                p = cb.and(p, cb.equal(root.get("status"), st));
            }
            CuttingPurpose pu = parse(CuttingPurpose.class, purpose);
            if (pu != null) {
                p = cb.and(p, cb.equal(root.get("purpose"), pu));
            }
            return p;
        };
        return repo.findAll(spec, PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "number"))));
    }

    /** Pieces wanted per job of a page, read in one query: (count, m²). */
    public Map<UUID, Need> needs(Collection<CuttingJob> jobs) {
        if (jobs.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<CuttingJobLine>> lines = lineRepo.findByJob_IdIn(jobs.stream().map(CuttingJob::getId).toList())
                .stream().collect(Collectors.groupingBy(l -> l.getJob().getId()));
        Map<UUID, Need> needs = new HashMap<>();
        for (CuttingJob job : jobs) {
            int pieces = 0;
            BigDecimal area = BigDecimal.ZERO;
            for (CuttingJobLine line : lines.getOrDefault(job.getId(), List.of())) {
                pieces += line.getQuantity();
                area = area.add(line.getAreaM2());
            }
            needs.put(job.getId(), new Need(pieces, area, 0, 0));
        }
        return needs;
    }

    public CuttingJob findById(UUID id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException("CuttingJob", id));
    }

    /** A job with its pieces, product and customer. */
    public CuttingJob findDetailed(UUID id) {
        return repo.findDetailedById(id).orElseThrow(() -> new NotFoundException("CuttingJob", id));
    }

    /** The largest piece either way round and the total area of a job's pieces. */
    public static Need need(CuttingJob job) {
        int shortSide = 0;
        int longSide = 0;
        for (CuttingJobLine line : job.getLines()) {
            shortSide = Math.max(shortSide, Math.min(line.getWidthMm(), line.getHeightMm()));
            longSide = Math.max(longSide, Math.max(line.getWidthMm(), line.getHeightMm()));
        }
        return new Need(job.getPieces(), job.getPiecesWantedM2(), shortSide, longSide);
    }

    /** Units the job can take, smallest first (off-cuts before full sheets): INV-06 for the cut. */
    public List<StockUnit> suggestions(CuttingJob job) {
        Need need = need(job);
        if (need.pieces() == 0) {
            return List.of();
        }
        return stockService.cuttingSources(job.getProduct().getId(), need.shortSideMm(), need.longSideMm(),
                need.areaM2(), SUGGESTIONS);
    }

    /** What a completed job produced. */
    public Outcome outcome(CuttingJob job) {
        if (job.getStatus() != CuttingJobStatus.COMPLETED) {
            return new Outcome(List.of(), Map.of(), job.getSourceAreaM2());
        }
        Map<UUID, StockUnit> units = stockService.cutFrom(job.getSourceUnitId()).stream()
                .collect(Collectors.toMap(StockUnit::getId, Function.identity()));
        // Written in one go, so the time does not order them: pieces, off-cuts, cullet (trim last), breakage,
        // units by label code.
        List<CuttingJobOutput> outputs = new ArrayList<>(outputRepo.findByCuttingJobIdOrderByCreatedAtAscIdAsc(job.getId()));
        outputs.sort(Comparator.comparing((CuttingJobOutput o) -> o.getKind().ordinal())
                .thenComparing(o -> o.getStockUnitId() == null || units.get(o.getStockUnitId()) == null ? ""
                        : units.get(o.getStockUnitId()).getCode())
                .thenComparing(o -> o.getWidthMm() == null)
                .thenComparing(CuttingJobOutput::getAreaM2, Comparator.reverseOrder()));
        return new Outcome(outputs, units, job.getSourceAreaM2());
    }

    /** The unit a job took (in progress or completed), with its product and location. */
    public Optional<StockUnit> source(CuttingJob job) {
        return job.getSourceUnitId() == null ? Optional.empty() : unitRepo.findDetailedById(job.getSourceUnitId());
    }

    /** Jobs cutting the rest of a job, not cancelled. */
    public List<CuttingJob> restJobs(UUID id) {
        return repo.findByParentJobIdAndStatusNot(id, CuttingJobStatus.CANCELLED);
    }

    /** The job a unit was taken for or cut by, if any (for the stock unit page). */
    public Optional<CuttingJob> jobOfSource(UUID unitId) {
        return repo.findFirstBySourceUnitIdAndStatusIn(unitId, TAKEN);
    }

    /** The job a unit was cut out of, if any (for the stock unit page). */
    public Optional<CuttingJob> jobOfOutput(UUID unitId) {
        return outputRepo.findByStockUnitId(unitId).flatMap(o -> repo.findById(o.getCuttingJobId()));
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    public Threshold threshold() {
        return new Threshold(settingService.getDecimal(SettingKey.OFFCUT_MIN_AREA), settingService.getInt(SettingKey.OFFCUT_MIN_SIDE));
    }

    // ---------------------------------------------------------------- form data

    public CuttingJobDto newForm() {
        CuttingJobDto dto = new CuttingJobDto();
        dto.getLines().add(new CuttingJobDto.Line());
        return dto;
    }

    public CuttingJobDto formOf(CuttingJob job) {
        CuttingJobDto dto = new CuttingJobDto();
        dto.setId(job.getId());
        dto.setPurpose(job.getPurpose());
        dto.setCustomerId(job.getCustomer() == null ? null : job.getCustomer().getId());
        dto.setCustomerRef(job.getCustomerRef());
        dto.setProductId(job.getProduct().getId());
        dto.setDueDate(job.getDueDate());
        dto.setNotes(job.getNotes());
        for (CuttingJobLine line : job.getLines()) {
            CuttingJobDto.Line row = new CuttingJobDto.Line();
            row.setId(line.getId());
            row.setWidthMm(line.getWidthMm());
            row.setHeightMm(line.getHeightMm());
            row.setQuantity(line.getQuantity());
            row.setProcessing(new ArrayList<>(line.getProcessingCodes()));
            row.setMark(line.getMark());
            dto.getLines().add(row);
        }
        return dto;
    }

    /** Glass that can be cut (tempered cannot, PRD-02), plus the job's own product if it was disabled since. */
    public List<Product> products(CuttingJob current) {
        List<Product> list = new ArrayList<>(productRepo.findByEnabledTrueOrderByCodeAsc().stream()
                .filter(p -> p.getGlassType().isCuttable()).toList());
        if (current != null && list.stream().noneMatch(p -> p.getId().equals(current.getProduct().getId()))) {
            list.add(0, current.getProduct());
        }
        return list;
    }

    public List<Customer> customers(CuttingJob current) {
        List<Customer> list = new ArrayList<>(customerRepo.findByEnabledTrueOrderByNameAsc());
        if (current != null && current.getCustomer() != null
                && list.stream().noneMatch(c -> c.getId().equals(current.getCustomer().getId()))) {
            list.add(0, current.getCustomer());
        }
        return list;
    }

    /** Processing that can be asked for: enabled services, and any a job already uses. */
    public List<ProcessingService> processing(CuttingJobDto dto) {
        Set<String> used = dto.getLines().stream().flatMap(l -> l.getProcessing().stream()).collect(Collectors.toSet());
        return serviceRepo.findAllByOrderByEnabledDescCodeAsc().stream()
                .filter(s -> s.isEnabled() || used.contains(s.getCode()))
                .toList();
    }

    /** Processing service names by code, for showing a job. */
    public Map<String, String> processingNames() {
        return serviceRepo.findAllByOrderByEnabledDescCodeAsc().stream()
                .collect(Collectors.toMap(ProcessingService::getCode, ProcessingService::getName, (a, b) -> a, LinkedHashMap::new));
    }

    /** Racks and slots for cut pieces (not off-cut racks), with their load. */
    public List<LocationChoice> pieceLocations() {
        Map<UUID, Location> byId = stockService.locationsById();
        Map<UUID, RackLoad> loads = stockService.rackLoads(byId);
        return stockService.receivingLocations(byId).stream()
                .map(l -> choice(l, byId, loads))
                .toList();
    }

    /** Off-cut racks and their slots (PRD-04), with their load. */
    public List<LocationChoice> offcutLocations() {
        Map<UUID, Location> byId = stockService.locationsById();
        Map<UUID, RackLoad> loads = stockService.rackLoads(byId);
        return offcutPlaces(byId).stream()
                .map(l -> choice(l, byId, loads))
                .toList();
    }

    /** The cut form: every size at the quantity wanted, the pieces where the source is, off-cuts on the first off-cut rack. */
    public CuttingResultDto resultForm(CuttingJob job) {
        CuttingResultDto dto = new CuttingResultDto();
        for (CuttingJobLine line : job.getLines()) {
            CuttingResultDto.Line row = new CuttingResultDto.Line();
            row.setLineId(line.getId());
            row.setCutQty(line.getQuantity());
            dto.getLines().add(row);
        }
        dto.getLeftovers().add(new CuttingResultDto.Leftover());
        dto.getBroken().add(new CuttingResultDto.Broken());
        Map<UUID, Location> byId = stockService.locationsById();
        List<Location> forPieces = stockService.receivingLocations(byId);
        source(job).map(StockUnit::getLocation)
                .filter(l -> forPieces.stream().anyMatch(r -> r.getId().equals(l.getId())))
                .ifPresent(l -> dto.setPiecesLocationId(l.getId()));
        offcutPlaces(byId).stream().findFirst().ifPresent(l -> dto.setOffcutLocationId(l.getId()));
        return dto;
    }

    // ---------------------------------------------------------------- drafts (PRD-01)

    @Transactional
    public CuttingJob create(CuttingJobDto dto) {
        Product product = product(dto, null);
        Customer customer = customer(dto, null);
        checkLines(dto);
        CuttingJob job = new CuttingJob();
        job.setNumber(numbers.next(DocumentType.CUTTING_JOB));
        apply(job, dto, product, customer);
        return repo.save(job);
    }

    @Transactional
    public CuttingJob update(UUID id, CuttingJobDto dto) {
        CuttingJob job = findDetailed(id);
        requireStatus(job, CuttingJobStatus.DRAFT, "cutting.notDraft");
        Product product = product(dto, job);
        Customer customer = customer(dto, job);
        checkLines(dto);
        apply(job, dto, product, customer);
        return job;
    }

    /** Header from the form; rows matched by id and numbered in order. */
    private void apply(CuttingJob job, CuttingJobDto dto, Product product, Customer customer) {
        job.setPurpose(dto.getPurpose());
        job.setCustomer(customer);
        job.setCustomerRef(dto.getPurpose() == CuttingPurpose.CUSTOMER ? PartyRules.clean(dto.getCustomerRef()) : null);
        job.setProduct(product);
        job.setDueDate(dto.getDueDate());
        job.setNotes(PartyRules.clean(dto.getNotes()));

        Map<UUID, CuttingJobLine> current = job.getLines().stream()
                .filter(l -> l.getId() != null)
                .collect(Collectors.toMap(CuttingJobLine::getId, Function.identity()));
        List<CuttingJobLine> kept = new ArrayList<>();
        for (CuttingJobDto.Line row : dto.getLines()) {
            CuttingJobLine line = row.getId() == null ? null : current.get(row.getId());
            if (line == null) {
                line = new CuttingJobLine();
                line.setJob(job);
            }
            line.setWidthMm(row.getWidthMm());
            line.setHeightMm(row.getHeightMm());
            line.setQuantity(row.getQuantity());
            line.setProcessing(processingText(row.getProcessing()));
            line.setMark(PartyRules.clean(row.getMark()));
            kept.add(line);
        }
        job.getLines().removeIf(l -> !kept.contains(l));
        // uk_cutting_job_lines_no is checked at commit, so rows can swap numbers here.
        for (int i = 0; i < kept.size(); i++) {
            CuttingJobLine line = kept.get(i);
            line.setLineNo(i + 1);
            if (!job.getLines().contains(line)) {
                job.getLines().add(line);
            }
        }
    }

    /** Codes sorted, without repeats, comma separated; null for none. */
    static String processingText(List<String> codes) {
        if (codes == null) {
            return null;
        }
        String text = codes.stream().filter(StringUtils::hasText).map(String::trim).distinct().sorted()
                .collect(Collectors.joining(","));
        return text.isEmpty() ? null : text;
    }

    private Product product(CuttingJobDto dto, CuttingJob current) {
        Product product = productRepo.findById(dto.getProductId())
                .orElseThrow(() -> BusinessException.onField("productId", "cutting.product.required"));
        boolean same = current != null && current.getProduct().getId().equals(product.getId());
        if (!product.isEnabled() && !same) {
            throw BusinessException.onField("productId", "cutting.product.disabled", product.getCode());
        }
        if (!product.getGlassType().isCuttable()) {
            throw BusinessException.onField("productId", "cutting.product.notCuttable", product.getCode());
        }
        return product;
    }

    /** Pieces for a customer name an active customer (the job's own may have been disabled since); pieces for stock none. */
    private Customer customer(CuttingJobDto dto, CuttingJob current) {
        if (dto.getPurpose() != CuttingPurpose.CUSTOMER) {
            return null;
        }
        if (dto.getCustomerId() == null) {
            throw BusinessException.onField("customerId", "cutting.customer.required");
        }
        Customer customer = customerRepo.findById(dto.getCustomerId())
                .orElseThrow(() -> BusinessException.onField("customerId", "cutting.customer.required"));
        boolean same = current != null && current.getCustomer() != null && current.getCustomer().getId().equals(customer.getId());
        if (!customer.isEnabled() && !same) {
            throw BusinessException.onField("customerId", "cutting.customer.disabled", customer.getName());
        }
        return customer;
    }

    /** At least one size; processing only from the services offered. */
    void checkLines(CuttingJobDto dto) {
        if (dto.getLines().isEmpty()) {
            throw BusinessException.of("cutting.lines.required");
        }
        Set<String> known = serviceRepo.findAllByOrderByEnabledDescCodeAsc().stream()
                .map(ProcessingService::getCode).collect(Collectors.toSet());
        for (int i = 0; i < dto.getLines().size(); i++) {
            for (String code : dto.getLines().get(i).getProcessing()) {
                if (StringUtils.hasText(code) && !known.contains(code.trim())) {
                    throw BusinessException.onField("lines[" + i + "].processing", "cutting.line.processing.unknown", code);
                }
            }
        }
    }

    // ---------------------------------------------------------------- the source (PRD-02)

    /**
     * Takes a unit for the job: same glass, cuttable, available, every piece fits it either way round and
     * their area is within its area (PRD-02). The unit goes IN_CUTTING (INV-05) and the job IN_PROGRESS.
     * {@code code} is a scanned or typed label code, used when no unit id is given.
     */
    @Transactional
    public CuttingJob start(UUID id, UUID unitId, String code) {
        repo.lockById(id).orElseThrow(() -> new NotFoundException("CuttingJob", id));
        CuttingJob job = findDetailed(id);
        requireStatus(job, CuttingJobStatus.DRAFT, "cutting.notDraft");
        StockUnit unit = (unitId != null ? unitRepo.findById(unitId) : stockService.findByCode(code))
                .orElseThrow(() -> BusinessException.onField("code", "cutting.source.notFound",
                        StringUtils.hasText(code) ? code.trim() : "?"));
        checkSource(job, unit);
        stockService.startCutting(unit, job.getId(), job.getNumber());
        Optional<AppUserPrincipal> user = AppUserPrincipal.current();
        job.setSourceUnitId(unit.getId());
        job.setSourceCode(unit.getCode());
        job.setSourceWidthMm(unit.getWidthMm());
        job.setSourceHeightMm(unit.getHeightMm());
        job.setSourceAreaM2(unit.getAreaM2());
        job.setOperatorId(user.map(AppUserPrincipal::getId).orElse(null));
        job.setOperatorName(user.map(AppUserPrincipal::getUsername).orElse("system"));
        job.setStartedAt(LocalDateTime.now(clock));
        job.setStatus(CuttingJobStatus.IN_PROGRESS);
        return job;
    }

    /** PRD-02: the unit can be cut for these pieces. */
    void checkSource(CuttingJob job, StockUnit unit) {
        if (!unit.getProduct().getId().equals(job.getProduct().getId())) {
            throw BusinessException.onField("code", "cutting.source.wrongProduct", unit.getCode(),
                    unit.getProduct().getCode(), job.getProduct().getCode());
        }
        if (!unit.getProduct().getGlassType().isCuttable()) {
            throw BusinessException.onField("code", "cutting.source.notCuttable", unit.getCode());
        }
        if (unit.getStatus() != StockStatus.AVAILABLE) {
            throw BusinessException.onField("code", "cutting.source.notAvailable", unit.getCode());
        }
        for (CuttingJobLine line : job.getLines()) {
            if (!Cutting.fits(line.getWidthMm(), line.getHeightMm(), unit.getWidthMm(), unit.getHeightMm())) {
                throw BusinessException.onField("code", "cutting.source.pieceTooBig", line.getLineNo(), line.getWidthMm(),
                        line.getHeightMm(), unit.getCode(), unit.getWidthMm(), unit.getHeightMm());
            }
        }
        if (job.getPiecesWantedM2().compareTo(unit.getAreaM2()) > 0) {
            throw BusinessException.onField("code", "cutting.source.tooSmall", unit.getCode(), unit.getAreaM2(),
                    job.getPiecesWantedM2());
        }
    }

    /** Puts the unit back uncut (wrong sheet, job postponed): available again, the job a draft. */
    @Transactional
    public CuttingJob release(UUID id, String reason) {
        repo.lockById(id).orElseThrow(() -> new NotFoundException("CuttingJob", id));
        CuttingJob job = findDetailed(id);
        requireStatus(job, CuttingJobStatus.IN_PROGRESS, "cutting.notInProgress");
        releaseSource(job, reason);
        job.setStatus(CuttingJobStatus.DRAFT);
        return job;
    }

    private void releaseSource(CuttingJob job, String reason) {
        StockUnit unit = unitRepo.findById(job.getSourceUnitId())
                .orElseThrow(() -> new NotFoundException("StockUnit", job.getSourceUnitId()));
        stockService.releaseCutting(unit, reason.trim(), job.getId(), job.getNumber());
        job.setSourceUnitId(null);
        job.setSourceCode(null);
        job.setSourceWidthMm(null);
        job.setSourceHeightMm(null);
        job.setSourceAreaM2(null);
        job.setOperatorId(null);
        job.setOperatorName(null);
        job.setStartedAt(null);
    }

    // ---------------------------------------------------------------- the cut (PRD-03..08)

    /**
     * Records the cut. Locks the job, then its product, and reads the stock held before changing it. The
     * source is consumed; each cut piece and each leftover big enough (PRD-04) becomes a unit on its rack,
     * other leftovers and the trim are cullet (PRD-05), broken glass is recorded with its reason (PRD-08).
     * The areas must balance within 1% (PRD-06). The source cost is shared by area: units carry their part,
     * cullet and breakage are expensed, and the product's MAC follows (PRD-07).
     */
    @Transactional
    public CutResult complete(UUID id, CuttingResultDto dto) {
        repo.lockById(id).orElseThrow(() -> new NotFoundException("CuttingJob", id));
        CuttingJob job = findDetailed(id);
        requireStatus(job, CuttingJobStatus.IN_PROGRESS, "cutting.notInProgress");
        Product product = productRepo.lockAllById(List.of(job.getProduct().getId())).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Product", job.getProduct().getId()));
        StockUnit source = unitRepo.findById(job.getSourceUnitId())
                .orElseThrow(() -> new NotFoundException("StockUnit", job.getSourceUnitId()));
        if (source.getStatus() != StockStatus.IN_CUTTING) {
            throw BusinessException.of("cutting.source.notInCutting", source.getCode());
        }
        BigDecimal heldBefore = stockService.heldArea(product.getId());
        Threshold threshold = threshold();
        BigDecimal weightPerM2 = GlassProducts.weightPerM2(product.getThicknessMm(),
                settingService.getDecimal(SettingKey.GLASS_DENSITY));

        Plan plan = plan(job, source, dto, threshold);
        Map<UUID, Location> byId = stockService.locationsById();
        Location piecesAt = plan.pieceCount() == 0 ? null : piecesLocation(dto.getPiecesLocationId(), byId);
        Location offcutsAt = plan.offcuts().isEmpty() ? null : offcutLocation(dto.getOffcutLocationId(), byId);
        checkRacks(job, source, plan, piecesAt, offcutsAt, weightPerM2, byId);

        // Parts of the source cost, in this order: pieces, off-cuts, small leftovers, breakage, trim.
        List<BigDecimal> areas = new ArrayList<>();
        for (CuttingJobLine line : job.getLines()) {
            for (int k = 0; k < plan.cutQty().get(line.getId()); k++) {
                areas.add(line.getPieceAreaM2());
            }
        }
        plan.offcuts().forEach(o -> areas.add(Pricing.areaM2(o.getWidthMm(), o.getHeightMm())));
        plan.small().forEach(o -> areas.add(rowArea(o.getWidthMm(), o.getHeightMm(), o.getQuantity())));
        plan.broken().forEach(b -> areas.add(rowArea(b.getWidthMm(), b.getHeightMm(), b.getQuantity())));
        BigDecimal trim = plan.balance().getTrim();
        if (trim.signum() > 0) {
            areas.add(trim);
        }
        BigDecimal sourceCost = source.getUnitCost();
        Iterator<BigDecimal> parts = Cutting.costByArea(sourceCost, areas).iterator();

        LocalDateTime now = LocalDateTime.now(clock);
        Optional<AppUserPrincipal> user = AppUserPrincipal.current();
        StockStatus pieceStatus = job.getPurpose() == CuttingPurpose.CUSTOMER ? StockStatus.RESERVED : StockStatus.AVAILABLE;
        List<StockUnit> pieces = new ArrayList<>();
        List<StockUnit> offcuts = new ArrayList<>();
        BigDecimal culletCost = BigDecimal.ZERO;
        BigDecimal brokenCost = BigDecimal.ZERO;
        BigDecimal culletKg = BigDecimal.ZERO;

        for (CuttingJobLine line : job.getLines()) {
            int cut = plan.cutQty().get(line.getId());
            line.setCutQty(cut);
            for (int k = 0; k < cut; k++) {
                BigDecimal cost = parts.next();
                BigDecimal kg = GlassProducts.weightKg(line.getPieceAreaM2(), weightPerM2);
                StockUnit unit = stockService.createCut(source, UnitKind.CUT_PIECE, line.getWidthMm(), line.getHeightMm(),
                        kg, pieceStatus, piecesAt, cost, job.getCustomer(), job.getId(), job.getNumber());
                pieces.add(unit);
                saveOutput(job, CuttingOutputKind.PIECE, line.getId(), line.getWidthMm(), line.getHeightMm(), 1,
                        line.getPieceAreaM2(), kg, unit.getId(), null, null, cost, now, user);
            }
        }
        for (CuttingResultDto.Leftover o : plan.offcuts()) {
            BigDecimal cost = parts.next();
            BigDecimal area = Pricing.areaM2(o.getWidthMm(), o.getHeightMm());
            BigDecimal kg = GlassProducts.weightKg(area, weightPerM2);
            StockUnit unit = stockService.createCut(source, UnitKind.OFFCUT, o.getWidthMm(), o.getHeightMm(), kg,
                    StockStatus.AVAILABLE, offcutsAt, cost, null, job.getId(), job.getNumber());
            offcuts.add(unit);
            saveOutput(job, CuttingOutputKind.OFFCUT, null, o.getWidthMm(), o.getHeightMm(), 1, area, kg, unit.getId(),
                    null, null, cost, now, user);
        }
        for (CuttingResultDto.Leftover o : plan.small()) {
            BigDecimal cost = parts.next();
            BigDecimal area = rowArea(o.getWidthMm(), o.getHeightMm(), o.getQuantity());
            BigDecimal kg = GlassProducts.weightKg(area, weightPerM2);
            culletCost = culletCost.add(cost);
            culletKg = culletKg.add(kg);
            saveOutput(job, CuttingOutputKind.CULLET, null, o.getWidthMm(), o.getHeightMm(), o.getQuantity(), area, kg,
                    null, null, null, cost, now, user);
        }
        for (CuttingResultDto.Broken b : plan.broken()) {
            BigDecimal cost = parts.next();
            BigDecimal area = rowArea(b.getWidthMm(), b.getHeightMm(), b.getQuantity());
            brokenCost = brokenCost.add(cost);
            saveOutput(job, CuttingOutputKind.BROKEN, null, b.getWidthMm(), b.getHeightMm(), b.getQuantity(), area,
                    GlassProducts.weightKg(area, weightPerM2), null, b.getReason(), PartyRules.clean(b.getNote()), cost, now, user);
        }
        if (trim.signum() > 0) {
            BigDecimal cost = parts.next();
            BigDecimal kg = GlassProducts.weightKg(trim, weightPerM2);
            culletCost = culletCost.add(cost);
            culletKg = culletKg.add(kg);
            saveOutput(job, CuttingOutputKind.CULLET, null, null, null, 1, trim, kg, null, null, null, cost, now, user);
        }
        stockService.consumeByCutting(source, job.getId(), job.getNumber());

        Cutting.Balance balance = plan.balance();
        job.setSourceCost(sourceCost);
        job.setPiecesAreaM2(balance.pieces());
        job.setOffcutAreaM2(balance.offcuts());
        job.setCulletAreaM2(balance.getCullet());
        job.setCulletKg(culletKg);
        job.setBrokenAreaM2(balance.broken());
        job.setCulletCost(culletCost);
        job.setBrokenCost(brokenCost);
        job.setYieldPercent(balance.getYieldPercent());
        job.setCompletedAt(now);
        job.setCompletedBy(AppUserPrincipal.currentUsername());
        job.setStatus(CuttingJobStatus.COMPLETED);

        BigDecimal spoilage = culletCost.add(brokenCost);
        BigDecimal stockedArea = balance.pieces().add(balance.offcuts());
        product.setMacPerM2(Costing.afterCut(heldBefore, product.getMacPerM2(), stockedArea.subtract(source.getAreaM2()),
                spoilage.negate()));
        return new CutResult(job, pieces, offcuts, balance, spoilage);
    }

    /** The cut as entered, checked: quantities per size, leftovers sorted into off-cuts and cullet, breakage, areas. */
    record Plan(Map<UUID, Integer> cutQty, int pieceCount, List<CuttingResultDto.Leftover> offcuts,
                List<CuttingResultDto.Leftover> small, List<CuttingResultDto.Broken> broken, Cutting.Balance balance) {
    }

    Plan plan(CuttingJob job, StockUnit source, CuttingResultDto dto, Threshold threshold) {
        Map<UUID, Integer> entered = new HashMap<>();
        Map<UUID, Integer> rowOf = new HashMap<>();
        for (int i = 0; i < dto.getLines().size(); i++) {
            CuttingResultDto.Line row = dto.getLines().get(i);
            entered.put(row.getLineId(), row.getCutQty());
            rowOf.put(row.getLineId(), i);
        }
        Map<UUID, Integer> cutQty = new LinkedHashMap<>();
        int pieceCount = 0;
        BigDecimal piecesArea = BigDecimal.ZERO;
        for (CuttingJobLine line : job.getLines()) {
            Integer cut = entered.get(line.getId());
            if (cut == null) {
                throw BusinessException.of("cutting.result.lineMissing", line.getLineNo());
            }
            if (cut > line.getQuantity()) {
                throw BusinessException.onField("lines[" + rowOf.get(line.getId()) + "].cutQty", "cutting.result.cutQty.over",
                        line.getQuantity());
            }
            cutQty.put(line.getId(), cut);
            pieceCount += cut;
            piecesArea = piecesArea.add(line.getPieceAreaM2().multiply(BigDecimal.valueOf(cut)));
        }

        List<CuttingResultDto.Leftover> offcuts = new ArrayList<>();
        List<CuttingResultDto.Leftover> small = new ArrayList<>();
        BigDecimal offcutArea = BigDecimal.ZERO;
        BigDecimal smallArea = BigDecimal.ZERO;
        for (int i = 0; i < dto.getLeftovers().size(); i++) {
            CuttingResultDto.Leftover o = dto.getLeftovers().get(i);
            if (!Cutting.fits(o.getWidthMm(), o.getHeightMm(), source.getWidthMm(), source.getHeightMm())) {
                throw BusinessException.onField("leftovers[" + i + "].widthMm", "cutting.result.tooBig",
                        o.getWidthMm(), o.getHeightMm(), source.getWidthMm(), source.getHeightMm());
            }
            if (Cutting.isOffcut(o.getWidthMm(), o.getHeightMm(), threshold.minAreaM2(), threshold.minSideMm())) {
                // One off-cut unit per piece, each with its own label.
                for (int k = 0; k < o.getQuantity(); k++) {
                    CuttingResultDto.Leftover one = new CuttingResultDto.Leftover();
                    one.setWidthMm(o.getWidthMm());
                    one.setHeightMm(o.getHeightMm());
                    one.setQuantity(1);
                    offcuts.add(one);
                }
                offcutArea = offcutArea.add(rowArea(o.getWidthMm(), o.getHeightMm(), o.getQuantity()));
            } else {
                small.add(o);
                smallArea = smallArea.add(rowArea(o.getWidthMm(), o.getHeightMm(), o.getQuantity()));
            }
        }

        BigDecimal brokenArea = BigDecimal.ZERO;
        for (int i = 0; i < dto.getBroken().size(); i++) {
            CuttingResultDto.Broken b = dto.getBroken().get(i);
            if (!Cutting.fits(b.getWidthMm(), b.getHeightMm(), source.getWidthMm(), source.getHeightMm())) {
                throw BusinessException.onField("broken[" + i + "].widthMm", "cutting.result.tooBig",
                        b.getWidthMm(), b.getHeightMm(), source.getWidthMm(), source.getHeightMm());
            }
            brokenArea = brokenArea.add(rowArea(b.getWidthMm(), b.getHeightMm(), b.getQuantity()));
        }

        if (pieceCount == 0 && offcuts.isEmpty() && small.isEmpty() && dto.getBroken().isEmpty()) {
            throw BusinessException.of("cutting.result.nothing");
        }
        Cutting.Balance balance = new Cutting.Balance(source.getAreaM2(), piecesArea, offcutArea, smallArea, brokenArea);
        if (!balance.isBalanced()) {
            throw BusinessException.of("cutting.result.overArea", balance.getRecorded(), source.getAreaM2(), source.getCode());
        }
        return new Plan(cutQty, pieceCount, offcuts, small, List.copyOf(dto.getBroken()), balance);
    }

    private static BigDecimal rowArea(int widthMm, int heightMm, int quantity) {
        return Pricing.areaM2(widthMm, heightMm).multiply(BigDecimal.valueOf(quantity));
    }

    private Location piecesLocation(UUID locationId, Map<UUID, Location> byId) {
        if (locationId == null) {
            throw BusinessException.onField("piecesLocationId", "cutting.result.piecesLocation.required");
        }
        return stockService.receivingLocations(byId).stream().filter(l -> l.getId().equals(locationId)).findFirst()
                .orElseThrow(() -> BusinessException.onField("piecesLocationId", "cutting.result.piecesLocation.invalid"));
    }

    private Location offcutLocation(UUID locationId, Map<UUID, Location> byId) {
        if (locationId == null) {
            throw BusinessException.onField("offcutLocationId", "cutting.result.offcutLocation.required");
        }
        return offcutPlaces(byId).stream().filter(l -> l.getId().equals(locationId)).findFirst()
                .orElseThrow(() -> BusinessException.onField("offcutLocationId", "cutting.result.offcutLocation.invalid"));
    }

    /** The racks must take the new units on top of what they hold, the source leaving its rack (MD-03). */
    private void checkRacks(CuttingJob job, StockUnit source, Plan plan, Location piecesAt, Location offcutsAt,
                            BigDecimal weightPerM2, Map<UUID, Location> byId) {
        Map<UUID, RackLoad> loads = new HashMap<>(stockService.rackLoads(byId));
        Location sourceRack = StockService.rackOf(source.getLocation(), byId);
        if (sourceRack != null) {
            loads.computeIfPresent(sourceRack.getId(), (k, l) -> l.plus(-1, source.getWeightKg().negate()));
        }
        Map<UUID, RackLoad> after = new LinkedHashMap<>();
        Map<UUID, String> fieldOf = new HashMap<>();
        if (piecesAt != null) {
            BigDecimal kg = BigDecimal.ZERO;
            for (CuttingJobLine line : job.getLines()) {
                kg = kg.add(GlassProducts.weightKg(line.getPieceAreaM2(), weightPerM2)
                        .multiply(BigDecimal.valueOf(plan.cutQty().get(line.getId()))));
            }
            add(after, fieldOf, loads, StockService.rackOf(piecesAt, byId), plan.pieceCount(), kg, "piecesLocationId");
        }
        if (offcutsAt != null) {
            BigDecimal kg = plan.offcuts().stream()
                    .map(o -> GlassProducts.weightKg(Pricing.areaM2(o.getWidthMm(), o.getHeightMm()), weightPerM2))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            add(after, fieldOf, loads, StockService.rackOf(offcutsAt, byId), plan.offcuts().size(), kg, "offcutLocationId");
        }
        for (Map.Entry<UUID, RackLoad> e : after.entrySet()) {
            Location rack = byId.get(e.getKey());
            RackLoad now = loads.getOrDefault(rack.getId(), RackLoad.EMPTY);
            if (e.getValue().exceedsPieces(rack.getMaxPieces())) {
                throw BusinessException.onField(fieldOf.get(rack.getId()), "receipt.rack.pieces", rack.getCode(), now.pieces(),
                        rack.getMaxPieces(), e.getValue().pieces() - now.pieces());
            }
            if (e.getValue().exceedsKg(rack.getMaxWeightKg())) {
                throw BusinessException.onField(fieldOf.get(rack.getId()), "receipt.rack.weight", rack.getCode(), now.kg(),
                        rack.getMaxWeightKg(), e.getValue().kg().subtract(now.kg()));
            }
        }
    }

    private static void add(Map<UUID, RackLoad> after, Map<UUID, String> fieldOf, Map<UUID, RackLoad> loads, Location rack,
                            long pieces, BigDecimal kg, String field) {
        if (rack == null) {
            return;
        }
        RackLoad base = after.getOrDefault(rack.getId(), loads.getOrDefault(rack.getId(), RackLoad.EMPTY));
        after.put(rack.getId(), base.plus(pieces, kg));
        fieldOf.putIfAbsent(rack.getId(), field);
    }

    private void saveOutput(CuttingJob job, CuttingOutputKind kind, UUID lineId, Integer widthMm, Integer heightMm, int quantity,
                            BigDecimal area, BigDecimal kg, UUID unitId, BreakageReason reason, String note, BigDecimal cost,
                            LocalDateTime at, Optional<AppUserPrincipal> user) {
        CuttingJobOutput output = new CuttingJobOutput();
        output.setCuttingJobId(job.getId());
        output.setKind(kind);
        output.setJobLineId(lineId);
        output.setWidthMm(widthMm);
        output.setHeightMm(heightMm);
        output.setQuantity(quantity);
        output.setAreaM2(area);
        output.setWeightKg(kg);
        output.setStockUnitId(unitId);
        output.setReason(reason);
        output.setNote(note);
        output.setCost(cost);
        output.setCreatedAt(at);
        output.setUserId(user.map(AppUserPrincipal::getId).orElse(null));
        output.setUsername(user.map(AppUserPrincipal::getUsername).orElse("system"));
        outputRepo.save(output);
    }

    // ---------------------------------------------------------------- cancel, cut the rest

    /** A job not cut yet. A unit taken for it is put back. The number stays. */
    @Transactional
    public CuttingJob cancel(UUID id, String reason) {
        repo.lockById(id).orElseThrow(() -> new NotFoundException("CuttingJob", id));
        CuttingJob job = findDetailed(id);
        if (job.getStatus() != CuttingJobStatus.DRAFT && job.getStatus() != CuttingJobStatus.IN_PROGRESS) {
            throw BusinessException.of("cutting.cancel.notOpen", job.getNumber());
        }
        if (job.getStatus() == CuttingJobStatus.IN_PROGRESS) {
            releaseSource(job, reason);
        }
        job.setStatus(CuttingJobStatus.CANCELLED);
        job.setCancelReason(reason.trim());
        return job;
    }

    /** A new draft for the pieces a completed job did not cut, linked to it (cut from another source). */
    @Transactional
    public CuttingJob cutRest(UUID id) {
        CuttingJob job = findDetailed(id);
        if (!job.isShort()) {
            throw BusinessException.of("cutting.rest.nothing", job.getNumber());
        }
        List<CuttingJob> existing = restJobs(id);
        if (!existing.isEmpty()) {
            throw BusinessException.of("cutting.rest.exists", job.getNumber(), existing.get(0).getNumber());
        }
        CuttingJob rest = new CuttingJob();
        rest.setNumber(numbers.next(DocumentType.CUTTING_JOB));
        rest.setPurpose(job.getPurpose());
        rest.setCustomer(job.getCustomer());
        rest.setCustomerRef(job.getCustomerRef());
        rest.setProduct(job.getProduct());
        rest.setDueDate(job.getDueDate());
        rest.setNotes(job.getNotes());
        rest.setParentJobId(job.getId());
        int no = 0;
        for (CuttingJobLine line : job.getLines()) {
            if (line.getShortQty() <= 0) {
                continue;
            }
            CuttingJobLine copy = new CuttingJobLine();
            copy.setJob(rest);
            copy.setLineNo(++no);
            copy.setWidthMm(line.getWidthMm());
            copy.setHeightMm(line.getHeightMm());
            copy.setQuantity(line.getShortQty());
            copy.setProcessing(line.getProcessing());
            copy.setMark(line.getMark());
            rest.getLines().add(copy);
        }
        return repo.save(rest);
    }

    // ---------------------------------------------------------------- yield (PRD-09)

    /** Cuts completed from {@code from} to {@code to} (inclusive), optionally of one product or operator. */
    public YieldReport yieldReport(LocalDate from, LocalDate to, UUID productId, String operator) {
        List<CuttingJob> jobs = repo.findByStatusAndCompletedAtGreaterThanEqualAndCompletedAtLessThanOrderByCompletedAtAsc(
                        CuttingJobStatus.COMPLETED, from.atStartOfDay(), to.plusDays(1).atStartOfDay()).stream()
                .filter(j -> productId == null || j.getProduct().getId().equals(productId))
                .filter(j -> !StringUtils.hasText(operator) || operator.equals(j.getOperatorName()))
                .toList();
        List<CuttingJobOutput> outputs = jobs.isEmpty() ? List.of()
                : outputRepo.findByCuttingJobIdIn(jobs.stream().map(CuttingJob::getId).toList());
        return new YieldReport(from, to, CuttingYield.total(jobs),
                CuttingYield.by(jobs, CuttingJob::getOperatorName, CuttingJob::getOperatorName),
                CuttingYield.by(jobs, j -> j.getProduct().getId().toString(), j -> j.getProduct().getCode()),
                CuttingYield.breakage(outputs));
    }

    /** Operators who completed cuts, for the report filter. */
    public List<String> operators() {
        return repo.findOperators(CuttingJobStatus.COMPLETED);
    }

    /** Every product, for the report filter. */
    public List<Product> allProducts() {
        return productRepo.findAll(Sort.by("code"));
    }

    // ---------------------------------------------------------------- helpers

    private List<Location> offcutPlaces(Map<UUID, Location> byId) {
        return byId.values().stream()
                .filter(Location::isEnabled)
                .filter(l -> l.getType() == LocationType.RACK || l.getType() == LocationType.SLOT)
                .filter(l -> {
                    Location rack = StockService.rackOf(l, byId);
                    return rack != null && rack.isOffcut() && rack.isEnabled();
                })
                .sorted(Comparator.comparing(Location::getCode))
                .toList();
    }

    private static LocationChoice choice(Location l, Map<UUID, Location> byId, Map<UUID, RackLoad> loads) {
        Location rack = StockService.rackOf(l, byId);
        return new LocationChoice(l, rack, loads.getOrDefault(rack.getId(), RackLoad.EMPTY));
    }

    private static void requireStatus(CuttingJob job, CuttingJobStatus status, String key) {
        if (job.getStatus() != status) {
            throw BusinessException.of(key, job.getNumber());
        }
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

}
