package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.dto.ShipmentDto;
import com.ntaganira.heritier.iWarehouse.entity.Currency;
import com.ntaganira.heritier.iWarehouse.entity.*;
import com.ntaganira.heritier.iWarehouse.enums.*;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.*;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import com.ntaganira.heritier.iWarehouse.service.ExchangeRateService.AppliedRate;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * - File      : ShipmentService.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Shipments and their import costs (PRC-03..06). A shipment links the posted receipts that
 *               came in it and lists its bills (freight, insurance, duty, clearing, port, transport), each
 *               in its own currency. Posting converts the draft bills at the rate of each bill's date
 *               (customs rate for duty), rounds the total once, shares it between the crates by area,
 *               value or weight, and adds each sheet's part to its cost through StockService; the parts
 *               of sheets no longer in stock are expensed, those of sheets broken on arrival go to the
 *               claim. The products' moving average cost moves with what reached stock. Bills arriving
 *               later are posted the same way; a posted bill is corrected by a credit note.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class ShipmentService {

    private final ShipmentRepository repo;
    private final ShipmentReceiptRepository linkRepo;
    private final ShipmentCostRepository costRepo;
    private final ShipmentAllocationRepository allocationRepo;
    private final GoodsReceiptRepository receiptRepo;
    private final CrateBatchRepository crateRepo;
    private final ProductRepository productRepo;
    private final SupplierRepository supplierRepo;
    private final CurrencyRepository currencyRepo;
    private final StockService stockService;
    private final PostingService postingService;
    private final ExchangeRateService rateService;
    private final DocumentNumberService numbers;
    private final SettingService settingService;
    private final Clock clock;

    public ShipmentService(ShipmentRepository repo, ShipmentReceiptRepository linkRepo, ShipmentCostRepository costRepo,
                           ShipmentAllocationRepository allocationRepo, GoodsReceiptRepository receiptRepo,
                           CrateBatchRepository crateRepo, ProductRepository productRepo, SupplierRepository supplierRepo,
                           CurrencyRepository currencyRepo, StockService stockService, PostingService postingService,
                           ExchangeRateService rateService,
                           DocumentNumberService numbers, SettingService settingService, Clock clock) {
        this.repo = repo;
        this.linkRepo = linkRepo;
        this.costRepo = costRepo;
        this.allocationRepo = allocationRepo;
        this.receiptRepo = receiptRepo;
        this.crateRepo = crateRepo;
        this.productRepo = productRepo;
        this.supplierRepo = supplierRepo;
        this.currencyRepo = currencyRepo;
        this.stockService = stockService;
        this.postingService = postingService;
        this.rateService = rateService;
        this.numbers = numbers;
        this.settingService = settingService;
        this.clock = clock;
    }

    /** Receipts, RWF posted and draft bills of a shipment, for the list. */
    public record ListTotals(long receipts, BigDecimal posted, long drafts) {
        public static final ListTotals NONE = new ListTotals(0, BigDecimal.ZERO, 0);
    }

    /** A draft bill at the rate posting would use now, or why it can't be posted yet. */
    public record CostPreview(AppliedRate rate, BigDecimal base, String errorKey, Object[] errorArgs) {

        public boolean ok() {
            return errorKey == null;
        }
    }

    /**
     * A crate on the cost sheet: its basis, the landed cost posted to it and what the draft bills would add
     * (null when they can't be estimated).
     */
    public record CrateLine(CrateBatch crate, LandedCost.CrateBasis basis, BigDecimal allocated, BigDecimal brokenShare,
                            BigDecimal pending) {

        public BigDecimal getExtraPerM2() {
            return LandedCost.perM2(allocated, basis.area());
        }

        /** Purchase cost per m² plus the landed cost posted, per m² shipped. */
        public BigDecimal getLandedPerM2() {
            return crate.getCostPerM2().add(getExtraPerM2());
        }

        public BigDecimal getPendingPerM2() {
            return pending == null ? null : LandedCost.perM2(pending, basis.area());
        }

        /** What the sheets broken on arrival cost: their purchase cost plus their share of the posted costs. */
        public BigDecimal getBrokenValue() {
            return Costing.unitCost(crate.getSheetArea(), crate.getCostPerM2()).multiply(BigDecimal.valueOf(crate.getBroken()))
                    .add(brokenShare);
        }
    }

    /** One posting: when, who, and where its RWF went. */
    public record Posting(int number, LocalDateTime at, String username, BigDecimal amount, BigDecimal toStock,
                          BigDecimal expensed, BigDecimal broken) {
    }

    /** Totals of the cost sheet. pending is null when a draft bill has no usable rate yet. */
    public record Summary(int crates, int sheets, int broken, BigDecimal area, BigDecimal posted, BigDecimal toStock,
                          BigDecimal expensed, BigDecimal brokenShare, int draftLines, BigDecimal pending,
                          BigDecimal brokenValue) {

        public BigDecimal getExtraPerM2() {
            return LandedCost.perM2(posted, area);
        }
    }

    /** Everything the shipment page shows, read in one transaction. */
    public record CostSheet(Shipment shipment, List<CrateLine> crates, Map<UUID, CostPreview> previews,
                            List<Posting> postings, Summary summary) {
    }

    /** A posting done: its number, how many bills, and where the RWF went. */
    public record PostResult(Shipment shipment, int postingNo, int lines, BigDecimal total, BigDecimal toStock,
                             BigDecimal expensed, BigDecimal broken, int units) {
    }

    /** The landed cost posted to a receipt's crates, per m² shipped, and the shipment it came from. */
    public record ReceiptLanding(Shipment shipment, Map<UUID, BigDecimal> extraPerM2) {
    }

    // ---------------------------------------------------------------- reading

    /** status: a ShipmentStatus name or empty for all. */
    public Page<Shipment> findPage(String search, String status, int page, int size) {
        Specification<Shipment> spec = (root, query, cb) -> {
            Predicate p = cb.conjunction();
            if (StringUtils.hasText(search)) {
                String term = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                p = cb.and(p, cb.or(
                        cb.like(cb.lower(root.get("number")), term),
                        cb.like(cb.lower(cb.coalesce(root.get("reference"), "")), term),
                        cb.like(cb.lower(cb.coalesce(root.get("notes"), "")), term)));
            }
            if (StringUtils.hasText(status)) {
                try {
                    p = cb.and(p, cb.equal(root.get("status"), ShipmentStatus.valueOf(status)));
                } catch (IllegalArgumentException e) {
                    // unknown status: no filter
                }
            }
            return p;
        };
        return repo.findAll(spec, PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "arrivalDate").and(Sort.by(Sort.Direction.DESC, "number"))));
    }

    public Map<UUID, ListTotals> totals(Collection<Shipment> shipments) {
        if (shipments.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = shipments.stream().map(Shipment::getId).toList();
        Map<UUID, Long> receipts = counts(linkRepo.countByShipment(ids));
        Map<UUID, Long> drafts = counts(costRepo.countByShipment(ids, ShipmentCostStatus.DRAFT));
        Map<UUID, BigDecimal> posted = new HashMap<>();
        for (Object[] row : allocationRepo.totalsByShipment(ids)) {
            posted.put((UUID) row[0], (BigDecimal) row[1]);
        }
        Map<UUID, ListTotals> totals = new HashMap<>();
        for (UUID id : ids) {
            totals.put(id, new ListTotals(receipts.getOrDefault(id, 0L), posted.getOrDefault(id, BigDecimal.ZERO),
                    drafts.getOrDefault(id, 0L)));
        }
        return totals;
    }

    public Shipment findById(UUID id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException("Shipment", id));
    }

    /** A shipment with its receipts (order, supplier) and cost lines (paid to). */
    public Shipment findDetailed(UUID id) {
        return repo.findDetailedById(id).orElseThrow(() -> new NotFoundException("Shipment", id));
    }

    /** The shipment page: crates with their landed cost, draft bills at today's rates, postings, totals. */
    public CostSheet costSheet(UUID id) {
        Shipment shipment = findDetailed(id);
        List<CrateBatch> crates = cratesOf(shipment);
        Map<UUID, LandedCost.CrateBasis> bases = bases(crates);
        List<ShipmentAllocation> allocations = allocationRepo.findByShipmentIdOrderByPostingNoAsc(id);
        Map<UUID, BigDecimal> allocated = new HashMap<>();
        Map<UUID, BigDecimal> brokenByCrate = new HashMap<>();
        for (ShipmentAllocation a : allocations) {
            allocated.merge(a.getCrateBatchId(), a.getAmount(), BigDecimal::add);
            brokenByCrate.merge(a.getCrateBatchId(), a.getBrokenAmount(), BigDecimal::add);
        }

        Map<UUID, CostPreview> previews = new LinkedHashMap<>();
        boolean estimable = true;
        List<BigDecimal> draftBase = new ArrayList<>();
        for (ShipmentCost cost : shipment.getCosts()) {
            if (cost.getStatus() != ShipmentCostStatus.DRAFT) {
                continue;
            }
            CostPreview preview = preview(cost);
            previews.put(cost.getId(), preview);
            if (preview.ok()) {
                draftBase.add(preview.base());
            } else {
                estimable = false;
            }
        }
        Map<UUID, BigDecimal> pending = new HashMap<>();
        BigDecimal pendingTotal = null;
        if (estimable && !previews.isEmpty()) {
            pendingTotal = LandedCost.postingTotal(draftBase, baseDecimals());
            if (!crates.isEmpty() && pendingTotal.signum() != 0) {
                List<BigDecimal> parts = LandedCost.split(pendingTotal,
                        crates.stream().map(c -> bases.get(c.getId()).basis(shipment.getAllocationMethod())).toList(),
                        LandedCost.MONEY_SCALE);
                for (int i = 0; i < crates.size(); i++) {
                    pending.put(crates.get(i).getId(), parts.get(i));
                }
            }
        }

        List<CrateLine> lines = new ArrayList<>();
        int sheets = 0;
        int broken = 0;
        BigDecimal area = BigDecimal.ZERO;
        BigDecimal brokenValue = BigDecimal.ZERO;
        for (CrateBatch crate : crates) {
            CrateLine line = new CrateLine(crate, bases.get(crate.getId()), allocated.getOrDefault(crate.getId(), BigDecimal.ZERO),
                    brokenByCrate.getOrDefault(crate.getId(), BigDecimal.ZERO),
                    pendingTotal == null ? null : pending.getOrDefault(crate.getId(), BigDecimal.ZERO));
            lines.add(line);
            sheets += crate.getSheets();
            broken += crate.getBroken();
            area = area.add(line.basis().area());
            brokenValue = brokenValue.add(line.getBrokenValue());
        }

        List<Posting> postings = allocations.stream()
                .collect(Collectors.groupingBy(ShipmentAllocation::getPostingNo, TreeMap::new, Collectors.toList()))
                .entrySet().stream()
                .map(e -> {
                    List<ShipmentAllocation> rows = e.getValue();
                    return new Posting(e.getKey(), rows.get(0).getPostedAt(), rows.get(0).getUsername(),
                            sum(rows, ShipmentAllocation::getAmount), sum(rows, ShipmentAllocation::getStockAmount),
                            sum(rows, ShipmentAllocation::getExpensedAmount), sum(rows, ShipmentAllocation::getBrokenAmount));
                })
                .toList();
        BigDecimal posted = sum(allocations, ShipmentAllocation::getAmount);
        Summary summary = new Summary(crates.size(), sheets, broken, area, posted,
                sum(allocations, ShipmentAllocation::getStockAmount), sum(allocations, ShipmentAllocation::getExpensedAmount),
                sum(allocations, ShipmentAllocation::getBrokenAmount), previews.size(), pendingTotal,
                CurrencyMath.round(brokenValue, baseDecimals()));
        return new CostSheet(shipment, lines, previews, postings, summary);
    }

    /** The shipment a receipt came in, with the landed cost per m² posted to each of its crates. */
    public Optional<ReceiptLanding> landingOf(GoodsReceipt receipt) {
        return linkRepo.findByGoodsReceipt_Id(receipt.getId()).map(link -> {
            Map<UUID, CrateBatch> crates = receipt.getCrates().stream()
                    .collect(Collectors.toMap(CrateBatch::getId, Function.identity()));
            Map<UUID, BigDecimal> allocated = new HashMap<>();
            for (ShipmentAllocation a : allocationRepo.findByCrateBatchIdIn(crates.keySet())) {
                allocated.merge(a.getCrateBatchId(), a.getAmount(), BigDecimal::add);
            }
            Map<UUID, BigDecimal> perM2 = new HashMap<>();
            allocated.forEach((crateId, amount) -> {
                CrateBatch c = crates.get(crateId);
                perM2.put(crateId, LandedCost.perM2(amount,
                        c.getSheetArea().multiply(BigDecimal.valueOf(c.getSheets() + c.getBroken()))));
            });
            return new ReceiptLanding(link.getShipment(), perM2);
        });
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    /** A new shipment: arriving today, shared by area, one empty bill in the base currency. */
    public ShipmentDto newForm() {
        ShipmentDto dto = new ShipmentDto();
        dto.setArrivalDate(today());
        dto.setAllocationMethod(AllocationMethod.AREA);
        dto.getCosts().add(blankCost());
        return dto;
    }

    public ShipmentDto.Cost blankCost() {
        ShipmentDto.Cost cost = new ShipmentDto.Cost();
        cost.setCurrencyCode(baseCurrency().getCode());
        return cost;
    }

    /** Posted receipts that can join the shipment: not in another shipment. Newest first. */
    public List<GoodsReceipt> availableReceipts(Shipment current) {
        Set<UUID> elsewhere = new HashSet<>(current == null ? linkRepo.findLinkedReceiptIds()
                : linkRepo.findReceiptIdsLinkedElsewhere(current.getId()));
        return receiptRepo.findByStatusOrderByReceivedDateDescNumberDesc(GoodsReceiptStatus.POSTED).stream()
                .filter(r -> !elsewhere.contains(r.getId()))
                .toList();
    }

    /** Suppliers a bill can be paid to: active ones, plus any already on the shipment's bills. */
    public List<Supplier> suppliersFor(Shipment current) {
        Set<UUID> onBills = current == null ? Set.of() : current.getCosts().stream()
                .filter(c -> c.getSupplier() != null).map(c -> c.getSupplier().getId()).collect(Collectors.toSet());
        return supplierRepo.findAll(Sort.by("name")).stream()
                .filter(s -> s.isEnabled() || onBills.contains(s.getId()))
                .toList();
    }

    /** Currencies a bill can be in: active ones, base currency first. */
    public List<Currency> currencies() {
        return currencyRepo.findAllByOrderByBaseCurrencyDescEnabledDescCodeAsc().stream()
                .filter(Currency::isEnabled)
                .toList();
    }

    public Currency baseCurrency() {
        return currencyRepo.findByBaseCurrencyTrue().orElseThrow(() -> new IllegalStateException("No base currency"));
    }

    // ---------------------------------------------------------------- drafts

    @Transactional
    public Shipment create(ShipmentDto dto) {
        checkHeader(dto);
        List<GoodsReceipt> receipts = receipts(dto, null);
        Map<UUID, Supplier> suppliers = suppliers(dto, null);
        checkCosts(dto);
        Shipment shipment = new Shipment();
        shipment.setNumber(numbers.next(DocumentType.SHIPMENT));
        apply(shipment, dto, receipts, suppliers);
        return repo.save(shipment);
    }

    @Transactional
    public Shipment update(UUID id, ShipmentDto dto) {
        Shipment shipment = findDetailed(id);
        requireOpen(shipment);
        checkHeader(dto);
        if (shipment.hasPostedCosts() && dto.getAllocationMethod() != shipment.getAllocationMethod()) {
            throw BusinessException.onField("allocationMethod", "shipment.method.locked");
        }
        List<GoodsReceipt> receipts = receipts(dto, shipment);
        if (shipment.hasPostedCosts()) {
            Set<UUID> linked = shipment.getReceipts().stream().map(r -> r.getGoodsReceipt().getId()).collect(Collectors.toSet());
            Set<UUID> wanted = receipts.stream().map(GoodsReceipt::getId).collect(Collectors.toSet());
            if (!linked.equals(wanted)) {
                throw BusinessException.onField("receiptIds", "shipment.receipts.locked");
            }
        }
        Map<UUID, Supplier> suppliers = suppliers(dto, shipment);
        checkCosts(dto);
        apply(shipment, dto, receipts, suppliers);
        return shipment;
    }

    /** Header and receipts from the form; draft bills matched by id, numbered after the posted ones. */
    private void apply(Shipment shipment, ShipmentDto dto, List<GoodsReceipt> receipts, Map<UUID, Supplier> suppliers) {
        shipment.setReference(PartyRules.clean(dto.getReference()));
        shipment.setArrivalDate(dto.getArrivalDate());
        shipment.setAllocationMethod(dto.getAllocationMethod());
        shipment.setNotes(PartyRules.clean(dto.getNotes()));

        Set<UUID> wanted = receipts.stream().map(GoodsReceipt::getId).collect(Collectors.toSet());
        shipment.getReceipts().removeIf(link -> !wanted.contains(link.getGoodsReceipt().getId()));
        Set<UUID> linked = shipment.getReceipts().stream().map(link -> link.getGoodsReceipt().getId()).collect(Collectors.toSet());
        for (GoodsReceipt receipt : receipts) {
            if (!linked.contains(receipt.getId())) {
                ShipmentReceipt link = new ShipmentReceipt();
                link.setShipment(shipment);
                link.setGoodsReceipt(receipt);
                link.setReceiptNumber(receipt.getNumber());
                shipment.getReceipts().add(link);
            }
        }

        Map<UUID, ShipmentCost> drafts = shipment.getCosts().stream()
                .filter(c -> c.getStatus() == ShipmentCostStatus.DRAFT)
                .collect(Collectors.toMap(ShipmentCost::getId, Function.identity()));
        int lastPosted = shipment.getCosts().stream().filter(c -> c.getStatus() == ShipmentCostStatus.POSTED)
                .mapToInt(ShipmentCost::getLineNo).max().orElse(0);
        List<ShipmentCost> kept = new ArrayList<>();
        for (ShipmentDto.Cost row : dto.getCosts()) {
            ShipmentCost cost = row.getId() == null ? null : drafts.get(row.getId());
            if (cost == null) {
                cost = new ShipmentCost();
                cost.setShipment(shipment);
            }
            cost.setCostType(row.getCostType());
            cost.setDescription(PartyRules.clean(row.getDescription()));
            cost.setSupplier(row.getSupplierId() == null ? null : suppliers.get(row.getSupplierId()));
            cost.setInvoiceRef(PartyRules.clean(row.getInvoiceRef()));
            cost.setInvoiceDate(row.getInvoiceDate());
            cost.setCurrencyCode(row.getCurrencyCode().trim().toUpperCase(Locale.ROOT));
            cost.setAmount(row.getAmount());
            kept.add(cost);
        }
        shipment.getCosts().removeIf(c -> c.getStatus() == ShipmentCostStatus.DRAFT && !kept.contains(c));
        // uk_shipment_costs_no is checked at commit, so drafts can swap numbers here.
        for (int i = 0; i < kept.size(); i++) {
            ShipmentCost cost = kept.get(i);
            cost.setLineNo(lastPosted + i + 1);
            if (!shipment.getCosts().contains(cost)) {
                shipment.getCosts().add(cost);
            }
        }
    }

    // ---------------------------------------------------------------- posting

    /**
     * Posts the draft bills: fixes each one's rate, rounds the RWF total once, shares it between the crates
     * (PRC-04) and each crate's part between its sheets, adds the parts of sheets in stock to their cost
     * and moves the products' MAC (PRC-05). The shipment is locked first, then its products, so postings
     * of a shipment run one at a time and see the stock receipts of the same products left.
     */
    @Transactional
    public PostResult post(UUID id) {
        repo.lockById(id).orElseThrow(() -> new NotFoundException("Shipment", id));
        Shipment shipment = findDetailed(id);
        requireOpen(shipment);
        if (!shipment.hasDraftCosts()) {
            throw BusinessException.of("shipment.post.noDrafts", shipment.getNumber());
        }
        if (shipment.getReceipts().isEmpty()) {
            throw BusinessException.of("shipment.post.noReceipts", shipment.getNumber());
        }
        List<CrateBatch> crates = cratesOf(shipment);
        Map<UUID, Product> products = productRepo.lockAllById(crates.stream().map(c -> c.getProduct().getId())
                        .distinct().toList()).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));

        PostingService.StockValues valueBefore = postingService.stockValues(products.values());
        int postingNo = shipment.getPostings() + 1;
        LocalDateTime now = LocalDateTime.now(clock);
        String username = AppUserPrincipal.currentUsername();
        List<BigDecimal> base = new ArrayList<>();
        List<ShipmentCost> bills = new ArrayList<>();
        int lines = 0;
        for (ShipmentCost cost : shipment.getCosts()) {
            if (cost.getStatus() != ShipmentCostStatus.DRAFT) {
                continue;
            }
            AppliedRate rate = rateOf(cost);
            cost.setRate(rate.rate());
            cost.setRateDate(rate.rateDate());
            cost.setRateSource(rate.source());
            cost.setStatus(ShipmentCostStatus.POSTED);
            cost.setPostingNo(postingNo);
            cost.setPostedAt(now);
            cost.setPostedBy(username);
            base.add(rate.toBase(cost.getAmount()));
            bills.add(cost);
            lines++;
        }
        BigDecimal total = LandedCost.postingTotal(base, baseDecimals());
        if (total.signum() == 0) {
            return new PostResult(shipment, postingNo, lines, total, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0);
        }

        Map<UUID, LandedCost.CrateBasis> bases = bases(crates);
        List<BigDecimal> basisValues = crates.stream()
                .map(c -> bases.get(c.getId()).basis(shipment.getAllocationMethod())).toList();
        List<BigDecimal> crateAmounts = LandedCost.split(total, basisValues, LandedCost.MONEY_SCALE);
        Map<UUID, List<StockUnit>> sheetsByCrate = stockService.receivedSheetsOf(crates.stream().map(CrateBatch::getId).toList())
                .stream().collect(Collectors.groupingBy(u -> u.getCrateBatch().getId()));
        Optional<AppUserPrincipal> user = AppUserPrincipal.current();

        Map<UUID, BigDecimal> toStockByProduct = new HashMap<>();
        BigDecimal toStock = BigDecimal.ZERO;
        BigDecimal expensed = BigDecimal.ZERO;
        BigDecimal broken = BigDecimal.ZERO;
        int units = 0;
        for (int i = 0; i < crates.size(); i++) {
            CrateBatch crate = crates.get(i);
            List<StockUnit> sheets = sheetsByCrate.getOrDefault(crate.getId(), List.of());
            BigDecimal crateAmount = crateAmounts.get(i);
            List<BigDecimal> parts = LandedCost.perPiece(crateAmount, sheets.size() + crate.getBroken());
            BigDecimal crateStock = BigDecimal.ZERO;
            BigDecimal crateExpensed = BigDecimal.ZERO;
            for (int j = 0; j < sheets.size(); j++) {
                StockUnit unit = sheets.get(j);
                BigDecimal part = parts.get(j);
                if (StockStatus.onHand().contains(unit.getStatus())) {
                    stockService.addLandedCost(unit, part, shipment.getId(), shipment.getNumber());
                    crateStock = crateStock.add(part);
                    units++;
                } else {
                    crateExpensed = crateExpensed.add(part);
                }
            }
            BigDecimal crateBroken = crateAmount.subtract(crateStock).subtract(crateExpensed);

            ShipmentAllocation allocation = new ShipmentAllocation();
            allocation.setShipmentId(shipment.getId());
            allocation.setPostingNo(postingNo);
            allocation.setCrateBatchId(crate.getId());
            allocation.setMethod(shipment.getAllocationMethod());
            allocation.setBasis(basisValues.get(i));
            allocation.setAmount(crateAmount);
            allocation.setStockAmount(crateStock);
            allocation.setExpensedAmount(crateExpensed);
            allocation.setBrokenAmount(crateBroken);
            allocation.setPostedAt(now);
            allocation.setUserId(user.map(AppUserPrincipal::getId).orElse(null));
            allocation.setUsername(user.map(AppUserPrincipal::getUsername).orElse("system"));
            allocationRepo.save(allocation);

            toStockByProduct.merge(crate.getProduct().getId(), crateStock, BigDecimal::add);
            toStock = toStock.add(crateStock);
            expensed = expensed.add(crateExpensed);
            broken = broken.add(crateBroken);
        }

        for (Map.Entry<UUID, BigDecimal> e : toStockByProduct.entrySet()) {
            Product product = products.get(e.getKey());
            BigDecimal mac = Costing.addValue(stockService.heldArea(product.getId()), product.getMacPerM2(), e.getValue());
            if (mac != null && mac.signum() < 0) {
                throw BusinessException.of("shipment.post.negativeMac", product.getCode());
            }
            product.setMacPerM2(mac);
        }
        postingService.shipmentPosting(shipment, postingNo, bills, base, total, expensed, broken, valueBefore);
        return new PostResult(shipment, postingNo, lines, total, toStock, expensed, broken, units);
    }

    /** The rate a bill is posted at: of its date, customs rate for duty (PRC-05). */
    private AppliedRate rateOf(ShipmentCost cost) {
        RateSource source = cost.getCostType().rateSource();
        return source == null ? rateService.rateFor(cost.getCurrencyCode(), cost.getInvoiceDate())
                : rateService.rateFor(cost.getCurrencyCode(), cost.getInvoiceDate(), source);
    }

    private CostPreview preview(ShipmentCost cost) {
        try {
            AppliedRate rate = rateOf(cost);
            return new CostPreview(rate, rate.toBase(cost.getAmount()), null, null);
        } catch (BusinessException e) {
            return new CostPreview(null, null, e.getMessageKey(), e.getArgs());
        }
    }

    // ---------------------------------------------------------------- close, cancel

    /** Costs are complete: nothing more is added. */
    @Transactional
    public Shipment close(UUID id) {
        Shipment shipment = findDetailed(id);
        requireOpen(shipment);
        if (shipment.hasDraftCosts()) {
            throw BusinessException.of("shipment.close.drafts", shipment.getNumber());
        }
        if (!shipment.hasPostedCosts()) {
            throw BusinessException.of("shipment.close.nothingPosted", shipment.getNumber());
        }
        shipment.setStatus(ShipmentStatus.CLOSED);
        shipment.setClosedAt(LocalDateTime.now(clock));
        shipment.setClosedBy(AppUserPrincipal.currentUsername());
        return shipment;
    }

    /** A shipment nothing was posted on. Its receipts are freed for another shipment; the number stays. */
    @Transactional
    public Shipment cancel(UUID id, String reason) {
        Shipment shipment = findDetailed(id);
        requireOpen(shipment);
        if (shipment.hasPostedCosts()) {
            throw BusinessException.of("shipment.cancel.posted", shipment.getNumber());
        }
        shipment.setStatus(ShipmentStatus.CANCELLED);
        shipment.setCancelReason(reason.trim());
        shipment.getReceipts().clear();
        return shipment;
    }

    // ---------------------------------------------------------------- claim (PRC-06)

    /** Sends the claim for the sheets broken on arrival to the supplier or insurer. */
    @Transactional
    public Shipment openClaim(UUID id, String party, String ref, LocalDate date, BigDecimal amount) {
        Shipment shipment = findDetailed(id);
        if (shipment.getStatus() == ShipmentStatus.CANCELLED) {
            throw BusinessException.of("shipment.claim.cancelled", shipment.getNumber());
        }
        if (shipment.getClaimStatus() != ClaimStatus.NONE) {
            throw BusinessException.of("shipment.claim.exists", shipment.getNumber());
        }
        if (cratesOf(shipment).stream().mapToInt(CrateBatch::getBroken).sum() == 0) {
            throw BusinessException.of("shipment.claim.noBroken", shipment.getNumber());
        }
        String cleanParty = PartyRules.clean(party);
        if (cleanParty == null || cleanParty.length() > 100) {
            throw BusinessException.of("shipment.claim.party.required");
        }
        String cleanRef = PartyRules.clean(ref);
        if (cleanRef != null && cleanRef.length() > 60) {
            throw BusinessException.of("shipment.claim.ref.size");
        }
        if (date == null || date.isAfter(today()) || date.isBefore(shipment.getArrivalDate())) {
            throw BusinessException.of("shipment.claim.date.invalid", shipment.getArrivalDate());
        }
        if (amount == null || amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 2) {
            throw BusinessException.of("shipment.claim.amount.invalid");
        }
        shipment.setClaimStatus(ClaimStatus.OPEN);
        shipment.setClaimParty(cleanParty);
        shipment.setClaimRef(cleanRef);
        shipment.setClaimDate(date);
        shipment.setClaimAmount(amount.setScale(2, RoundingMode.UNNECESSARY));
        postingService.claimOpened(shipment);
        return shipment;
    }

    /** The claim was paid or credited: the amount received and how it came in (the account the journal debits). */
    @Transactional
    public Shipment settleClaim(UUID id, BigDecimal received, ClaimSettlement receivedInto, String note) {
        Shipment shipment = requireOpenClaim(id);
        if (received == null || received.signum() < 0 || received.stripTrailingZeros().scale() > 2) {
            throw BusinessException.of("shipment.claim.settled.invalid");
        }
        if (received.signum() > 0 && receivedInto == null) {
            throw BusinessException.of("shipment.claim.receivedInto.required");
        }
        String cleanNote = PartyRules.clean(note);
        if (cleanNote != null && cleanNote.length() > 255) {
            throw BusinessException.of("shipment.claim.note.size");
        }
        shipment.setClaimStatus(ClaimStatus.SETTLED);
        shipment.setClaimSettledAmount(received.setScale(2, RoundingMode.UNNECESSARY));
        shipment.setClaimReceivedInto(received.signum() > 0 ? receivedInto : null);
        shipment.setClaimNote(cleanNote);
        postingService.claimSettled(shipment);
        return shipment;
    }

    /** The claim was refused; the reason is kept. */
    @Transactional
    public Shipment rejectClaim(UUID id, String reason) {
        Shipment shipment = requireOpenClaim(id);
        shipment.setClaimStatus(ClaimStatus.REJECTED);
        shipment.setClaimNote(reason.trim());
        postingService.claimRejected(shipment);
        return shipment;
    }

    private Shipment requireOpenClaim(UUID id) {
        Shipment shipment = findById(id);
        if (shipment.getClaimStatus() != ClaimStatus.OPEN) {
            throw BusinessException.of("shipment.claim.notOpen", shipment.getNumber());
        }
        return shipment;
    }

    // ---------------------------------------------------------------- rules

    private static void requireOpen(Shipment shipment) {
        if (shipment.getStatus() != ShipmentStatus.OPEN) {
            throw BusinessException.of("shipment.notOpen", shipment.getNumber());
        }
    }

    private void checkHeader(ShipmentDto dto) {
        if (dto.getArrivalDate().isAfter(today())) {
            throw BusinessException.onField("arrivalDate", "shipment.arrivalDate.future");
        }
    }

    /** The receipts chosen: posted, and not in another shipment. */
    List<GoodsReceipt> receipts(ShipmentDto dto, Shipment current) {
        List<GoodsReceipt> receipts = new ArrayList<>();
        for (UUID receiptId : new LinkedHashSet<>(dto.getReceiptIds())) {
            GoodsReceipt receipt = receiptRepo.findById(receiptId)
                    .orElseThrow(() -> BusinessException.onField("receiptIds", "shipment.receipt.unknown"));
            if (receipt.getStatus() != GoodsReceiptStatus.POSTED) {
                throw BusinessException.onField("receiptIds", "shipment.receipt.notPosted", receipt.getNumber());
            }
            Optional<ShipmentReceipt> link = linkRepo.findByGoodsReceipt_Id(receiptId);
            if (link.isPresent() && (current == null || !link.get().getShipment().getId().equals(current.getId()))) {
                throw BusinessException.onField("receiptIds", "shipment.receipt.linked", receipt.getNumber(),
                        link.get().getShipment().getNumber());
            }
            receipts.add(receipt);
        }
        return receipts;
    }

    /** Suppliers the bills are paid to: active, unless already on this shipment's bills. */
    private Map<UUID, Supplier> suppliers(ShipmentDto dto, Shipment current) {
        Set<UUID> onBills = current == null ? Set.of() : current.getCosts().stream()
                .filter(c -> c.getSupplier() != null).map(c -> c.getSupplier().getId()).collect(Collectors.toSet());
        Map<UUID, Supplier> suppliers = new HashMap<>();
        for (int i = 0; i < dto.getCosts().size(); i++) {
            UUID supplierId = dto.getCosts().get(i).getSupplierId();
            if (supplierId == null || suppliers.containsKey(supplierId)) {
                continue;
            }
            String field = "costs[" + i + "].supplierId";
            Supplier supplier = supplierRepo.findById(supplierId)
                    .orElseThrow(() -> BusinessException.onField(field, "shipment.cost.supplier.unknown"));
            if (!supplier.isEnabled() && !onBills.contains(supplierId)) {
                throw BusinessException.onField(field, "shipment.cost.supplier.inactive", supplier.getName());
            }
            suppliers.put(supplierId, supplier);
        }
        return suppliers;
    }

    /** Each bill: dated today at the latest, in an active currency, not zero. */
    void checkCosts(ShipmentDto dto) {
        Set<String> active = currencies().stream().map(Currency::getCode).collect(Collectors.toSet());
        for (int i = 0; i < dto.getCosts().size(); i++) {
            ShipmentDto.Cost cost = dto.getCosts().get(i);
            String row = "costs[" + i + "].";
            if (cost.getInvoiceDate().isAfter(today())) {
                throw BusinessException.onField(row + "invoiceDate", "shipment.cost.date.future");
            }
            String code = cost.getCurrencyCode().trim().toUpperCase(Locale.ROOT);
            if (!active.contains(code)) {
                throw BusinessException.onField(row + "currencyCode", "shipment.cost.currency.invalid", code);
            }
            if (cost.getAmount().signum() == 0) {
                throw BusinessException.onField(row + "amount", "shipment.cost.amount.zero");
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Crates of the shipment's receipts, by receipt then crate. */
    private List<CrateBatch> cratesOf(Shipment shipment) {
        List<UUID> receiptIds = shipment.getReceipts().stream().map(r -> r.getGoodsReceipt().getId()).toList();
        return receiptIds.isEmpty() ? List.of() : crateRepo.findByReceipts(receiptIds);
    }

    private Map<UUID, LandedCost.CrateBasis> bases(List<CrateBatch> crates) {
        BigDecimal density = settingService.getDecimal(SettingKey.GLASS_DENSITY);
        Map<UUID, LandedCost.CrateBasis> bases = new HashMap<>();
        for (CrateBatch crate : crates) {
            bases.put(crate.getId(), new LandedCost.CrateBasis(crate.getSheets(), crate.getBroken(), crate.getSheetArea(),
                    crate.getCostPerM2(), GlassProducts.weightPerM2(crate.getProduct().getThicknessMm(), density)));
        }
        return bases;
    }

    private int baseDecimals() {
        return baseCurrency().getDecimals();
    }

    private static Map<UUID, Long> counts(List<Object[]> rows) {
        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : rows) {
            counts.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        return counts;
    }

    private static <T> BigDecimal sum(Collection<T> rows, Function<T, BigDecimal> amount) {
        return rows.stream().map(amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
