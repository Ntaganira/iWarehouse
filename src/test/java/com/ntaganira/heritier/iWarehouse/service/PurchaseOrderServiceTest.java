package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.dto.PurchaseOrderDto;
import com.ntaganira.heritier.iWarehouse.entity.Product;
import com.ntaganira.heritier.iWarehouse.entity.PurchaseOrder;
import com.ntaganira.heritier.iWarehouse.entity.PurchaseOrderLine;
import com.ntaganira.heritier.iWarehouse.entity.Supplier;
import com.ntaganira.heritier.iWarehouse.enums.*;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Purchase orders (PRC-01): drafts in the supplier's currency, placing, cancelling and closing short. */
class PurchaseOrderServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 8);

    private PurchaseOrderRepository repo;
    private GoodsReceiptRepository receiptRepo;
    private SupplierRepository supplierRepo;
    private ProductRepository productRepo;
    private DocumentNumberService numbers;
    private PurchaseOrderService service;

    private final Supplier shandong = supplier("Shandong Glass", "USD", Incoterm.FOB);
    private final Product clear6 = product("CLR-6");
    private final Product clear8 = product("CLR-8");

    @BeforeEach
    void setUp() {
        repo = mock(PurchaseOrderRepository.class);
        receiptRepo = mock(GoodsReceiptRepository.class);
        supplierRepo = mock(SupplierRepository.class);
        productRepo = mock(ProductRepository.class);
        numbers = mock(DocumentNumberService.class);
        when(numbers.next(DocumentType.PURCHASE_ORDER)).thenReturn("PO-WH-2026-000001");
        when(supplierRepo.findById(shandong.getId())).thenReturn(Optional.of(shandong));
        when(productRepo.findById(clear6.getId())).thenReturn(Optional.of(clear6));
        when(productRepo.findById(clear8.getId())).thenReturn(Optional.of(clear8));
        when(repo.save(any())).thenAnswer(a -> a.getArgument(0));
        Clock clock = Clock.fixed(Instant.parse("2026-10-08T08:00:00Z"), ZoneId.of("Africa/Kigali"));
        service = new PurchaseOrderService(repo, mock(PurchaseOrderLineRepository.class), receiptRepo, supplierRepo,
                productRepo, mock(CurrencyRepository.class), numbers, clock);
    }

    @Test
    void aNewOrderIsADraftInTheSupplierCurrencyWithNumberedLines() {
        PurchaseOrder order = service.create(dto(line(clear6, 20, "4.35"), line(clear8, 10, "5.8")));
        assertThat(order.getNumber()).isEqualTo("PO-WH-2026-000001");
        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.DRAFT);
        assertThat(order.getCurrencyCode()).isEqualTo("USD");
        assertThat(order.getLines()).extracting(PurchaseOrderLine::getLineNo).containsExactly(1, 2);
        assertThat(order.getLines().get(1).getProduct()).isSameAs(clear8);
        assertThat(order.getLines().get(0).getPurchaseOrder()).isSameAs(order);
    }

    @Test
    void anInactiveSupplierOrProductIsRefusedOnANewOrder() {
        shandong.setEnabled(false);
        assertThatThrownBy(() -> service.create(dto(line(clear6, 20, "4.35"))))
                .satisfies(e -> assertThat(((BusinessException) e).getField()).isEqualTo("supplierId"));
        shandong.setEnabled(true);
        clear8.setEnabled(false);
        assertThatThrownBy(() -> service.create(dto(line(clear6, 20, "4.35"), line(clear8, 10, "5.8"))))
                .satisfies(e -> assertThat(((BusinessException) e).getField()).isEqualTo("lines[1].productId"));
    }

    @Test
    void datesCannotBeInTheFutureOrInTheWrongOrder() {
        PurchaseOrderDto future = dto(line(clear6, 20, "4.35"));
        future.setOrderDate(TODAY.plusDays(1));
        assertThatThrownBy(() -> service.create(future))
                .satisfies(e -> assertThat(((BusinessException) e).getField()).isEqualTo("orderDate"));
        PurchaseOrderDto early = dto(line(clear6, 20, "4.35"));
        early.setExpectedDate(TODAY.minusDays(1));
        assertThatThrownBy(() -> service.create(early))
                .satisfies(e -> assertThat(((BusinessException) e).getField()).isEqualTo("expectedDate"));
    }

    @Test
    void editingADraftKeepsMatchedLinesRemovesMissingOnesAndRenumbers() {
        PurchaseOrder order = draftWith(existing(1, clear6, 20), existing(2, clear8, 10), existing(3, clear6, 5));
        PurchaseOrderLine kept = order.getLines().get(2);
        PurchaseOrderDto dto = dto();
        PurchaseOrderDto.Line keep = line(clear6, 8, "4.10");
        keep.setId(kept.getId());
        dto.getLines().add(keep);
        dto.getLines().add(line(clear8, 4, "6"));

        service.update(order.getId(), dto);

        assertThat(order.getLines()).hasSize(2);
        assertThat(order.getLines().get(0)).isSameAs(kept);
        assertThat(kept.getLineNo()).isEqualTo(1);
        assertThat(kept.getQuantity()).isEqualTo(8);
        assertThat(order.getLines().get(1).getLineNo()).isEqualTo(2);
        assertThat(order.getLines().get(1).getId()).isNull();
    }

    @Test
    void aPlacedOrderCannotBeEdited() {
        PurchaseOrder order = draftWith(existing(1, clear6, 20));
        order.setStatus(PurchaseOrderStatus.ORDERED);
        assertThatThrownBy(() -> service.update(order.getId(), dto(line(clear6, 1, "1"))))
                .satisfies(e -> assertThat(((BusinessException) e).getMessageKey()).isEqualTo("po.notDraft"));
    }

    @Test
    void placingNeedsLinesAndAnActiveSupplierAndProducts() {
        PurchaseOrder empty = draftWith();
        assertThatThrownBy(() -> service.place(empty.getId()))
                .satisfies(e -> assertThat(((BusinessException) e).getMessageKey()).isEqualTo("po.place.noLines"));
        PurchaseOrder order = draftWith(existing(1, clear6, 20));
        clear6.setEnabled(false);
        assertThatThrownBy(() -> service.place(order.getId()))
                .satisfies(e -> assertThat(((BusinessException) e).getMessageKey()).isEqualTo("po.line.product.inactive"));
        clear6.setEnabled(true);
        service.place(order.getId());
        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.ORDERED);
        assertThat(order.getOrderedAt()).isNotNull();
    }

    @Test
    void onlyAnOrderWithNothingReceivedCanBeCancelled() {
        PurchaseOrder order = draftWith(existing(1, clear6, 20));
        order.setStatus(PurchaseOrderStatus.PARTIALLY_RECEIVED);
        assertThatThrownBy(() -> service.cancel(order.getId(), "no longer needed"))
                .satisfies(e -> assertThat(((BusinessException) e).getMessageKey()).isEqualTo("po.cancel.notAllowed"));
        order.setStatus(PurchaseOrderStatus.ORDERED);
        service.cancel(order.getId(), "  supplier out of stock ");
        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.CANCELLED);
        assertThat(order.getClosedReason()).isEqualTo("supplier out of stock");
    }

    @Test
    void onlyAPartlyReceivedOrderCanBeClosedShortAndNotWithDraftReceiptsWaiting() {
        PurchaseOrder order = draftWith(existing(1, clear6, 20));
        order.setStatus(PurchaseOrderStatus.ORDERED);
        assertThatThrownBy(() -> service.close(order.getId(), "rest not coming"))
                .satisfies(e -> assertThat(((BusinessException) e).getMessageKey()).isEqualTo("po.close.notAllowed"));
        order.setStatus(PurchaseOrderStatus.PARTIALLY_RECEIVED);
        when(receiptRepo.countByPurchaseOrder_IdAndStatus(order.getId(), GoodsReceiptStatus.DRAFT)).thenReturn(1L);
        assertThatThrownBy(() -> service.close(order.getId(), "rest not coming"))
                .satisfies(e -> assertThat(((BusinessException) e).getMessageKey()).isEqualTo("po.draftReceipts"));
        when(receiptRepo.countByPurchaseOrder_IdAndStatus(order.getId(), GoodsReceiptStatus.DRAFT)).thenReturn(0L);
        service.close(order.getId(), "rest not coming");
        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.CLOSED);
    }

    // ---------------------------------------------------------------- helpers

    private PurchaseOrderDto dto(PurchaseOrderDto.Line... lines) {
        PurchaseOrderDto dto = new PurchaseOrderDto();
        dto.setSupplierId(shandong.getId());
        dto.setOrderDate(TODAY);
        dto.setIncoterm(Incoterm.FOB);
        dto.getLines().addAll(List.of(lines));
        return dto;
    }

    private static PurchaseOrderDto.Line line(Product product, int qty, String price) {
        PurchaseOrderDto.Line line = new PurchaseOrderDto.Line();
        line.setProductId(product.getId());
        line.setWidthMm(3210);
        line.setHeightMm(2250);
        line.setQuantity(qty);
        line.setPricePerM2(new BigDecimal(price));
        return line;
    }

    private PurchaseOrder draftWith(PurchaseOrderLine... lines) {
        PurchaseOrder order = new PurchaseOrder();
        order.setId(UUID.randomUUID());
        order.setNumber("PO-WH-2026-000009");
        order.setSupplier(shandong);
        order.setCurrencyCode("USD");
        order.setOrderDate(TODAY);
        for (PurchaseOrderLine l : lines) {
            l.setPurchaseOrder(order);
            order.getLines().add(l);
        }
        when(repo.findWithLinesById(order.getId())).thenReturn(Optional.of(order));
        return order;
    }

    private static PurchaseOrderLine existing(int no, Product product, int qty) {
        PurchaseOrderLine line = new PurchaseOrderLine();
        line.setId(UUID.randomUUID());
        line.setLineNo(no);
        line.setProduct(product);
        line.setWidthMm(3210);
        line.setHeightMm(2250);
        line.setQuantity(qty);
        line.setPricePerM2(new BigDecimal("4"));
        return line;
    }

    private static Supplier supplier(String name, String currency, Incoterm incoterm) {
        Supplier s = new Supplier();
        s.setId(UUID.randomUUID());
        s.setName(name);
        s.setCurrencyCode(currency);
        s.setIncoterm(incoterm);
        return s;
    }

    private static Product product(String code) {
        Product p = new Product();
        p.setId(UUID.randomUUID());
        p.setCode(code);
        p.setGlassType(GlassType.CLEAR);
        p.setThicknessMm(new BigDecimal("6"));
        return p;
    }
}
