package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.dto.GoodsReceiptDto;
import com.ntaganira.heritier.iWarehouse.entity.*;
import com.ntaganira.heritier.iWarehouse.enums.*;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.repository.CrateBatchRepository;
import com.ntaganira.heritier.iWarehouse.repository.GoodsReceiptRepository;
import com.ntaganira.heritier.iWarehouse.repository.ProductRepository;
import com.ntaganira.heritier.iWarehouse.repository.PurchaseOrderRepository;
import com.ntaganira.heritier.iWarehouse.service.ExchangeRateService.AppliedRate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Goods receipts (PRC-02, PRC-06): checks on crates and racks, and posting into stock with MAC (PRC-05). */
class GoodsReceiptServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 8);

    private GoodsReceiptRepository repo;
    private PurchaseOrderRepository orderRepo;
    private ProductRepository productRepo;
    private StockService stockService;
    private ExchangeRateService rateService;
    private GoodsReceiptService service;

    private final Product clear6 = product("CLR-6", "6");
    private final Product clear8 = product("CLR-8", "8");
    private final Location zone = location("WH-A", LocationType.ZONE, null, null, null, false);
    private final Location rack1 = location("WH-A-R01", LocationType.RACK, zone.getId(), 3000, 30, false);
    private final Location rack2 = location("WH-A-R02", LocationType.RACK, zone.getId(), null, null, false);
    private final Location offcut = location("WH-A-OC", LocationType.RACK, zone.getId(), 1000, 100, true);
    private final Location slot = location("WH-A-R01-S01", LocationType.SLOT, rack1.getId(), null, null, false);
    private PurchaseOrder order;
    private PurchaseOrderLine line1;
    private PurchaseOrderLine line2;

    @BeforeEach
    void setUp() {
        repo = mock(GoodsReceiptRepository.class);
        orderRepo = mock(PurchaseOrderRepository.class);
        productRepo = mock(ProductRepository.class);
        rateService = mock(ExchangeRateService.class);
        SettingService settings = mock(SettingService.class);
        when(settings.getDecimal(SettingKey.GLASS_DENSITY)).thenReturn(new BigDecimal("2.5"));
        // Real static helpers (rackOf, receivingLocations) over mocked data.
        stockService = mock(StockService.class);
        Map<UUID, Location> byId = new HashMap<>();
        for (Location l : List.of(zone, rack1, rack2, offcut, slot)) {
            byId.put(l.getId(), l);
        }
        when(stockService.locationsById()).thenReturn(byId);
        when(stockService.receivingLocations(any())).thenReturn(List.of(rack1, rack2, slot));
        when(stockService.rackLoads(any())).thenReturn(new HashMap<>());

        order = new PurchaseOrder();
        order.setId(UUID.randomUUID());
        order.setNumber("PO-WH-2026-000001");
        order.setStatus(PurchaseOrderStatus.ORDERED);
        order.setOrderDate(TODAY.minusDays(40));
        order.setCurrencyCode("USD");
        line1 = orderLine(1, clear6, 3210, 2250, 20, "4.35");
        line2 = orderLine(2, clear8, 3210, 2250, 10, "5.80");
        order.getLines().addAll(List.of(line1, line2));
        when(orderRepo.findWithLinesById(order.getId())).thenReturn(Optional.of(order));
        when(orderRepo.lockById(order.getId())).thenReturn(Optional.of(order));

        Clock clock = Clock.fixed(Instant.parse("2026-10-08T08:00:00Z"), ZoneId.of("Africa/Kigali"));
        service = new GoodsReceiptService(repo, mock(CrateBatchRepository.class), orderRepo, productRepo, stockService,
                mock(PostingService.class), rateService, mock(DocumentNumberService.class), settings, clock);
    }

    // ---------------------------------------------------------------- checks

    @Test
    void cratesOfALineCannotBringMoreThanIsStillToCome() {
        line1.setReceivedQty(15); // 5 to come
        List<GoodsReceiptService.CrateInput> crates = List.of(
                input(0, line1, "C-01", 3, 0, rack1),
                input(1, line1, "C-02", 2, 1, rack1));
        assertThatThrownBy(() -> service.check(order, TODAY, crates))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException b = (BusinessException) e;
                    assertThat(b.getMessageKey()).isEqualTo("receipt.crate.overOrdered");
                    assertThat(b.getField()).isEqualTo("crates[0].sheets");
                    assertThat(b.getArgs()).containsExactly(1, "CLR-6", 5, 6);
                });
        // Exactly what is still to come is fine, broken sheets included.
        service.check(order, TODAY, List.of(input(0, line1, "C-01", 4, 1, rack1)));
    }

    @Test
    void crateMarkingsAreUniqueOnAReceiptIgnoringCase() {
        List<GoodsReceiptService.CrateInput> crates = List.of(
                input(0, line1, "C-01", 5, 0, rack1),
                input(1, line2, " c-01 ", 5, 0, rack2));
        assertThatThrownBy(() -> service.check(order, TODAY, crates))
                .satisfies(e -> assertThat(((BusinessException) e).getField()).isEqualTo("crates[1].batchNo"));
    }

    @Test
    void aCrateNeedsASheetAndAReceivingRack() {
        assertThatThrownBy(() -> service.check(order, TODAY, List.of(input(0, line1, "C-01", 0, 0, rack1))))
                .satisfies(e -> assertThat(((BusinessException) e).getMessageKey()).isEqualTo("receipt.crate.empty"));
        assertThatThrownBy(() -> service.check(order, TODAY, List.of(input(0, line1, "C-01", 5, 0, offcut))))
                .satisfies(e -> assertThat(((BusinessException) e).getField()).isEqualTo("crates[0].locationId"));
    }

    @Test
    void receiptDateIsBetweenTheOrderDateAndToday() {
        List<GoodsReceiptService.CrateInput> crates = List.of(input(0, line1, "C-01", 5, 0, rack1));
        assertThatThrownBy(() -> service.check(order, TODAY.plusDays(1), crates))
                .satisfies(e -> assertThat(((BusinessException) e).getMessageKey()).isEqualTo("receipt.date.future"));
        assertThatThrownBy(() -> service.check(order, order.getOrderDate().minusDays(1), crates))
                .satisfies(e -> assertThat(((BusinessException) e).getMessageKey()).isEqualTo("receipt.date.beforeOrder"));
    }

    @Test
    void rackMustTakeTheExtraPiecesCountingItsSlots() {
        when(stockService.rackLoads(any())).thenReturn(new HashMap<>(Map.of(rack1.getId(), new RackLoad(25, new BigDecimal("900")))));
        // 3 on the rack + 3 in its slot = 6 more on a 30-piece rack holding 25
        List<GoodsReceiptService.CrateInput> crates = List.of(
                input(0, line1, "C-01", 3, 0, rack1),
                input(1, line2, "C-02", 3, 0, slot));
        assertThatThrownBy(() -> service.check(order, TODAY, crates))
                .satisfies(e -> {
                    BusinessException b = (BusinessException) e;
                    assertThat(b.getMessageKey()).isEqualTo("receipt.rack.pieces");
                    assertThat(b.getField()).isEqualTo("crates[0].locationId");
                    assertThat(b.getArgs()).containsExactly("WH-A-R01", 25L, 30, 6L);
                });
        // 5 more fits exactly
        service.check(order, TODAY, List.of(input(0, line1, "C-01", 5, 0, rack1)));
    }

    @Test
    void rackMustTakeTheExtraWeight() {
        // 6 mm: 7.2225 m2 x 15 kg = 108.34 kg a sheet; rack holds 2,800 of 3,000 kg -> 1 sheet fits, 2 do not
        when(stockService.rackLoads(any())).thenReturn(new HashMap<>(Map.of(rack1.getId(), new RackLoad(5, new BigDecimal("2800")))));
        service.check(order, TODAY, List.of(input(0, line1, "C-01", 1, 0, rack1)));
        assertThatThrownBy(() -> service.check(order, TODAY, List.of(input(0, line1, "C-01", 2, 0, rack1))))
                .satisfies(e -> assertThat(((BusinessException) e).getMessageKey()).isEqualTo("receipt.rack.weight"));
        // A rack without limits takes anything
        service.check(order, TODAY, List.of(input(0, line1, "C-01", 20, 0, rack2)));
    }

    @Test
    void anOrderMustBePlacedAndWaitingToBeReceived() {
        order.setStatus(PurchaseOrderStatus.DRAFT);
        GoodsReceiptDto dto = new GoodsReceiptDto();
        dto.setReceivedDate(TODAY);
        assertThatThrownBy(() -> service.create(order.getId(), dto))
                .satisfies(e -> assertThat(((BusinessException) e).getMessageKey()).isEqualTo("receipt.order.notReceivable"));
    }

    @Test
    void newFormHasOneCrateRowPerLineStillToCome() {
        line2.setReceivedQty(10);
        GoodsReceiptDto dto = service.newForm(order);
        assertThat(dto.getReceivedDate()).isEqualTo(TODAY);
        assertThat(dto.getCrates()).hasSize(1);
        assertThat(dto.getCrates().get(0).getPoLineId()).isEqualTo(line1.getId());
        assertThat(dto.getCrates().get(0).getWidthMm()).isEqualTo(3210);
    }

    // ---------------------------------------------------------------- posting

    @Test
    void postingCreatesUnitsFixesTheRateAndUpdatesOrderAndMac() {
        GoodsReceipt receipt = draft(crate(line1, "C-01", 18, 2, rack1), crate(line2, "C-02", 10, 0, rack2));
        stubPosting(receipt);
        clear6.setMacPerM2(new BigDecimal("6000"));
        when(stockService.heldArea(clear6.getId())).thenReturn(new BigDecimal("72.225")); // 10 sheets held
        when(stockService.heldArea(clear8.getId())).thenReturn(BigDecimal.ZERO);
        when(rateService.rateFor("USD", TODAY)).thenReturn(new AppliedRate("USD", new BigDecimal("1450"), TODAY, RateSource.BNR));
        when(stockService.receive(any(), any(), any(), any())).thenAnswer(a -> {
            CrateBatch c = a.getArgument(0);
            return Collections.nCopies(c.getSheets(), new StockUnit());
        });

        GoodsReceiptService.PostResult result = service.post(receipt.getId());

        assertThat(result.units()).isEqualTo(28);
        assertThat(receipt.getStatus()).isEqualTo(GoodsReceiptStatus.POSTED);
        assertThat(receipt.getRate()).isEqualByComparingTo("1450");
        assertThat(receipt.getRateDate()).isEqualTo(TODAY);
        assertThat(receipt.getRateSource()).isEqualTo(RateSource.BNR);
        assertThat(receipt.getPostedAt()).isNotNull();
        // 4.35 USD x 1,450 = 6,307.50 RWF/m2; 7.2225 m2 -> 45,555.92 a sheet; 6 mm -> 108.34 kg
        CrateBatch c1 = receipt.getCrates().get(0);
        assertThat(c1.getCostPerM2()).isEqualByComparingTo("6307.5000");
        verify(stockService).receive(eq(c1), argThat(w -> w.compareTo(new BigDecimal("108.34")) == 0),
                argThat(cost -> cost.compareTo(new BigDecimal("45555.92")) == 0), eq(receipt));
        // Broken sheets count as delivered on the order line, not as stock
        assertThat(line1.getReceivedQty()).isEqualTo(20);
        assertThat(line2.getReceivedQty()).isEqualTo(10);
        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.RECEIVED);
        // MAC: (72.225 x 6,000 + 18 x 45,555.92) / (72.225 + 130.005) = 1,253,356.56 / 202.23 = 6,197.6787
        assertThat(clear6.getMacPerM2()).isEqualByComparingTo("6197.6787");
        // First receipt of 8 mm: 5.80 x 1,450 = 8,410 /m2 -> 60,741.23 a sheet -> MAC 8,410.0003 (unit costs are rounded)
        assertThat(clear8.getMacPerM2()).isEqualByComparingTo(new BigDecimal("60741.23").multiply(BigDecimal.TEN)
                .divide(new BigDecimal("72.2250"), 4, java.math.RoundingMode.HALF_UP));
    }

    @Test
    void partOfAnOrderLeavesItPartlyReceived() {
        GoodsReceipt receipt = draft(crate(line1, "C-01", 10, 0, rack2));
        stubPosting(receipt);
        when(stockService.heldArea(any())).thenReturn(BigDecimal.ZERO);
        when(rateService.rateFor("USD", TODAY)).thenReturn(new AppliedRate("USD", new BigDecimal("1450"), TODAY, RateSource.BNR));
        when(stockService.receive(any(), any(), any(), any())).thenReturn(List.of());
        service.post(receipt.getId());
        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.PARTIALLY_RECEIVED);
    }

    @Test
    void noRateNoPosting() {
        GoodsReceipt receipt = draft(crate(line1, "C-01", 10, 0, rack2));
        stubPosting(receipt);
        when(rateService.rateFor("USD", TODAY)).thenThrow(BusinessException.of("rate.missing", "USD", "BNR", TODAY));
        assertThatThrownBy(() -> service.post(receipt.getId()))
                .satisfies(e -> assertThat(((BusinessException) e).getMessageKey()).isEqualTo("rate.missing"));
        assertThat(receipt.getStatus()).isEqualTo(GoodsReceiptStatus.DRAFT);
        verify(stockService, never()).receive(any(), any(), any(), any());
        assertThat(line1.getReceivedQty()).isZero();
    }

    @Test
    void aPostedReceiptCannotBePostedOrCancelledAgain() {
        GoodsReceipt receipt = draft(crate(line1, "C-01", 10, 0, rack2));
        receipt.setStatus(GoodsReceiptStatus.POSTED);
        stubPosting(receipt);
        when(repo.findById(receipt.getId())).thenReturn(Optional.of(receipt));
        assertThatThrownBy(() -> service.post(receipt.getId()))
                .satisfies(e -> assertThat(((BusinessException) e).getMessageKey()).isEqualTo("receipt.notDraft"));
        assertThatThrownBy(() -> service.cancel(receipt.getId(), "typo"))
                .satisfies(e -> assertThat(((BusinessException) e).getMessageKey()).isEqualTo("receipt.notDraft"));
    }

    // ---------------------------------------------------------------- helpers

    private void stubPosting(GoodsReceipt receipt) {
        when(repo.findOrderId(receipt.getId())).thenReturn(Optional.of(order.getId()));
        List<Product> products = receipt.getCrates().stream().map(CrateBatch::getProduct).distinct().toList();
        when(repo.findProductIds(receipt.getId())).thenReturn(products.stream().map(Product::getId).toList());
        when(productRepo.lockAllById(any())).thenReturn(products);
        when(repo.findWithCratesById(receipt.getId())).thenReturn(Optional.of(receipt));
    }

    private GoodsReceipt draft(CrateBatch... crates) {
        GoodsReceipt receipt = new GoodsReceipt();
        receipt.setId(UUID.randomUUID());
        receipt.setNumber("GRN-WH-2026-000001");
        receipt.setPurchaseOrder(order);
        receipt.setCurrencyCode("USD");
        receipt.setReceivedDate(TODAY);
        for (CrateBatch c : crates) {
            c.setGoodsReceipt(receipt);
            receipt.getCrates().add(c);
        }
        return receipt;
    }

    private static CrateBatch crate(PurchaseOrderLine line, String batch, int sheets, int broken, Location location) {
        CrateBatch c = new CrateBatch();
        c.setId(UUID.randomUUID());
        c.setPoLine(line);
        c.setProduct(line.getProduct());
        c.setBatchNo(batch);
        c.setWidthMm(line.getWidthMm());
        c.setHeightMm(line.getHeightMm());
        c.setSheets(sheets);
        c.setBroken(broken);
        c.setLocation(location);
        return c;
    }

    private static GoodsReceiptService.CrateInput input(int index, PurchaseOrderLine line, String batch, int sheets,
                                                        int broken, Location location) {
        return new GoodsReceiptService.CrateInput(index, line.getId(), batch, line.getWidthMm(), line.getHeightMm(),
                sheets, broken, location.getId());
    }

    private PurchaseOrderLine orderLine(int no, Product product, int w, int h, int qty, String price) {
        PurchaseOrderLine line = new PurchaseOrderLine();
        line.setId(UUID.randomUUID());
        line.setPurchaseOrder(order);
        line.setLineNo(no);
        line.setProduct(product);
        line.setWidthMm(w);
        line.setHeightMm(h);
        line.setQuantity(qty);
        line.setPricePerM2(new BigDecimal(price));
        return line;
    }

    private static Product product(String code, String thickness) {
        Product p = new Product();
        p.setId(UUID.randomUUID());
        p.setCode(code);
        p.setGlassType(GlassType.CLEAR);
        p.setThicknessMm(new BigDecimal(thickness));
        return p;
    }

    private static Location location(String code, LocationType type, UUID parentId, Integer maxKg, Integer maxPieces,
                                     boolean offcut) {
        Location l = new Location();
        l.setId(UUID.randomUUID());
        l.setCode(code);
        l.setType(type);
        l.setParentId(parentId);
        l.setMaxWeightKg(maxKg);
        l.setMaxPieces(maxPieces);
        l.setOffcut(offcut);
        l.setEnabled(true);
        return l;
    }
}
