package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.dto.ShipmentDto;
import com.ntaganira.heritier.iWarehouse.entity.*;
import com.ntaganira.heritier.iWarehouse.entity.Currency;
import com.ntaganira.heritier.iWarehouse.enums.*;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.repository.*;
import com.ntaganira.heritier.iWarehouse.service.ExchangeRateService.AppliedRate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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

/**
 * Shipments (PRC-03..06): posting bills at the rate of their date, sharing them over crates and sheets,
 * expensing the share of units gone, the broken share, MAC; locks after posting; claims.
 */
class ShipmentServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 8);
    private static final LocalDate BILL_DATE = LocalDate.of(2026, 10, 2);

    private ShipmentRepository repo;
    private ShipmentReceiptRepository linkRepo;
    private ShipmentAllocationRepository allocationRepo;
    private GoodsReceiptRepository receiptRepo;
    private CrateBatchRepository crateRepo;
    private ProductRepository productRepo;
    private StockUnitRepository unitRepo;
    private StockCostEntryRepository costEntryRepo;
    private ExchangeRateService rateService;
    private ShipmentService service;

    private final Product clear6 = product("CLR-6", "6", "6100.0000");
    private final Product clear8 = product("CLR-8", "8", "8122.4000");
    private Shipment shipment;
    private GoodsReceipt receipt;
    private CrateBatch crateA;
    private CrateBatch crateB;
    private List<StockUnit> units;

    @BeforeEach
    void setUp() {
        repo = mock(ShipmentRepository.class);
        linkRepo = mock(ShipmentReceiptRepository.class);
        allocationRepo = mock(ShipmentAllocationRepository.class);
        receiptRepo = mock(GoodsReceiptRepository.class);
        crateRepo = mock(CrateBatchRepository.class);
        productRepo = mock(ProductRepository.class);
        unitRepo = mock(StockUnitRepository.class);
        costEntryRepo = mock(StockCostEntryRepository.class);
        rateService = mock(ExchangeRateService.class);
        SupplierRepository supplierRepo = mock(SupplierRepository.class);
        CurrencyRepository currencyRepo = mock(CurrencyRepository.class);
        Currency rwf = currency("RWF", 0, true, true);
        Currency usd = currency("USD", 2, false, true);
        Currency eur = currency("EUR", 2, false, false);
        when(currencyRepo.findByBaseCurrencyTrue()).thenReturn(Optional.of(rwf));
        when(currencyRepo.findAllByOrderByBaseCurrencyDescEnabledDescCodeAsc()).thenReturn(List.of(rwf, usd, eur));
        SettingService settings = mock(SettingService.class);
        when(settings.getDecimal(SettingKey.GLASS_DENSITY)).thenReturn(new BigDecimal("2.5"));

        when(rateService.rateFor(eq("USD"), any())).thenAnswer(a -> new AppliedRate("USD", new BigDecimal("1452.10"), a.getArgument(1), RateSource.BNR));
        when(rateService.rateFor(eq("USD"), any(), eq(RateSource.CUSTOMS))).thenAnswer(a -> new AppliedRate("USD", new BigDecimal("1449.00"), a.getArgument(1), RateSource.CUSTOMS));
        when(rateService.rateFor(eq("RWF"), any())).thenAnswer(a -> new AppliedRate("RWF", BigDecimal.ONE, a.getArgument(1), null));

        Clock clock = Clock.fixed(Instant.parse("2026-10-08T08:00:00Z"), ZoneId.of("Africa/Kigali"));
        StockService stockService = new StockService(unitRepo, mock(StockMovementRepository.class), costEntryRepo,
                mock(LocationRepository.class), mock(DocumentNumberService.class), clock);
        service = new ShipmentService(repo, linkRepo, mock(ShipmentCostRepository.class), allocationRepo, receiptRepo,
                crateRepo, productRepo, supplierRepo, currencyRepo, stockService, rateService,
                mock(DocumentNumberService.class), settings, clock);

        // One posted receipt: crate A (CLR-6, 3 good + 1 broken), crate B (CLR-8, 2 good)
        receipt = new GoodsReceipt();
        receipt.setId(UUID.randomUUID());
        receipt.setNumber("GRN-WH-2026-000001");
        receipt.setStatus(GoodsReceiptStatus.POSTED);
        crateA = crate(receipt, clear6, "C-01", 3210, 2250, 3, 1, "6091.0500");
        crateB = crate(receipt, clear8, "C-02", 2440, 1830, 2, 0, "8122.4000");
        units = new ArrayList<>(List.of(
                unit("U-WH-000001", crateA, StockStatus.AVAILABLE, "43992.61"),
                unit("U-WH-000002", crateA, StockStatus.SOLD, "43992.61"),
                unit("U-WH-000003", crateA, StockStatus.RESERVED, "43992.61"),
                unit("U-WH-000004", crateB, StockStatus.AVAILABLE, "36268.10"),
                unit("U-WH-000005", crateB, StockStatus.AVAILABLE, "36268.10")));
        when(unitRepo.findByCrateBatch_IdInOrderByCode(any())).thenReturn(units);
        when(crateRepo.findByReceipts(any())).thenReturn(List.of(crateA, crateB));
        when(productRepo.lockAllById(any())).thenReturn(List.of(clear6, clear8));
        when(unitRepo.sumArea(eq(clear6.getId()), any())).thenReturn(new BigDecimal("114.445"));
        when(unitRepo.sumArea(eq(clear8.getId()), any())).thenReturn(new BigDecimal("8.9304"));

        shipment = new Shipment();
        shipment.setId(UUID.randomUUID());
        shipment.setNumber("SHP-WH-2026-000001");
        shipment.setArrivalDate(TODAY.minusDays(7));
        ShipmentReceipt link = new ShipmentReceipt();
        link.setShipment(shipment);
        link.setGoodsReceipt(receipt);
        link.setReceiptNumber(receipt.getNumber());
        shipment.getReceipts().add(link);
        when(repo.lockById(shipment.getId())).thenReturn(Optional.of(shipment));
        when(repo.findDetailedById(shipment.getId())).thenReturn(Optional.of(shipment));
        when(repo.findById(shipment.getId())).thenReturn(Optional.of(shipment));
    }

    // ---------------------------------------------------------------- posting

    @Test
    void postingConvertsEachBillAtItsRateAndSharesTheRoundedTotalOverCratesAndSheets() {
        bill(1, CostType.FREIGHT, "USD", "300.00");    // 435,630 at the BNR rate
        bill(2, CostType.CLEARING, "RWF", "150000");   // 150,000
        bill(3, CostType.DUTY, "USD", "100.00");       // 144,900 at the customs rate

        ShipmentService.PostResult result = service.post(shipment.getId());

        assertThat(result.postingNo()).isEqualTo(1);
        assertThat(result.lines()).isEqualTo(3);
        assertThat(result.total()).isEqualByComparingTo("730530");
        ShipmentCost duty = cost(2);
        assertThat(duty.getStatus()).isEqualTo(ShipmentCostStatus.POSTED);
        assertThat(duty.getRate()).isEqualByComparingTo("1449.00");
        assertThat(duty.getRateSource()).isEqualTo(RateSource.CUSTOMS);
        assertThat(duty.getRateDate()).isEqualTo(BILL_DATE);
        assertThat(duty.getPostingNo()).isEqualTo(1);
        assertThat(cost(1).getRateSource()).isNull(); // RWF: rate 1, no source

        // By area, broken sheets included: crate A 28.89 m², crate B 8.9304 m²
        ArgumentCaptor<ShipmentAllocation> captor = ArgumentCaptor.forClass(ShipmentAllocation.class);
        verify(allocationRepo, times(2)).save(captor.capture());
        ShipmentAllocation a = captor.getAllValues().get(0);
        ShipmentAllocation b = captor.getAllValues().get(1);
        assertThat(a.getBasis()).isEqualByComparingTo("28.89");
        assertThat(a.getAmount()).isEqualByComparingTo("558032.48");
        assertThat(b.getAmount()).isEqualByComparingTo("172497.52");
        // Crate A: 4 pieces of 139,508.12; the sold sheet's part is expensed, the broken one goes to the claim
        assertThat(a.getStockAmount()).isEqualByComparingTo("279016.24");
        assertThat(a.getExpensedAmount()).isEqualByComparingTo("139508.12");
        assertThat(a.getBrokenAmount()).isEqualByComparingTo("139508.12");
        assertThat(b.getStockAmount()).isEqualByComparingTo("172497.52");

        assertThat(units.get(0).getUnitCost()).isEqualByComparingTo("183500.73");
        assertThat(units.get(1).getUnitCost()).isEqualByComparingTo("43992.61"); // sold: unchanged
        assertThat(units.get(2).getUnitCost()).isEqualByComparingTo("183500.73"); // reserved is still in stock
        assertThat(units.get(3).getUnitCost()).isEqualByComparingTo("122516.86");
        verify(costEntryRepo, times(4)).save(any());
        assertThat(result.units()).isEqualTo(4);
        assertThat(result.toStock().add(result.expensed()).add(result.broken())).isEqualByComparingTo(result.total());

        // MAC moves by what reached stock over the m² held
        assertThat(clear6.getMacPerM2()).isEqualByComparingTo("8537.9941");
        assertThat(clear8.getMacPerM2()).isEqualByComparingTo("27438.1664");
    }

    @Test
    void laterBillsArePostedAsTheNextPostingAndOnlyTheyAreAllocated() {
        bill(1, CostType.FREIGHT, "USD", "300.00");
        service.post(shipment.getId());
        bill(2, CostType.TRANSPORT, "RWF", "37820.40");

        ShipmentService.PostResult second = service.post(shipment.getId());

        assertThat(second.postingNo()).isEqualTo(2);
        assertThat(second.lines()).isEqualTo(1);
        assertThat(second.total()).isEqualByComparingTo("37820"); // rounded once to whole francs
        assertThat(cost(0).getPostingNo()).isEqualTo(1);
    }

    @Test
    void aCreditNoteTakesCostBackOff() {
        bill(1, CostType.CLEARING, "RWF", "150000");
        service.post(shipment.getId());
        BigDecimal before = units.get(3).getUnitCost();
        bill(2, CostType.CLEARING, "RWF", "-37820.40");

        ShipmentService.PostResult credit = service.post(shipment.getId());

        assertThat(credit.total()).isEqualByComparingTo("-37820");
        assertThat(units.get(3).getUnitCost()).isLessThan(before);
    }

    @Test
    void nothingToPostOrNoReceiptsIsRefused() {
        assertThatThrownBy(() -> service.post(shipment.getId()))
                .satisfies(e -> assertThat(((BusinessException) e).getMessageKey()).isEqualTo("shipment.post.noDrafts"));
        bill(1, CostType.FREIGHT, "USD", "300.00");
        shipment.getReceipts().clear();
        assertThatThrownBy(() -> service.post(shipment.getId()))
                .satisfies(e -> assertThat(((BusinessException) e).getMessageKey()).isEqualTo("shipment.post.noReceipts"));
    }

    @Test
    void aMissingRateRefusesTheWholePosting() {
        bill(1, CostType.FREIGHT, "USD", "300.00");
        when(rateService.rateFor(eq("USD"), any())).thenThrow(BusinessException.of("rate.missing", "USD", "BNR", BILL_DATE));
        assertThatThrownBy(() -> service.post(shipment.getId()))
                .satisfies(e -> assertThat(((BusinessException) e).getMessageKey()).isEqualTo("rate.missing"));
        verifyNoInteractions(allocationRepo);
    }

    // ---------------------------------------------------------------- form rules

    @Test
    void receiptsAndMethodAreFixedOnceBillsArePosted() {
        bill(1, CostType.FREIGHT, "USD", "300.00");
        service.post(shipment.getId());
        when(linkRepo.findByGoodsReceipt_Id(receipt.getId())).thenReturn(Optional.of(shipment.getReceipts().iterator().next()));
        when(receiptRepo.findById(receipt.getId())).thenReturn(Optional.of(receipt));

        ShipmentDto dto = dto();
        dto.setAllocationMethod(AllocationMethod.VALUE);
        assertThatThrownBy(() -> service.update(shipment.getId(), dto))
                .satisfies(e -> assertThat(((BusinessException) e).getField()).isEqualTo("allocationMethod"));

        ShipmentDto noReceipts = dto();
        noReceipts.getReceiptIds().clear();
        assertThatThrownBy(() -> service.update(shipment.getId(), noReceipts))
                .satisfies(e -> assertThat(((BusinessException) e).getMessageKey()).isEqualTo("shipment.receipts.locked"));
    }

    @Test
    void aReceiptJoinsOneShipmentAndOnlyOncePosted() {
        Shipment other = new Shipment();
        other.setId(UUID.randomUUID());
        other.setNumber("SHP-WH-2026-000009");
        ShipmentReceipt elsewhere = new ShipmentReceipt();
        elsewhere.setShipment(other);
        when(linkRepo.findByGoodsReceipt_Id(receipt.getId())).thenReturn(Optional.of(elsewhere));
        when(receiptRepo.findById(receipt.getId())).thenReturn(Optional.of(receipt));
        assertThatThrownBy(() -> service.receipts(dto(), shipment))
                .satisfies(e -> assertThat(((BusinessException) e).getArgs()).containsExactly("GRN-WH-2026-000001", "SHP-WH-2026-000009"));

        receipt.setStatus(GoodsReceiptStatus.DRAFT);
        assertThatThrownBy(() -> service.receipts(dto(), shipment))
                .satisfies(e -> assertThat(((BusinessException) e).getMessageKey()).isEqualTo("shipment.receipt.notPosted"));
    }

    @Test
    void billsAreDatedTodayAtTheLatestInAnActiveCurrencyAndNotZero() {
        ShipmentDto dto = dto();
        dto.getCosts().add(cost(CostType.FREIGHT, "USD", "100", TODAY.plusDays(1)));
        assertThatThrownBy(() -> service.checkCosts(dto))
                .satisfies(e -> assertThat(((BusinessException) e).getField()).isEqualTo("costs[0].invoiceDate"));
        dto.getCosts().set(0, cost(CostType.FREIGHT, "EUR", "100", TODAY));
        assertThatThrownBy(() -> service.checkCosts(dto))
                .satisfies(e -> assertThat(((BusinessException) e).getField()).isEqualTo("costs[0].currencyCode"));
        dto.getCosts().set(0, cost(CostType.FREIGHT, "usd", "0.00", TODAY));
        assertThatThrownBy(() -> service.checkCosts(dto))
                .satisfies(e -> assertThat(((BusinessException) e).getField()).isEqualTo("costs[0].amount"));
        dto.getCosts().set(0, cost(CostType.FREIGHT, "usd", "-5", TODAY));
        service.checkCosts(dto); // a credit note is fine
    }

    // ---------------------------------------------------------------- close, cancel

    @Test
    void cancelOnlyBeforeAnythingIsPostedAndCloseOnlyWithoutDrafts() {
        bill(1, CostType.FREIGHT, "USD", "300.00");
        assertThatThrownBy(() -> service.close(shipment.getId()))
                .satisfies(e -> assertThat(((BusinessException) e).getMessageKey()).isEqualTo("shipment.close.drafts"));
        service.post(shipment.getId());
        assertThatThrownBy(() -> service.cancel(shipment.getId(), "duplicate"))
                .satisfies(e -> assertThat(((BusinessException) e).getMessageKey()).isEqualTo("shipment.cancel.posted"));
        service.close(shipment.getId());
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.CLOSED);
        assertThatThrownBy(() -> service.post(shipment.getId()))
                .satisfies(e -> assertThat(((BusinessException) e).getMessageKey()).isEqualTo("shipment.notOpen"));
    }

    @Test
    void cancellingFreesTheReceipts() {
        service.cancel(shipment.getId(), " entered twice ");
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
        assertThat(shipment.getCancelReason()).isEqualTo("entered twice");
        assertThat(shipment.getReceipts()).isEmpty();
    }

    // ---------------------------------------------------------------- claim (PRC-06)

    @Test
    void aClaimIsOpenedForBrokenSheetsThenSettledOrRejected() {
        Shipment opened = service.openClaim(shipment.getId(), " Shandong Glass Co. ", "CLM-77", TODAY, new BigDecimal("88050.00"));
        assertThat(opened.getClaimStatus()).isEqualTo(ClaimStatus.OPEN);
        assertThat(opened.getClaimParty()).isEqualTo("Shandong Glass Co.");
        assertThatThrownBy(() -> service.openClaim(shipment.getId(), "Insurer", null, TODAY, BigDecimal.TEN))
                .satisfies(e -> assertThat(((BusinessException) e).getMessageKey()).isEqualTo("shipment.claim.exists"));

        service.settleClaim(shipment.getId(), new BigDecimal("80000"), "Credit note CN-12");
        assertThat(shipment.getClaimStatus()).isEqualTo(ClaimStatus.SETTLED);
        assertThat(shipment.getClaimSettledAmount()).isEqualByComparingTo("80000");
        assertThatThrownBy(() -> service.rejectClaim(shipment.getId(), "late"))
                .satisfies(e -> assertThat(((BusinessException) e).getMessageKey()).isEqualTo("shipment.claim.notOpen"));
    }

    @Test
    void aClaimNeedsBrokenSheetsAPartyADateFromArrivalAndAnAmount() {
        crateA.setBroken(0);
        assertThatThrownBy(() -> service.openClaim(shipment.getId(), "Shandong", null, TODAY, BigDecimal.TEN))
                .satisfies(e -> assertThat(((BusinessException) e).getMessageKey()).isEqualTo("shipment.claim.noBroken"));
        crateA.setBroken(1);
        assertThatThrownBy(() -> service.openClaim(shipment.getId(), "  ", null, TODAY, BigDecimal.TEN))
                .satisfies(e -> assertThat(((BusinessException) e).getMessageKey()).isEqualTo("shipment.claim.party.required"));
        assertThatThrownBy(() -> service.openClaim(shipment.getId(), "Shandong", null, shipment.getArrivalDate().minusDays(1), BigDecimal.TEN))
                .satisfies(e -> assertThat(((BusinessException) e).getMessageKey()).isEqualTo("shipment.claim.date.invalid"));
        assertThatThrownBy(() -> service.openClaim(shipment.getId(), "Shandong", null, TODAY, new BigDecimal("10.001")))
                .satisfies(e -> assertThat(((BusinessException) e).getMessageKey()).isEqualTo("shipment.claim.amount.invalid"));
    }

    @Test
    void theCostSheetEstimatesDraftsAndValuesBrokenSheetsAtLandedCost() {
        bill(1, CostType.FREIGHT, "USD", "300.00");
        service.post(shipment.getId());
        ShipmentAllocation a = new ShipmentAllocation();
        a.setCrateBatchId(crateA.getId());
        a.setPostingNo(1);
        a.setAmount(new BigDecimal("332756.84"));
        a.setStockAmount(new BigDecimal("166378.42"));
        a.setExpensedAmount(new BigDecimal("83189.21"));
        a.setBrokenAmount(new BigDecimal("83189.21"));
        when(allocationRepo.findByShipmentIdOrderByPostingNoAsc(shipment.getId())).thenReturn(List.of(a));
        bill(2, CostType.CLEARING, "RWF", "150000");

        ShipmentService.CostSheet sheet = service.costSheet(shipment.getId());

        ShipmentService.Summary sum = sheet.summary();
        assertThat(sum.draftLines()).isEqualTo(1);
        assertThat(sum.pending()).isEqualByComparingTo("150000");
        assertThat(sum.area()).isEqualByComparingTo("37.8204");
        // Broken sheet: 7.2225 m² x 6,091.05 = 43,992.61 + its share 83,189.21
        assertThat(sum.brokenValue()).isEqualByComparingTo("127182");
        ShipmentService.CrateLine lineA = sheet.crates().get(0);
        assertThat(lineA.getExtraPerM2()).isEqualByComparingTo(LandedCost.perM2(new BigDecimal("332756.84"), new BigDecimal("28.89")));
        assertThat(lineA.pending().add(sheet.crates().get(1).pending())).isEqualByComparingTo("150000");
        assertThat(sheet.postings()).hasSize(1);
    }

    // ---------------------------------------------------------------- helpers

    /** The shipment's n-th cost line (0-based), in line order. */
    private ShipmentCost cost(int index) {
        return new ArrayList<>(shipment.getCosts()).get(index);
    }

    private void bill(int lineNo, CostType type, String currency, String amount) {
        ShipmentCost cost = new ShipmentCost();
        cost.setId(UUID.randomUUID());
        cost.setShipment(shipment);
        cost.setLineNo(lineNo);
        cost.setCostType(type);
        cost.setCurrencyCode(currency);
        cost.setAmount(new BigDecimal(amount));
        cost.setInvoiceDate(BILL_DATE);
        shipment.getCosts().add(cost);
    }

    private ShipmentDto dto() {
        ShipmentDto dto = new ShipmentDto();
        dto.setArrivalDate(shipment.getArrivalDate());
        dto.setAllocationMethod(AllocationMethod.AREA);
        dto.getReceiptIds().add(receipt.getId());
        return dto;
    }

    private static ShipmentDto.Cost cost(CostType type, String currency, String amount, LocalDate date) {
        ShipmentDto.Cost cost = new ShipmentDto.Cost();
        cost.setCostType(type);
        cost.setCurrencyCode(currency);
        cost.setAmount(new BigDecimal(amount));
        cost.setInvoiceDate(date);
        return cost;
    }

    private static Product product(String code, String thickness, String mac) {
        Product p = new Product();
        p.setId(UUID.randomUUID());
        p.setCode(code);
        p.setThicknessMm(new BigDecimal(thickness));
        p.setMacPerM2(new BigDecimal(mac));
        return p;
    }

    private static Currency currency(String code, int decimals, boolean base, boolean enabled) {
        Currency c = new Currency();
        c.setCode(code);
        c.setDecimals(decimals);
        c.setBaseCurrency(base);
        c.setEnabled(enabled);
        return c;
    }

    private static CrateBatch crate(GoodsReceipt receipt, Product product, String batchNo, int w, int h, int sheets,
                                    int broken, String costPerM2) {
        CrateBatch c = new CrateBatch();
        c.setId(UUID.randomUUID());
        c.setGoodsReceipt(receipt);
        c.setProduct(product);
        c.setBatchNo(batchNo);
        c.setWidthMm(w);
        c.setHeightMm(h);
        c.setSheets(sheets);
        c.setBroken(broken);
        c.setCostPerM2(new BigDecimal(costPerM2));
        receipt.getCrates().add(c);
        return c;
    }

    private static StockUnit unit(String code, CrateBatch crate, StockStatus status, String cost) {
        StockUnit u = new StockUnit();
        u.setId(UUID.randomUUID());
        u.setCode(code);
        u.setKind(UnitKind.SHEET);
        u.setCrateBatch(crate);
        u.setProduct(crate.getProduct());
        u.setAreaM2(crate.getSheetArea());
        u.setStatus(status);
        u.setUnitCost(new BigDecimal(cost));
        return u;
    }
}
