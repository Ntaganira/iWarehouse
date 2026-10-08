package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.dto.PurchaseOrderDto;
import com.ntaganira.heritier.iWarehouse.entity.Currency;
import com.ntaganira.heritier.iWarehouse.entity.Product;
import com.ntaganira.heritier.iWarehouse.entity.PurchaseOrder;
import com.ntaganira.heritier.iWarehouse.entity.PurchaseOrderLine;
import com.ntaganira.heritier.iWarehouse.entity.Supplier;
import com.ntaganira.heritier.iWarehouse.enums.DocumentType;
import com.ntaganira.heritier.iWarehouse.enums.GoodsReceiptStatus;
import com.ntaganira.heritier.iWarehouse.enums.PurchaseOrderStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.CurrencyRepository;
import com.ntaganira.heritier.iWarehouse.repository.GoodsReceiptRepository;
import com.ntaganira.heritier.iWarehouse.repository.ProductRepository;
import com.ntaganira.heritier.iWarehouse.repository.PurchaseOrderLineRepository;
import com.ntaganira.heritier.iWarehouse.repository.PurchaseOrderRepository;
import com.ntaganira.heritier.iWarehouse.repository.SupplierRepository;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

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
 * - File      : PurchaseOrderService.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Purchase orders (PRC-01). A draft takes the supplier's currency and can be edited;
 *               placing it fixes it. An order nothing was received on can be cancelled, one partly
 *               received can be closed short; both need a reason and no draft receipt waiting.
 *               Numbers come from DocumentNumberService (PO-WH-2026-000001).
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class PurchaseOrderService {

    /** Orders still in progress: their supplier and products must stay active. */
    public static final List<PurchaseOrderStatus> OPEN = Arrays.stream(PurchaseOrderStatus.values())
            .filter(PurchaseOrderStatus::isOpen).toList();

    private final PurchaseOrderRepository repo;
    private final PurchaseOrderLineRepository lineRepo;
    private final GoodsReceiptRepository receiptRepo;
    private final SupplierRepository supplierRepo;
    private final ProductRepository productRepo;
    private final CurrencyRepository currencyRepo;
    private final DocumentNumberService numbers;
    private final Clock clock;

    public PurchaseOrderService(PurchaseOrderRepository repo, PurchaseOrderLineRepository lineRepo,
                                GoodsReceiptRepository receiptRepo, SupplierRepository supplierRepo,
                                ProductRepository productRepo, CurrencyRepository currencyRepo,
                                DocumentNumberService numbers, Clock clock) {
        this.repo = repo;
        this.lineRepo = lineRepo;
        this.receiptRepo = receiptRepo;
        this.supplierRepo = supplierRepo;
        this.productRepo = productRepo;
        this.currencyRepo = currencyRepo;
        this.numbers = numbers;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- reading

    /** status: a PurchaseOrderStatus name, "open" (draft or waiting for sheets), or empty for all. */
    public Page<PurchaseOrder> findPage(String search, UUID supplierId, String status, int page, int size) {
        Specification<PurchaseOrder> spec = (root, query, cb) -> {
            Predicate p = cb.conjunction();
            if (StringUtils.hasText(search)) {
                String term = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                Join<PurchaseOrder, Supplier> supplier = root.join("supplier");
                p = cb.and(p, cb.or(
                        cb.like(cb.lower(root.get("number")), term),
                        cb.like(cb.lower(cb.coalesce(root.get("supplierRef"), "")), term),
                        cb.like(cb.lower(supplier.get("name")), term)));
            }
            if (supplierId != null) {
                p = cb.and(p, cb.equal(root.get("supplier").get("id"), supplierId));
            }
            if ("open".equalsIgnoreCase(status)) {
                p = cb.and(p, root.get("status").in(OPEN));
            } else if (StringUtils.hasText(status)) {
                try {
                    p = cb.and(p, cb.equal(root.get("status"), PurchaseOrderStatus.valueOf(status)));
                } catch (IllegalArgumentException e) {
                    // unknown status: no filter
                }
            }
            return p;
        };
        return repo.findAll(spec, PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "orderDate").and(Sort.by(Sort.Direction.DESC, "number"))));
    }

    /** Totals per order of a page (sheets, m², amount in the order's currency). */
    public Map<UUID, PurchaseOrders.Totals> totals(Collection<PurchaseOrder> orders) {
        if (orders.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<PurchaseOrderLine>> lines = lineRepo.findByPurchaseOrder_IdIn(
                        orders.stream().map(PurchaseOrder::getId).toList()).stream()
                .collect(Collectors.groupingBy(l -> l.getPurchaseOrder().getId()));
        Map<String, Integer> decimals = currencyDecimals();
        Map<UUID, PurchaseOrders.Totals> totals = new HashMap<>();
        for (PurchaseOrder order : orders) {
            totals.put(order.getId(), PurchaseOrders.totals(lines.getOrDefault(order.getId(), List.of()),
                    decimals.getOrDefault(order.getCurrencyCode(), 2)));
        }
        return totals;
    }

    /** Totals of an order whose lines are loaded. */
    public PurchaseOrders.Totals totals(PurchaseOrder order) {
        return PurchaseOrders.totals(order.getLines(), currencyDecimals().getOrDefault(order.getCurrencyCode(), 2));
    }

    public PurchaseOrder findById(UUID id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException("PurchaseOrder", id));
    }

    /** An order with its supplier and lines (with products). */
    public PurchaseOrder findDetailed(UUID id) {
        return repo.findWithLinesById(id).orElseThrow(() -> new NotFoundException("PurchaseOrder", id));
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    /** Suppliers for the form: active ones, plus the order's own if it was deactivated since. */
    public List<Supplier> suppliersFor(PurchaseOrder current) {
        return supplierRepo.findAll(Sort.by("name")).stream()
                .filter(s -> s.isEnabled() || (current != null && s.getId().equals(current.getSupplier().getId())))
                .toList();
    }

    /** Products for the form lines: active ones, plus any already on the order. */
    public List<Product> productsFor(PurchaseOrder current) {
        Set<UUID> onOrder = current == null ? Set.of()
                : current.getLines().stream().map(l -> l.getProduct().getId()).collect(Collectors.toSet());
        return productRepo.findAll(Sort.by("glassType", "variant", "thicknessMm")).stream()
                .filter(p -> p.isEnabled() || onOrder.contains(p.getId()))
                .toList();
    }

    /** Draft receipts waiting on an order: it can't be cancelled or closed until they are dealt with. */
    public long draftReceipts(UUID orderId) {
        return receiptRepo.countByPurchaseOrder_IdAndStatus(orderId, GoodsReceiptStatus.DRAFT);
    }

    // ---------------------------------------------------------------- drafts

    @Transactional
    public PurchaseOrder create(PurchaseOrderDto dto) {
        Supplier supplier = supplier(dto.getSupplierId(), null);
        Map<UUID, Product> products = products(dto, null);
        checkDates(dto);
        PurchaseOrder order = new PurchaseOrder();
        order.setNumber(numbers.next(DocumentType.PURCHASE_ORDER));
        apply(order, supplier, products, dto);
        return repo.save(order);
    }

    @Transactional
    public PurchaseOrder update(UUID id, PurchaseOrderDto dto) {
        PurchaseOrder order = findDetailed(id);
        requireDraft(order);
        Supplier supplier = supplier(dto.getSupplierId(), order);
        Map<UUID, Product> products = products(dto, order);
        checkDates(dto);
        apply(order, supplier, products, dto);
        return order;
    }

    /** Places a draft with the supplier: from now on it is fixed and crates can be received against it. */
    @Transactional
    public PurchaseOrder place(UUID id) {
        PurchaseOrder order = findDetailed(id);
        requireDraft(order);
        if (order.getLines().isEmpty()) {
            throw BusinessException.of("po.place.noLines", order.getNumber());
        }
        if (!order.getSupplier().isEnabled()) {
            throw BusinessException.of("po.supplier.inactive", order.getSupplier().getName());
        }
        for (PurchaseOrderLine line : order.getLines()) {
            if (!line.getProduct().isEnabled()) {
                throw BusinessException.of("po.line.product.inactive", line.getLineNo(), line.getProduct().getCode());
            }
        }
        order.setStatus(PurchaseOrderStatus.ORDERED);
        order.setOrderedAt(LocalDateTime.now(clock));
        order.setOrderedBy(AppUserPrincipal.currentUsername());
        return order;
    }

    /** Cancels an order nothing was received on (a draft, or placed but nothing arrived). */
    @Transactional
    public PurchaseOrder cancel(UUID id, String reason) {
        PurchaseOrder order = findDetailed(id);
        if (order.getStatus() != PurchaseOrderStatus.DRAFT && order.getStatus() != PurchaseOrderStatus.ORDERED) {
            throw BusinessException.of("po.cancel.notAllowed", order.getNumber());
        }
        requireNoDraftReceipts(order);
        order.setStatus(PurchaseOrderStatus.CANCELLED);
        order.setClosedReason(reason.trim());
        return order;
    }

    /** Closes a partly received order: the rest will not come. */
    @Transactional
    public PurchaseOrder close(UUID id, String reason) {
        PurchaseOrder order = findDetailed(id);
        if (order.getStatus() != PurchaseOrderStatus.PARTIALLY_RECEIVED) {
            throw BusinessException.of("po.close.notAllowed", order.getNumber());
        }
        requireNoDraftReceipts(order);
        order.setStatus(PurchaseOrderStatus.CLOSED);
        order.setClosedReason(reason.trim());
        return order;
    }

    // ---------------------------------------------------------------- rules

    private void requireDraft(PurchaseOrder order) {
        if (!order.getStatus().isEditable()) {
            throw BusinessException.of("po.notDraft", order.getNumber());
        }
    }

    private void requireNoDraftReceipts(PurchaseOrder order) {
        long drafts = draftReceipts(order.getId());
        if (drafts > 0) {
            throw BusinessException.of("po.draftReceipts", order.getNumber(), drafts);
        }
    }

    /** The supplier must be active, unless it is the draft's own supplier. */
    private Supplier supplier(UUID id, PurchaseOrder current) {
        Supplier supplier = supplierRepo.findById(id)
                .orElseThrow(() -> BusinessException.onField("supplierId", "po.supplier.required"));
        boolean unchanged = current != null && current.getSupplier().getId().equals(id);
        if (!supplier.isEnabled() && !unchanged) {
            throw BusinessException.onField("supplierId", "po.supplier.inactive", supplier.getName());
        }
        return supplier;
    }

    /** At least one line; each product active, unless the draft already had it. */
    private Map<UUID, Product> products(PurchaseOrderDto dto, PurchaseOrder current) {
        if (dto.getLines().isEmpty()) {
            throw BusinessException.of("po.lines.required");
        }
        Set<UUID> onOrder = current == null ? Set.of()
                : current.getLines().stream().map(l -> l.getProduct().getId()).collect(Collectors.toSet());
        Map<UUID, Product> products = new HashMap<>();
        for (int i = 0; i < dto.getLines().size(); i++) {
            UUID productId = dto.getLines().get(i).getProductId();
            Product product = productRepo.findById(productId)
                    .orElseThrow(() -> BusinessException.of("po.line.product.required"));
            if (!product.isEnabled() && !onOrder.contains(productId)) {
                throw BusinessException.onField("lines[" + i + "].productId", "po.line.product.inactive", i + 1,
                        product.getCode());
            }
            products.put(productId, product);
        }
        return products;
    }

    private void checkDates(PurchaseOrderDto dto) {
        if (dto.getOrderDate().isAfter(today())) {
            throw BusinessException.onField("orderDate", "po.orderDate.future");
        }
        if (dto.getExpectedDate() != null && dto.getExpectedDate().isBefore(dto.getOrderDate())) {
            throw BusinessException.onField("expectedDate", "po.expectedDate.beforeOrder");
        }
    }

    /** Header from the form; lines matched by id, new ones added, missing ones removed, renumbered 1..n. */
    private static void apply(PurchaseOrder order, Supplier supplier, Map<UUID, Product> products, PurchaseOrderDto dto) {
        order.setSupplier(supplier);
        order.setCurrencyCode(supplier.getCurrencyCode());
        order.setOrderDate(dto.getOrderDate());
        order.setExpectedDate(dto.getExpectedDate());
        order.setIncoterm(dto.getIncoterm());
        order.setSupplierRef(PartyRules.clean(dto.getSupplierRef()));
        order.setNotes(PartyRules.clean(dto.getNotes()));

        Map<UUID, PurchaseOrderLine> existing = order.getLines().stream()
                .collect(Collectors.toMap(PurchaseOrderLine::getId, Function.identity()));
        List<PurchaseOrderLine> kept = new ArrayList<>();
        for (PurchaseOrderDto.Line row : dto.getLines()) {
            PurchaseOrderLine line = row.getId() == null ? null : existing.get(row.getId());
            if (line == null) {
                line = new PurchaseOrderLine();
                line.setPurchaseOrder(order);
            }
            line.setProduct(products.get(row.getProductId()));
            line.setWidthMm(row.getWidthMm());
            line.setHeightMm(row.getHeightMm());
            line.setQuantity(row.getQuantity());
            line.setPricePerM2(row.getPricePerM2());
            kept.add(line);
        }
        order.getLines().removeIf(l -> !kept.contains(l));
        // uk_purchase_order_lines_no is checked at commit, so lines can swap numbers here.
        for (int i = 0; i < kept.size(); i++) {
            PurchaseOrderLine line = kept.get(i);
            line.setLineNo(i + 1);
            if (!order.getLines().contains(line)) {
                order.getLines().add(line);
            }
        }
    }

    private Map<String, Integer> currencyDecimals() {
        return currencyRepo.findAll().stream().collect(Collectors.toMap(Currency::getCode, Currency::getDecimals));
    }
}
