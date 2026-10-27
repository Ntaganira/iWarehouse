package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.dto.FixedAssetDto;
import com.ntaganira.heritier.iWarehouse.entity.*;
import com.ntaganira.heritier.iWarehouse.enums.*;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.*;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : FixedAssetService.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The fixed-asset register (C.08, U.12). Each capital item of an approved expense bill becomes an asset at its cost
 *               (category Other, its life from the category, depreciated from the month after the bill); an asset owned before
 *               the register is registered at its value to date (PERM_MANAGE_FIXED_ASSETS: PostingService.assetOpening). Its
 *               category, residual value, life and first month change until it is depreciated; its name, serial, cost centre
 *               and place at any time. Each month is run once, in order, once it has ended, in an open period
 *               (PERM_RUN_DEPRECIATION): every asset in use takes FixedAssets.monthly, a DepreciationLine each, and
 *               PostingService.depreciation posts the total; the latest run is reversed with a reason, never while an asset
 *               in it has been disposed of since. Disposing (PERM_DISPOSE_ASSET) takes its book value out against what it
 *               fetched (PostingService.assetDisposal), with a reason. A bill reversed before its assets are depreciated
 *               cancels them. Once runs have started, a month does not close without its run (missingRun).
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class FixedAssetService {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MM/yyyy");
    private static final Set<String> METHODS = Set.of("CASH_VAULT", "BANK", "MOBILE_MONEY");

    private final FixedAssetRepository repo;
    private final DepreciationRunRepository runRepo;
    private final DepreciationLineRepository lineRepo;
    private final CostCentreRepository costCentreRepo;
    private final ExpenseBillRepository billRepo;
    private final StockService stockService;
    private final PostingService postingService;
    private final JournalService journalService;
    private final DocumentNumberService numbers;
    private final PeriodLock periods;
    private final Clock clock;

    public FixedAssetService(FixedAssetRepository repo, DepreciationRunRepository runRepo, DepreciationLineRepository lineRepo,
                             CostCentreRepository costCentreRepo, ExpenseBillRepository billRepo, StockService stockService,
                             PostingService postingService,
                             JournalService journalService, DocumentNumberService numbers, PeriodLock periods, Clock clock) {
        this.repo = repo;
        this.runRepo = runRepo;
        this.lineRepo = lineRepo;
        this.costCentreRepo = costCentreRepo;
        this.billRepo = billRepo;
        this.stockService = stockService;
        this.postingService = postingService;
        this.journalService = journalService;
        this.numbers = numbers;
        this.periods = periods;
        this.clock = clock;
    }

    /** An asset with its depreciation to date. */
    public record Row(FixedAsset asset, BigDecimal accumulated) {

        public BigDecimal getBookValue() {
            return FixedAssets.bookValue(asset.getCost(), accumulated);
        }
    }

    /** The register's totals over the assets in use. */
    public record Totals(long assets, BigDecimal cost, BigDecimal accumulated) {

        public BigDecimal getBookValue() {
            return cost.subtract(accumulated);
        }
    }

    /** A month's depreciation before it is posted: each asset's share and the total; why it cannot run, if it cannot. */
    public record Preview(YearMonth month, List<PreviewLine> lines, String blockedKey, Object[] blockedArgs) {

        public BigDecimal getTotal() {
            return lines.stream().map(PreviewLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        public boolean isRunnable() {
            return blockedKey == null && !lines.isEmpty();
        }

        public LocalDate getPeriodEnd() {
            return month.atEndOfMonth();
        }
    }

    public record PreviewLine(FixedAsset asset, BigDecimal accumulated, BigDecimal amount) {

        public BigDecimal getBookValueAfter() {
            return asset.getCost().subtract(accumulated).subtract(amount);
        }
    }

    /** A disposal as the form gives it. */
    public record Disposal(LocalDate disposedOn, BigDecimal proceeds, String method, String reference, String reason) {
    }

    // ---------------------------------------------------------------- reading

    public Page<FixedAsset> findPage(String search, String status, String category, int page, int size) {
        Specification<FixedAsset> spec = (root, query, cb) -> {
            Predicate p = cb.conjunction();
            if (StringUtils.hasText(search)) {
                String term = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                p = cb.and(p, cb.or(cb.like(cb.lower(root.get("number")), term), cb.like(cb.lower(root.get("name")), term),
                        cb.like(cb.lower(cb.coalesce(root.get("serialNumber"), "")), term)));
            }
            FixedAssetStatus st = parse(FixedAssetStatus.class, status);
            if (st != null) {
                p = cb.and(p, cb.equal(root.get("status"), st));
            }
            FixedAssetCategory cat = parse(FixedAssetCategory.class, category);
            if (cat != null) {
                p = cb.and(p, cb.equal(root.get("category"), cat));
            }
            return p;
        };
        return repo.findAll(spec, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "number")));
    }

    public FixedAsset findDetailed(UUID id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException("FixedAsset", id));
    }

    /** Depreciation to date of each asset: before the register and in posted runs. */
    public Map<UUID, BigDecimal> accumulatedOf(Collection<FixedAsset> assets) {
        Map<UUID, BigDecimal> result = new HashMap<>();
        assets.forEach(a -> result.put(a.getId(), a.getOpeningAccumulated()));
        if (!assets.isEmpty()) {
            for (Object[] r : lineRepo.postedByAsset(assets.stream().map(FixedAsset::getId).toList())) {
                result.merge((UUID) r[0], (BigDecimal) r[1], BigDecimal::add);
            }
        }
        return result;
    }

    public BigDecimal accumulated(FixedAsset asset) {
        return accumulatedOf(List.of(asset)).get(asset.getId());
    }

    public Row row(FixedAsset asset) {
        return new Row(asset, accumulated(asset));
    }

    public Totals totals() {
        List<FixedAsset> active = repo.findByStatusOrderByNumberAsc(FixedAssetStatus.ACTIVE);
        Map<UUID, BigDecimal> acc = accumulatedOf(active);
        BigDecimal cost = active.stream().map(FixedAsset::getCost).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal accumulated = acc.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Totals(active.size(), cost, accumulated);
    }

    /** The asset's depreciation lines with their run, newest first. */
    public Page<Object[]> depreciationOf(UUID assetId, int page, int size) {
        return lineRepo.findWithRunByAsset(assetId, PageRequest.of(page, size));
    }

    public boolean isDepreciated(FixedAsset asset) {
        return lineRepo.isDepreciated(asset.getId());
    }

    /** The journals of an asset: its opening, its disposal. */
    public List<JournalEntry> journals(FixedAsset asset) {
        List<JournalEntry> journals = new ArrayList<>();
        if (asset.getOpeningJournalId() != null) {
            journals.add(journalService.findById(asset.getOpeningJournalId()));
        }
        if (asset.getDisposalJournalId() != null) {
            journals.add(journalService.findById(asset.getDisposalJournalId()));
        }
        return journals;
    }

    /** The assets a bill's capital items became, by number. */
    public List<FixedAsset> ofBill(ExpenseBill bill) {
        List<UUID> lineIds = bill.getLines().stream().map(ExpenseBillLine::getId).toList();
        if (lineIds.isEmpty()) {
            return List.of();
        }
        return repo.findByExpenseBillLineIdIn(lineIds).stream().sorted(Comparator.comparing(FixedAsset::getNumber)).toList();
    }

    /** The expense bill an asset came from (empty for an asset registered at its value to date). */
    public Optional<ExpenseBill> billOf(FixedAsset asset) {
        return asset.getExpenseBillLineId() == null ? Optional.empty() : billRepo.findByLineId(asset.getExpenseBillLineId());
    }

    /** Every cost centre by id: the register names each asset's. */
    public Map<UUID, CostCentre> costCentres() {
        Map<UUID, CostCentre> map = new LinkedHashMap<>();
        costCentreRepo.findAll(Sort.by("code")).forEach(c -> map.put(c.getId(), c));
        return map;
    }

    public List<CostCentre> activeCostCentres() {
        return costCentreRepo.findByEnabledTrueOrderByCodeAsc();
    }

    /** Places an asset may be at: the sites and the vehicles. */
    public List<Location> places() {
        return stockService.locationsById().values().stream().filter(Location::isEnabled).filter(l -> l.getParentId() == null)
                .sorted(Comparator.comparing(Location::getCode)).toList();
    }

    public Map<UUID, Location> placesById() {
        return stockService.locationsById();
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    // ---------------------------------------------------------------- the register

    public FixedAssetDto newForm() {
        FixedAssetDto dto = new FixedAssetDto();
        dto.setCategory(FixedAssetCategory.OTHER);
        dto.setUsefulLifeMonths(FixedAssetCategory.OTHER.lifeMonths());
        dto.setResidualValue(BigDecimal.ZERO);
        dto.setOpeningAccumulated(BigDecimal.ZERO);
        dto.setDepreciateFrom(nextRun().atDay(1));
        return dto;
    }

    public FixedAssetDto formOf(FixedAsset a) {
        FixedAssetDto dto = new FixedAssetDto();
        dto.setName(a.getName());
        dto.setCategory(a.getCategory());
        dto.setCostCentreId(a.getCostCentreId());
        dto.setLocationId(a.getLocationId());
        dto.setSerialNumber(a.getSerialNumber());
        dto.setAcquiredOn(a.getAcquiredOn());
        dto.setCost(a.getCost());
        dto.setResidualValue(a.getResidualValue());
        dto.setUsefulLifeMonths(a.getUsefulLifeMonths());
        dto.setDepreciateFrom(a.getDepreciateFrom());
        dto.setOpeningAccumulated(a.getOpeningAccumulated());
        return dto;
    }

    /**
     * The capital items of a bill being approved (C.08), one asset each at its amount before VAT: category Other with its life,
     * depreciated from the month after the bill. MANDATORY: inside the approval.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<FixedAsset> fromBill(ExpenseBill bill) {
        List<FixedAsset> assets = new ArrayList<>();
        for (ExpenseBillLine l : bill.getLines()) {
            if (!l.getCategory().isCapital()) {
                continue;
            }
            FixedAsset a = new FixedAsset();
            a.setNumber(numbers.next(DocumentType.FIXED_ASSET));
            a.setName(l.getDescription().length() > 150 ? l.getDescription().substring(0, 150) : l.getDescription());
            a.setCategory(FixedAssetCategory.OTHER);
            a.setSource(FixedAssetSource.EXPENSE_BILL);
            a.setExpenseBillLineId(l.getId());
            a.setCostCentreId(l.getCostCentre().getId());
            a.setLocationId(l.getLocationId());
            a.setAcquiredOn(bill.getBillDate());
            a.setCost(l.getNetAmount());
            a.setResidualValue(BigDecimal.ZERO.setScale(2));
            a.setUsefulLifeMonths(FixedAssetCategory.OTHER.lifeMonths());
            a.setDepreciateFrom(laterOf(FixedAssets.defaultDepreciateFrom(bill.getBillDate()), firstOpenMonth()));
            assets.add(repo.save(a));
        }
        return assets;
    }

    /**
     * A capital bill reversed: its assets are cancelled, refused while one of them has been depreciated or disposed of (reverse
     * the runs first, or dispose of the asset instead). MANDATORY: inside the reversal, before its journal.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void billReversed(ExpenseBill bill, LocalDate day, String reason) {
        List<UUID> lineIds = bill.getLines().stream().map(ExpenseBillLine::getId).toList();
        for (FixedAsset found : repo.findByExpenseBillLineIdIn(lineIds)) {
            FixedAsset a = lock(found.getId());
            if (a.getStatus() == FixedAssetStatus.CANCELLED) {
                continue;
            }
            if (a.getStatus() == FixedAssetStatus.DISPOSED || lineRepo.isDepreciated(a.getId())) {
                throw BusinessException.of("asset.billReversed.depreciated", bill.getNumber(), a.getNumber());
            }
            a.setDisposedOn(day);
            a.setDisposalReason(reason);
            a.setDisposedBy(AppUserPrincipal.currentUsername());
            a.setStatus(FixedAssetStatus.CANCELLED);
        }
    }

    /** Registers an asset owned before the register at its value to date, posted on today's date (PostingService.assetOpening). */
    @Transactional
    public FixedAsset register(FixedAssetDto dto) {
        LocalDate today = today();
        periods.requireOpen(today);
        BigDecimal cost = dto.getCost().setScale(2, RoundingMode.HALF_UP);
        BigDecimal opening = dto.getOpeningAccumulated() == null ? BigDecimal.ZERO.setScale(2) : dto.getOpeningAccumulated().setScale(2, RoundingMode.HALF_UP);
        FixedAsset a = new FixedAsset();
        a.setSource(FixedAssetSource.OPENING);
        a.setCost(cost);
        a.setOpeningAccumulated(opening);
        a.setAcquiredOn(dto.getAcquiredOn());
        apply(a, dto, false);
        if (opening.compareTo(a.getDepreciable()) > 0) {
            throw BusinessException.onField("openingAccumulated", "asset.opening.tooHigh");
        }
        a.setNumber(numbers.next(DocumentType.FIXED_ASSET));
        repo.save(a);
        JournalEntry entry = postingService.assetOpening(a, today);
        a.setOpeningJournalId(entry.getId());
        return a;
    }

    /** Changes an asset in use: its name, serial, cost centre and place at any time; its category, residual, life and first month until depreciated. */
    @Transactional
    public FixedAsset update(UUID id, FixedAssetDto dto) {
        FixedAsset a = lock(id);
        if (!a.isActive()) {
            throw BusinessException.of("asset.notActive", a.getNumber());
        }
        apply(a, dto, lineRepo.isDepreciated(a.getId()));
        if (a.getOpeningAccumulated().compareTo(a.getDepreciable()) > 0) {
            throw BusinessException.onField("residualValue", "asset.residual.tooHigh");
        }
        return a;
    }

    /** Disposes of an asset in use: sold (what it fetched, how it was paid) or scrapped, with a reason (PostingService.assetDisposal). */
    @Transactional
    public FixedAsset dispose(UUID id, Disposal form) {
        FixedAsset a = lock(id);
        if (!a.isActive()) {
            throw BusinessException.of("asset.notActive", a.getNumber());
        }
        LocalDate day = form.disposedOn() == null ? today() : form.disposedOn();
        if (day.isAfter(today())) {
            throw BusinessException.onField("disposedOn", "asset.dispose.future");
        }
        if (day.isBefore(a.getAcquiredOn())) {
            throw BusinessException.onField("disposedOn", "asset.dispose.early", a.getAcquiredOn().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")));
        }
        periods.requireOpen(day, "disposedOn");
        YearMonth lastRun = lastMonthDepreciated(a);
        if (lastRun != null && lastRun.isAfter(YearMonth.from(day))) {
            throw BusinessException.onField("disposedOn", "asset.dispose.depreciatedAfter", lastRun.format(MONTH));
        }
        BigDecimal proceeds = form.proceeds() == null ? BigDecimal.ZERO : form.proceeds();
        if (proceeds.signum() < 0 || proceeds.stripTrailingZeros().scale() > 2) {
            throw BusinessException.onField("proceeds", "asset.proceeds.invalid");
        }
        String method = null;
        String reference = PartyRules.clean(form.reference());
        if (proceeds.signum() > 0) {
            method = form.method();
            if (method == null || !METHODS.contains(method)) {
                throw BusinessException.onField("method", "asset.method.required");
            }
            if (!"CASH_VAULT".equals(method) && reference == null) {
                throw BusinessException.onField("reference", "asset.reference.required");
            }
            if (reference != null && reference.length() > 60) {
                throw BusinessException.onField("reference", "asset.reference.size");
            }
        }
        String reason = PartyRules.clean(form.reason());
        if (reason == null || reason.length() > 500) {
            throw BusinessException.onField("reason", "asset.reason.required");
        }
        BigDecimal accumulated = accumulated(a);
        a.setDisposedOn(day);
        a.setProceeds(proceeds.setScale(2, RoundingMode.HALF_UP));
        a.setProceedsMethod(method);
        a.setProceedsReference(method == null ? null : reference);
        a.setDisposalReason(reason);
        a.setDisposedBy(AppUserPrincipal.currentUsername());
        JournalEntry entry = postingService.assetDisposal(a, accumulated);
        a.setDisposalJournalId(entry.getId());
        a.setStatus(FixedAssetStatus.DISPOSED);
        return a;
    }

    // ---------------------------------------------------------------- depreciation runs

    public Page<DepreciationRun> runs(int page, int size) {
        return runRepo.findAllByOrderByPeriodDescRunAtDesc(PageRequest.of(page, size));
    }

    public DepreciationRun findRun(UUID id) {
        return runRepo.findById(id).orElseThrow(() -> new NotFoundException("DepreciationRun", id));
    }

    public Page<DepreciationLine> runLines(UUID runId, int page, int size) {
        return lineRepo.findByRunIdOrderByAsset_NumberAsc(runId, PageRequest.of(page, size));
    }

    public Optional<DepreciationRun> latestPosted() {
        return runRepo.findFirstByStatusOrderByPeriodDesc(DepreciationRunStatus.POSTED);
    }

    /** The month to run next: after the latest posted run, or, before the first, last month unless it is closed (then the first open one). */
    public YearMonth nextRun() {
        YearMonth latest = latestPosted().map(r -> YearMonth.from(r.getPeriod())).orElse(null);
        YearMonth next = FixedAssets.nextRun(latest, today());
        LocalDate closed = periods.closedThrough();
        if (latest == null && closed != null && !next.isAfter(YearMonth.from(closed))) {
            next = YearMonth.from(closed).plusMonths(1);
        }
        return next;
    }

    /** Whether a run of the month has started already: the first posted run's month. */
    public Optional<YearMonth> firstRun() {
        return runRepo.findFirstByStatusOrderByPeriodAsc(DepreciationRunStatus.POSTED).map(r -> YearMonth.from(r.getPeriod()));
    }

    /** The posted run of a month, if it has one. */
    public Optional<DepreciationRun> postedRun(YearMonth month) {
        return runRepo.findByPeriodAndStatus(month.atDay(1), DepreciationRunStatus.POSTED);
    }

    /** Whether a month is the latest posted run's or before it: nothing is left to preview there. */
    public boolean isRunAlready(YearMonth month) {
        return latestPosted().map(r -> !month.isAfter(YearMonth.from(r.getPeriod()))).orElse(false);
    }

    /**
     * What a month's run would post, and why it cannot run if it cannot. A month already run, or before the latest run, has no
     * lines: its depreciation to date would count runs made after it.
     */
    public Preview preview(YearMonth month) {
        List<FixedAsset> active = repo.findByStatusOrderByNumberAsc(FixedAssetStatus.ACTIVE);
        List<PreviewLine> lines = isRunAlready(month) ? List.of() : linesFor(active, month);
        String[] blocked = blocked(month);
        return new Preview(month, lines, blocked == null ? null : blocked[0], blocked == null ? new Object[0] : Arrays.copyOfRange(blocked, 1, blocked.length));
    }

    /** Posts a month's depreciation (C.08, U.12): the assets in use locked, each one's share a line, one journal on the month's last day. */
    @Transactional
    public DepreciationRun run(YearMonth month) {
        String[] blocked = blocked(month);
        if (blocked != null) {
            throw BusinessException.of(blocked[0], (Object[]) Arrays.copyOfRange(blocked, 1, blocked.length));
        }
        periods.requireOpen(month.atEndOfMonth());
        List<PreviewLine> lines = linesFor(repo.lockActive(), month);
        if (lines.isEmpty()) {
            throw BusinessException.of("depreciation.nothing", month.format(MONTH));
        }
        DepreciationRun run = new DepreciationRun();
        run.setNumber(numbers.next(DocumentType.DEPRECIATION_RUN));
        run.setPeriod(month.atDay(1));
        run.setTotal(lines.stream().map(PreviewLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add));
        run.setAssets(lines.size());
        run.setRunBy(AppUserPrincipal.currentUsername());
        run.setRunAt(LocalDateTime.now(clock));
        runRepo.save(run);
        List<DepreciationLine> saved = new ArrayList<>();
        for (PreviewLine p : lines) {
            DepreciationLine l = new DepreciationLine();
            l.setRunId(run.getId());
            l.setAsset(p.asset());
            l.setAmount(p.amount());
            l.setAccumulatedAfter(p.accumulated().add(p.amount()));
            saved.add(lineRepo.save(l));
        }
        JournalEntry entry = postingService.depreciation(run, saved);
        run.setJournalId(entry.getId());
        return run;
    }

    /** Reverses the latest posted run with a reason, on its own date (the month's last day): refused while an asset in it has been disposed of. */
    @Transactional
    public DepreciationRun reverse(UUID id, String reason) {
        DepreciationRun run = runRepo.lockById(id).orElseThrow(() -> new NotFoundException("DepreciationRun", id));
        if (!run.isPosted()) {
            throw BusinessException.of("depreciation.notPosted", run.getNumber());
        }
        DepreciationRun latest = latestPosted().orElseThrow();
        if (!latest.getId().equals(run.getId())) {
            throw BusinessException.of("depreciation.notLatest", run.getNumber(), latest.getNumber());
        }
        String clean = PartyRules.clean(reason);
        if (clean == null || clean.length() > 500) {
            throw BusinessException.onField("reason", "depreciation.reason.required");
        }
        for (DepreciationLine l : lineRepo.findByRunIdOrderByAsset_NumberAsc(run.getId())) {
            if (l.getAsset().getStatus() == FixedAssetStatus.DISPOSED) {
                throw BusinessException.of("depreciation.reverse.disposed", run.getNumber(), l.getAsset().getNumber());
            }
        }
        LocalDate day = run.getPeriod().withDayOfMonth(run.getPeriod().lengthOfMonth());
        periods.requireOpen(day);
        JournalEntry reversal = journalService.reverse(journalService.findById(run.getJournalId()), day,
                "Reversal of " + run.getNumber() + ": " + clean);
        run.setReversalJournalId(reversal.getId());
        run.setReversalReason(clean);
        run.setReversedBy(AppUserPrincipal.currentUsername());
        run.setReversedAt(LocalDateTime.now(clock));
        run.setStatus(DepreciationRunStatus.REVERSED);
        return run;
    }

    /**
     * Whether closing a month waits for its run (U.12): once runs have started, a month after the first run with no posted run while
     * an asset in use is due to be depreciated in it.
     */
    public boolean missingRun(YearMonth month) {
        Optional<YearMonth> first = firstRun();
        if (first.isEmpty() || !month.isAfter(first.get())) {
            return false;
        }
        if (runRepo.findByPeriodAndStatus(month.atDay(1), DepreciationRunStatus.POSTED).isPresent()) {
            return false;
        }
        return !linesFor(repo.findByStatusOrderByNumberAsc(FixedAssetStatus.ACTIVE), month).isEmpty();
    }

    // ---------------------------------------------------------------- helpers

    private List<PreviewLine> linesFor(List<FixedAsset> assets, YearMonth month) {
        Map<UUID, BigDecimal> acc = accumulatedOf(assets);
        List<PreviewLine> lines = new ArrayList<>();
        for (FixedAsset a : assets) {
            if (a.getAcquiredOn().isAfter(month.atEndOfMonth())) {
                continue;
            }
            BigDecimal accumulated = acc.getOrDefault(a.getId(), BigDecimal.ZERO);
            BigDecimal amount = FixedAssets.monthly(a.getAcquiredOn(), a.getDepreciateFrom(), a.getUsefulLifeMonths(), a.getDepreciable(),
                    accumulated, month);
            if (amount.signum() > 0) {
                lines.add(new PreviewLine(a, accumulated, amount));
            }
        }
        return lines;
    }

    /** Why a month cannot run: [key, args...], or null. */
    private String[] blocked(YearMonth month) {
        if (!FixedAssets.mayRun(month, today())) {
            return new String[]{"depreciation.notEnded", month.format(MONTH)};
        }
        Optional<DepreciationRun> latest = latestPosted();
        if (runRepo.findByPeriodAndStatus(month.atDay(1), DepreciationRunStatus.POSTED).isPresent()) {
            return new String[]{"depreciation.exists", month.format(MONTH)};
        }
        if (latest.isPresent() && !month.equals(YearMonth.from(latest.get().getPeriod()).plusMonths(1))) {
            return new String[]{"depreciation.order", month.format(MONTH), YearMonth.from(latest.get().getPeriod()).plusMonths(1).format(MONTH)};
        }
        LocalDate closed = periods.closedThrough();
        if (closed != null && !month.atEndOfMonth().isAfter(closed)) {
            return new String[]{"depreciation.closed", month.format(MONTH)};
        }
        return null;
    }

    private void apply(FixedAsset a, FixedAssetDto dto, boolean depreciated) {
        a.setName(dto.getName().trim());
        a.setSerialNumber(StringUtils.hasText(dto.getSerialNumber()) ? dto.getSerialNumber().trim() : null);
        if (dto.getCostCentreId() != null && costCentreRepo.findById(dto.getCostCentreId()).isEmpty()) {
            throw BusinessException.onField("costCentreId", "asset.costCentre.unknown");
        }
        a.setCostCentreId(dto.getCostCentreId());
        if (dto.getLocationId() != null && !stockService.locationsById().containsKey(dto.getLocationId())) {
            throw BusinessException.onField("locationId", "asset.location.unknown");
        }
        a.setLocationId(dto.getLocationId());
        BigDecimal residual = dto.getResidualValue() == null ? BigDecimal.ZERO.setScale(2) : dto.getResidualValue().setScale(2, RoundingMode.HALF_UP);
        LocalDate from = dto.getDepreciateFrom() == null ? FixedAssets.defaultDepreciateFrom(a.getAcquiredOn()) : dto.getDepreciateFrom().withDayOfMonth(1);
        if (depreciated) {
            boolean changed = dto.getCategory() != a.getCategory() || residual.compareTo(a.getResidualValue()) != 0
                    || !Objects.equals(dto.getUsefulLifeMonths(), a.getUsefulLifeMonths()) || !from.equals(a.getDepreciateFrom());
            if (changed) {
                throw BusinessException.of("asset.fixedOnceDepreciated", a.getNumber());
            }
            return;
        }
        if (a.getSource() == FixedAssetSource.OPENING && a.getId() == null && dto.getAcquiredOn().isAfter(today())) {
            throw BusinessException.onField("acquiredOn", "asset.acquiredOn.future");
        }
        if (residual.compareTo(a.getCost()) >= 0) {
            throw BusinessException.onField("residualValue", "asset.residual.tooHigh");
        }
        if (from.isBefore(a.getAcquiredOn().withDayOfMonth(1))) {
            throw BusinessException.onField("depreciateFrom", "asset.depreciateFrom.early");
        }
        Optional<DepreciationRun> latest = latestPosted();
        if (latest.isPresent() && !YearMonth.from(from).isAfter(YearMonth.from(latest.get().getPeriod()))) {
            throw BusinessException.onField("depreciateFrom", "asset.depreciateFrom.run", YearMonth.from(latest.get().getPeriod()).format(MONTH));
        }
        a.setCategory(dto.getCategory());
        a.setResidualValue(residual);
        a.setUsefulLifeMonths(dto.getUsefulLifeMonths());
        a.setDepreciateFrom(from);
    }

    /** The first month an asset may still be depreciated from: after the latest posted run, if any. */
    private LocalDate firstOpenMonth() {
        return latestPosted().map(r -> r.getPeriod().plusMonths(1)).orElse(null);
    }

    private static LocalDate laterOf(LocalDate a, LocalDate b) {
        return b == null || a.isAfter(b) ? a : b;
    }

    private YearMonth lastMonthDepreciated(FixedAsset a) {
        Page<Object[]> latest = lineRepo.findWithRunByAsset(a.getId(), PageRequest.of(0, 20));
        for (Object[] r : latest.getContent()) {
            DepreciationRun run = (DepreciationRun) r[1];
            if (run.isPosted()) {
                return YearMonth.from(run.getPeriod());
            }
        }
        return null;
    }

    private FixedAsset lock(UUID id) {
        return repo.lockById(id).orElseThrow(() -> new NotFoundException("FixedAsset", id));
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
