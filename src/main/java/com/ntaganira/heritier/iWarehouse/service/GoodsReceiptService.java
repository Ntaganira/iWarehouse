package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.dto.GoodsReceiptDto;
import com.ntaganira.heritier.iWarehouse.entity.CrateBatch;
import com.ntaganira.heritier.iWarehouse.entity.GoodsReceipt;
import com.ntaganira.heritier.iWarehouse.entity.Location;
import com.ntaganira.heritier.iWarehouse.entity.Product;
import com.ntaganira.heritier.iWarehouse.entity.PurchaseOrder;
import com.ntaganira.heritier.iWarehouse.entity.PurchaseOrderLine;
import com.ntaganira.heritier.iWarehouse.entity.Supplier;
import com.ntaganira.heritier.iWarehouse.enums.DocumentType;
import com.ntaganira.heritier.iWarehouse.enums.GoodsReceiptStatus;
import com.ntaganira.heritier.iWarehouse.enums.SettingKey;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.CrateBatchRepository;
import com.ntaganira.heritier.iWarehouse.repository.GoodsReceiptRepository;
import com.ntaganira.heritier.iWarehouse.repository.ProductRepository;
import com.ntaganira.heritier.iWarehouse.repository.PurchaseOrderRepository;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import com.ntaganira.heritier.iWarehouse.service.ExchangeRateService.AppliedRate;
import jakarta.persistence.criteria.Join;
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
 * - File      : GoodsReceiptService.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Goods receipts against placed purchase orders (PRC-02, PRC-06). A draft lists the
 *               crates; it can't bring more sheets than an order line still waits for, and its racks
 *               must hold the extra pieces and weight (MD-03). Posting fixes the exchange rate of the
 *               receipt date, creates one stock unit per good sheet through StockService, adds the
 *               sheets to the order lines, moves the order's status on and updates each product's
 *               moving average cost at the PO price (PRC-05). Broken sheets never enter stock.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class GoodsReceiptService {

    private final GoodsReceiptRepository repo;
    private final CrateBatchRepository crateRepo;
    private final PurchaseOrderRepository orderRepo;
    private final ProductRepository productRepo;
    private final StockService stockService;
    private final PostingService postingService;
    private final ExchangeRateService rateService;
    private final DocumentNumberService numbers;
    private final SettingService settingService;
    private final Clock clock;

    public GoodsReceiptService(GoodsReceiptRepository repo, CrateBatchRepository crateRepo,
                               PurchaseOrderRepository orderRepo, ProductRepository productRepo,
                               StockService stockService, PostingService postingService, ExchangeRateService rateService,
                               DocumentNumberService numbers, SettingService settingService, Clock clock) {
        this.repo = repo;
        this.crateRepo = crateRepo;
        this.orderRepo = orderRepo;
        this.productRepo = productRepo;
        this.stockService = stockService;
        this.postingService = postingService;
        this.rateService = rateService;
        this.numbers = numbers;
        this.settingService = settingService;
        this.clock = clock;
    }

    /** Crates and sheets of a receipt, for lists. */
    public record Totals(int crates, int sheets, int broken) {
        public static final Totals NONE = new Totals(0, 0, 0);
    }

    /** A posted receipt: how many units it created and the rate it used. */
    public record PostResult(GoodsReceipt receipt, int units, AppliedRate rate) {
    }

    /** One crate as checked: a form row (index = its row) or a saved crate when posting. */
    record CrateInput(int index, UUID lineId, String batchNo, int widthMm, int heightMm, int sheets, int broken,
                      UUID locationId) {
    }

    // ---------------------------------------------------------------- reading

    public Page<GoodsReceipt> findPage(String search, String status, int page, int size) {
        Specification<GoodsReceipt> spec = (root, query, cb) -> {
            Predicate p = cb.conjunction();
            if (StringUtils.hasText(search)) {
                String term = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                Join<GoodsReceipt, PurchaseOrder> order = root.join("purchaseOrder");
                Join<PurchaseOrder, Supplier> supplier = order.join("supplier");
                p = cb.and(p, cb.or(
                        cb.like(cb.lower(root.get("number")), term),
                        cb.like(cb.lower(cb.coalesce(root.get("deliveryRef"), "")), term),
                        cb.like(cb.lower(order.get("number")), term),
                        cb.like(cb.lower(supplier.get("name")), term)));
            }
            if (StringUtils.hasText(status)) {
                try {
                    p = cb.and(p, cb.equal(root.get("status"), GoodsReceiptStatus.valueOf(status)));
                } catch (IllegalArgumentException e) {
                    // unknown status: no filter
                }
            }
            return p;
        };
        return repo.findAll(spec, PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "number"))));
    }

    public Map<UUID, Totals> totals(Collection<GoodsReceipt> receipts) {
        if (receipts.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Totals> totals = new HashMap<>();
        for (Object[] row : crateRepo.totalsByReceipt(receipts.stream().map(GoodsReceipt::getId).toList())) {
            totals.put((UUID) row[0], new Totals(((Number) row[1]).intValue(), ((Number) row[2]).intValue(),
                    ((Number) row[3]).intValue()));
        }
        return totals;
    }

    public GoodsReceipt findById(UUID id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException("GoodsReceipt", id));
    }

    /** A receipt with its order, supplier and crates (product, rack, order line). */
    public GoodsReceipt findDetailed(UUID id) {
        return repo.findWithCratesById(id).orElseThrow(() -> new NotFoundException("GoodsReceipt", id));
    }

    /** Receipts of an order, newest first. */
    public List<GoodsReceipt> receiptsOf(UUID orderId) {
        return repo.findByPurchaseOrder_IdOrderByCreatedAtDesc(orderId);
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    /** Where crates can go, with what each rack holds now (for the form). */
    public record RackChoice(Location location, Location rack, RackLoad load) {
    }

    public List<RackChoice> rackChoices() {
        Map<UUID, Location> byId = stockService.locationsById();
        Map<UUID, RackLoad> loads = stockService.rackLoads(byId);
        return stockService.receivingLocations(byId).stream()
                .map(l -> {
                    Location rack = StockService.rackOf(l, byId);
                    return new RackChoice(l, rack, loads.getOrDefault(rack.getId(), RackLoad.EMPTY));
                })
                .toList();
    }

    /** A new receipt for an order: dated today, one crate row per line still waiting for sheets. */
    public GoodsReceiptDto newForm(PurchaseOrder order) {
        GoodsReceiptDto dto = new GoodsReceiptDto();
        dto.setPurchaseOrderId(order.getId());
        dto.setReceivedDate(today());
        for (PurchaseOrderLine line : order.getLines()) {
            if (line.getOutstanding() > 0) {
                GoodsReceiptDto.Crate crate = new GoodsReceiptDto.Crate();
                crate.setPoLineId(line.getId());
                crate.setWidthMm(line.getWidthMm());
                crate.setHeightMm(line.getHeightMm());
                dto.getCrates().add(crate);
            }
        }
        return dto;
    }

    // ---------------------------------------------------------------- drafts

    @Transactional
    public GoodsReceipt create(UUID orderId, GoodsReceiptDto dto) {
        PurchaseOrder order = orderRepo.findWithLinesById(orderId)
                .orElseThrow(() -> new NotFoundException("PurchaseOrder", orderId));
        requireReceivable(order);
        check(order, dto.getReceivedDate(), inputs(dto));
        GoodsReceipt receipt = new GoodsReceipt();
        receipt.setNumber(numbers.next(DocumentType.GOODS_RECEIPT));
        receipt.setPurchaseOrder(order);
        receipt.setCurrencyCode(order.getCurrencyCode());
        apply(receipt, order, dto);
        return repo.save(receipt);
    }

    @Transactional
    public GoodsReceipt update(UUID id, GoodsReceiptDto dto) {
        GoodsReceipt receipt = findDetailed(id);
        requireDraft(receipt);
        PurchaseOrder order = orderRepo.findWithLinesById(receipt.getPurchaseOrder().getId())
                .orElseThrow(() -> new NotFoundException("PurchaseOrder", receipt.getPurchaseOrder().getId()));
        requireReceivable(order);
        check(order, dto.getReceivedDate(), inputs(dto));
        apply(receipt, order, dto);
        return receipt;
    }

    /** A draft that will not be posted. Its number stays, with the reason. */
    @Transactional
    public GoodsReceipt cancel(UUID id, String reason) {
        GoodsReceipt receipt = findById(id);
        requireDraft(receipt);
        receipt.setStatus(GoodsReceiptStatus.CANCELLED);
        receipt.setCancelReason(reason.trim());
        return receipt;
    }

    private void apply(GoodsReceipt receipt, PurchaseOrder order, GoodsReceiptDto dto) {
        Map<UUID, PurchaseOrderLine> lines = linesById(order);
        Map<UUID, Location> locations = stockService.locationsById();
        receipt.setReceivedDate(dto.getReceivedDate());
        receipt.setDeliveryRef(PartyRules.clean(dto.getDeliveryRef()));
        receipt.setNotes(PartyRules.clean(dto.getNotes()));

        Map<UUID, CrateBatch> existing = receipt.getCrates().stream()
                .collect(Collectors.toMap(CrateBatch::getId, Function.identity()));
        List<CrateBatch> kept = new ArrayList<>();
        for (GoodsReceiptDto.Crate row : dto.getCrates()) {
            CrateBatch crate = row.getId() == null ? null : existing.get(row.getId());
            if (crate == null) {
                crate = new CrateBatch();
                crate.setGoodsReceipt(receipt);
            }
            PurchaseOrderLine line = lines.get(row.getPoLineId());
            crate.setPoLine(line);
            crate.setProduct(line.getProduct());
            crate.setBatchNo(row.getBatchNo().trim());
            crate.setWidthMm(row.getWidthMm());
            crate.setHeightMm(row.getHeightMm());
            crate.setSheets(row.getSheets());
            crate.setBroken(row.getBroken() == null ? 0 : row.getBroken());
            crate.setLocation(locations.get(row.getLocationId()));
            kept.add(crate);
        }
        receipt.getCrates().removeIf(c -> !kept.contains(c));
        for (CrateBatch crate : kept) {
            if (!receipt.getCrates().contains(crate)) {
                receipt.getCrates().add(crate);
            }
        }
    }

    // ---------------------------------------------------------------- posting

    /**
     * Posts a draft: units on their racks, order lines and status, moving average cost. Products, then
     * the order, are locked first, so receipts of the same product or order are posted one at a time
     * and each sees the stock and quantities the previous one left.
     */
    @Transactional
    public PostResult post(UUID id) {
        // Ids only: the receipt itself is read after the locks, so its status is current.
        UUID orderId = repo.findOrderId(id).orElseThrow(() -> new NotFoundException("GoodsReceipt", id));
        Map<UUID, Product> products = productRepo.lockAllById(repo.findProductIds(id)).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        PurchaseOrder order = orderRepo.lockById(orderId).orElseThrow(() -> new NotFoundException("PurchaseOrder", orderId));
        GoodsReceipt receipt = findDetailed(id);
        requireDraft(receipt);
        requireReceivable(order);
        if (receipt.getCrates().isEmpty()) {
            throw BusinessException.of("receipt.crates.required");
        }
        check(order, receipt.getReceivedDate(), inputs(receipt));

        AppliedRate rate = rateService.rateFor(order.getCurrencyCode(), receipt.getReceivedDate());
        receipt.setRate(rate.rate());
        receipt.setRateDate(rate.rateDate());
        receipt.setRateSource(rate.source());

        // Stock held before this receipt, for the moving average, and its value for the journal (ACC-04).
        PostingService.StockValues valueBefore = postingService.stockValues(products.values());
        Map<UUID, BigDecimal> heldBefore = new HashMap<>();
        for (UUID productId : products.keySet()) {
            heldBefore.put(productId, stockService.heldArea(productId));
        }
        Map<UUID, BigDecimal> addedArea = new HashMap<>();
        Map<UUID, BigDecimal> addedValue = new HashMap<>();
        BigDecimal density = settingService.getDecimal(SettingKey.GLASS_DENSITY);
        int units = 0;
        for (CrateBatch crate : receipt.getCrates()) {
            PurchaseOrderLine line = crate.getPoLine();
            Product product = crate.getProduct();
            BigDecimal costPerM2 = Costing.costPerM2(line.getPricePerM2(), rate.rate());
            BigDecimal unitCost = Costing.unitCost(crate.getSheetArea(), costPerM2);
            BigDecimal unitWeight = GlassProducts.weightKg(crate.getSheetArea(),
                    GlassProducts.weightPerM2(product.getThicknessMm(), density));
            crate.setCostPerM2(costPerM2);
            units += stockService.receive(crate, unitWeight, unitCost, receipt).size();
            line.setReceivedQty(line.getReceivedQty() + crate.getSheets() + crate.getBroken());
            addedArea.merge(product.getId(), crate.getArea(), BigDecimal::add);
            addedValue.merge(product.getId(), unitCost.multiply(BigDecimal.valueOf(crate.getSheets())), BigDecimal::add);
        }
        for (Product product : products.values()) {
            product.setMacPerM2(Costing.movingAverage(heldBefore.get(product.getId()), product.getMacPerM2(),
                    addedArea.getOrDefault(product.getId(), BigDecimal.ZERO),
                    addedValue.getOrDefault(product.getId(), BigDecimal.ZERO)));
        }

        order.setStatus(PurchaseOrders.statusAfterReceipt(order.getLines()));
        receipt.setStatus(GoodsReceiptStatus.POSTED);
        receipt.setPostedAt(LocalDateTime.now(clock));
        receipt.setPostedBy(AppUserPrincipal.currentUsername());
        postingService.goodsReceipt(receipt, valueBefore);
        return new PostResult(receipt, units, rate);
    }

    // ---------------------------------------------------------------- rules

    private void requireDraft(GoodsReceipt receipt) {
        if (receipt.getStatus() != GoodsReceiptStatus.DRAFT) {
            throw BusinessException.of("receipt.notDraft", receipt.getNumber());
        }
    }

    private static void requireReceivable(PurchaseOrder order) {
        if (!order.getStatus().isReceivable()) {
            throw BusinessException.of("receipt.order.notReceivable", order.getNumber());
        }
    }

    private static Map<UUID, PurchaseOrderLine> linesById(PurchaseOrder order) {
        return order.getLines().stream().collect(Collectors.toMap(PurchaseOrderLine::getId, Function.identity()));
    }

    private static List<CrateInput> inputs(GoodsReceiptDto dto) {
        List<CrateInput> inputs = new ArrayList<>();
        for (int i = 0; i < dto.getCrates().size(); i++) {
            GoodsReceiptDto.Crate c = dto.getCrates().get(i);
            inputs.add(new CrateInput(i, c.getPoLineId(), c.getBatchNo(), c.getWidthMm(), c.getHeightMm(),
                    c.getSheets(), c.getBroken() == null ? 0 : c.getBroken(), c.getLocationId()));
        }
        return inputs;
    }

    private static List<CrateInput> inputs(GoodsReceipt receipt) {
        List<CrateInput> inputs = new ArrayList<>();
        for (int i = 0; i < receipt.getCrates().size(); i++) {
            CrateBatch c = receipt.getCrates().get(i);
            inputs.add(new CrateInput(i, c.getPoLine().getId(), c.getBatchNo(), c.getWidthMm(), c.getHeightMm(),
                    c.getSheets(), c.getBroken(), c.getLocation().getId()));
        }
        return inputs;
    }

    /**
     * Checks a receipt's crates against its order and the racks: at least one crate, dated between the
     * order date and today, lines of this order, unique crate markings, at least one sheet per crate,
     * no more sheets than a line still waits for, and racks that can take the pieces and weight.
     * Errors point at the form row (crates[i].field) so the draft form shows them in place.
     */
    void check(PurchaseOrder order, LocalDate receivedDate, List<CrateInput> crates) {
        if (crates.isEmpty()) {
            throw BusinessException.of("receipt.crates.required");
        }
        if (receivedDate.isAfter(today())) {
            throw BusinessException.onField("receivedDate", "receipt.date.future");
        }
        if (receivedDate.isBefore(order.getOrderDate())) {
            throw BusinessException.onField("receivedDate", "receipt.date.beforeOrder", order.getOrderDate());
        }
        Map<UUID, PurchaseOrderLine> lines = linesById(order);
        Map<UUID, Location> locations = stockService.locationsById();
        Set<UUID> allowed = stockService.receivingLocations(locations).stream().map(Location::getId)
                .collect(Collectors.toSet());
        Set<String> batches = new HashSet<>();
        Map<UUID, Integer> perLine = new LinkedHashMap<>();
        Map<UUID, Integer> firstRowOfLine = new HashMap<>();
        for (CrateInput c : crates) {
            String row = "crates[" + c.index() + "].";
            PurchaseOrderLine line = lines.get(c.lineId());
            if (line == null) {
                throw BusinessException.onField(row + "poLineId", "receipt.crate.line.required");
            }
            String batch = c.batchNo().trim();
            if (!batches.add(batch.toLowerCase(Locale.ROOT))) {
                throw BusinessException.onField(row + "batchNo", "receipt.crate.batch.duplicate", batch);
            }
            if (c.sheets() + c.broken() <= 0) {
                throw BusinessException.onField(row + "sheets", "receipt.crate.empty");
            }
            if (!allowed.contains(c.locationId())) {
                throw BusinessException.onField(row + "locationId", "receipt.crate.location.invalid");
            }
            perLine.merge(line.getId(), c.sheets() + c.broken(), Integer::sum);
            firstRowOfLine.putIfAbsent(line.getId(), c.index());
        }
        for (Map.Entry<UUID, Integer> e : perLine.entrySet()) {
            PurchaseOrderLine line = lines.get(e.getKey());
            if (e.getValue() > line.getOutstanding()) {
                throw BusinessException.onField("crates[" + firstRowOfLine.get(e.getKey()) + "].sheets",
                        "receipt.crate.overOrdered", line.getLineNo(), line.getProduct().getCode(),
                        line.getOutstanding(), e.getValue());
            }
        }
        checkRacks(crates, lines, locations);
    }

    /** The racks must take the extra pieces and kg on top of what they hold (MD-03). */
    private void checkRacks(List<CrateInput> crates, Map<UUID, PurchaseOrderLine> lines, Map<UUID, Location> locations) {
        BigDecimal density = settingService.getDecimal(SettingKey.GLASS_DENSITY);
        Map<UUID, RackLoad> loads = stockService.rackLoads(locations);
        Map<UUID, RackLoad> after = new LinkedHashMap<>();
        Map<UUID, Integer> firstRowOfRack = new HashMap<>();
        for (CrateInput c : crates) {
            Location rack = StockService.rackOf(locations.get(c.locationId()), locations);
            Product product = lines.get(c.lineId()).getProduct();
            BigDecimal sheetKg = GlassProducts.weightKg(Pricing.areaM2(c.widthMm(), c.heightMm()),
                    GlassProducts.weightPerM2(product.getThicknessMm(), density));
            RackLoad base = after.getOrDefault(rack.getId(), loads.getOrDefault(rack.getId(), RackLoad.EMPTY));
            after.put(rack.getId(), base.plus(c.sheets(), sheetKg.multiply(BigDecimal.valueOf(c.sheets()))));
            firstRowOfRack.putIfAbsent(rack.getId(), c.index());
        }
        for (Map.Entry<UUID, RackLoad> e : after.entrySet()) {
            Location rack = locations.get(e.getKey());
            RackLoad now = loads.getOrDefault(rack.getId(), RackLoad.EMPTY);
            String field = "crates[" + firstRowOfRack.get(rack.getId()) + "].locationId";
            if (e.getValue().exceedsPieces(rack.getMaxPieces())) {
                throw BusinessException.onField(field, "receipt.rack.pieces", rack.getCode(), now.pieces(),
                        rack.getMaxPieces(), e.getValue().pieces() - now.pieces());
            }
            if (e.getValue().exceedsKg(rack.getMaxWeightKg())) {
                throw BusinessException.onField(field, "receipt.rack.weight", rack.getCode(), now.kg(),
                        rack.getMaxWeightKg(), e.getValue().kg().subtract(now.kg()));
            }
        }
    }
}
