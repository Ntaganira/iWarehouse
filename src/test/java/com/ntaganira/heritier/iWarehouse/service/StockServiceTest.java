package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.*;
import com.ntaganira.heritier.iWarehouse.enums.*;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.repository.SalesInvoiceLineRepository;
import com.ntaganira.heritier.iWarehouse.repository.LocationRepository;
import com.ntaganira.heritier.iWarehouse.repository.StockAdjustmentLineRepository;
import com.ntaganira.heritier.iWarehouse.repository.StockCostEntryRepository;
import com.ntaganira.heritier.iWarehouse.repository.StockCountRepository;
import com.ntaganira.heritier.iWarehouse.repository.StockMovementRepository;
import com.ntaganira.heritier.iWarehouse.repository.StockUnitRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Stock units (INV-01..04): creation on receipt with a movement and a cost entry each, landed cost added to
 * a unit (PRC-05), rack loads (MD-03), status filter.
 */
class StockServiceTest {

    private StockUnitRepository unitRepo;
    private StockMovementRepository movementRepo;
    private StockCostEntryRepository costEntryRepo;
    private LocationRepository locationRepo;
    private StockService service;

    private final Location zone = location("WH-A", LocationType.ZONE, null, false, true);
    private final Location rack = location("WH-A-R01", LocationType.RACK, zone.getId(), false, true);
    private final Location slot = location("WH-A-R01-S01", LocationType.SLOT, rack.getId(), false, true);
    private final Location offcutRack = location("WH-A-OC", LocationType.RACK, zone.getId(), true, true);
    private final Location offcutSlot = location("WH-A-OC-S01", LocationType.SLOT, offcutRack.getId(), false, true);
    private final Location oldRack = location("WH-A-R09", LocationType.RACK, zone.getId(), false, false);

    @BeforeEach
    void setUp() {
        unitRepo = mock(StockUnitRepository.class);
        movementRepo = mock(StockMovementRepository.class);
        costEntryRepo = mock(StockCostEntryRepository.class);
        locationRepo = mock(LocationRepository.class);
        when(locationRepo.findAll()).thenReturn(List.of(zone, rack, slot, offcutRack, offcutSlot, oldRack));
        DocumentNumberService numbers = mock(DocumentNumberService.class);
        AtomicInteger next = new AtomicInteger(1);
        when(numbers.next(DocumentType.STOCK_UNIT)).thenAnswer(a -> String.format("U-WH-%06d", next.getAndIncrement()));
        when(unitRepo.save(any())).thenAnswer(a -> {
            StockUnit u = a.getArgument(0);
            u.setId(UUID.randomUUID());
            return u;
        });
        Clock clock = Clock.fixed(Instant.parse("2026-10-08T08:00:00Z"), ZoneId.of("Africa/Kigali"));
        service = new StockService(unitRepo, movementRepo, costEntryRepo, mock(StockAdjustmentLineRepository.class), mock(StockCountRepository.class), mock(SalesInvoiceLineRepository.class), locationRepo, numbers, clock);
    }

    @Test
    void receivingACrateCreatesOneAvailableSheetPerGoodSheetWithAMovementEach() {
        Product clear6 = new Product();
        clear6.setId(UUID.randomUUID());
        CrateBatch crate = new CrateBatch();
        crate.setId(UUID.randomUUID());
        crate.setProduct(clear6);
        crate.setWidthMm(3210);
        crate.setHeightMm(2250);
        crate.setSheets(3);
        crate.setBroken(1);
        crate.setLocation(rack);
        GoodsReceipt receipt = new GoodsReceipt();
        receipt.setId(UUID.randomUUID());
        receipt.setNumber("GRN-WH-2026-000004");

        List<StockUnit> units = service.receive(crate, new BigDecimal("108.34"), new BigDecimal("45556.42"), receipt);

        assertThat(units).hasSize(3);
        assertThat(units).extracting(StockUnit::getCode).containsExactly("U-WH-000001", "U-WH-000002", "U-WH-000003");
        StockUnit u = units.get(0);
        assertThat(u.getStatus()).isEqualTo(StockStatus.AVAILABLE);
        assertThat(u.getKind()).isEqualTo(UnitKind.SHEET);
        assertThat(u.getLocation()).isSameAs(rack);
        assertThat(u.getCrateBatch()).isSameAs(crate);
        assertThat(u.getAreaM2()).isEqualByComparingTo("7.2225");
        assertThat(u.getWeightKg()).isEqualByComparingTo("108.34");
        assertThat(u.getUnitCost()).isEqualByComparingTo("45556.42");

        ArgumentCaptor<StockMovement> captor = ArgumentCaptor.forClass(StockMovement.class);
        verify(movementRepo, times(3)).save(captor.capture());
        StockMovement m = captor.getAllValues().get(0);
        assertThat(m.getStockUnitId()).isEqualTo(u.getId());
        assertThat(m.getType()).isEqualTo(MovementType.RECEIPT);
        assertThat(m.getFromLocationId()).isNull();
        assertThat(m.getToLocationId()).isEqualTo(rack.getId());
        assertThat(m.getFromStatus()).isNull();
        assertThat(m.getToStatus()).isEqualTo(StockStatus.AVAILABLE);
        assertThat(m.getRefType()).isEqualTo(StockService.REF_GOODS_RECEIPT);
        assertThat(m.getRefNumber()).isEqualTo("GRN-WH-2026-000004");
        assertThat(m.getMovedAt()).isEqualTo(LocalDateTime.of(2026, 10, 8, 10, 0));
        assertThat(m.getUsername()).isEqualTo("system");

        // Each unit starts its cost history with the receipt cost.
        ArgumentCaptor<StockCostEntry> costs = ArgumentCaptor.forClass(StockCostEntry.class);
        verify(costEntryRepo, times(3)).save(costs.capture());
        StockCostEntry entry = costs.getAllValues().get(0);
        assertThat(entry.getStockUnitId()).isEqualTo(u.getId());
        assertThat(entry.getType()).isEqualTo(CostEntryType.RECEIPT);
        assertThat(entry.getAmount()).isEqualByComparingTo("45556.42");
        assertThat(entry.getCostAfter()).isEqualByComparingTo("45556.42");
        assertThat(entry.getRefNumber()).isEqualTo("GRN-WH-2026-000004");
    }

    @Test
    void landedCostIsAddedToTheUnitAndRecorded() {
        StockUnit unit = new StockUnit();
        unit.setId(UUID.randomUUID());
        unit.setCode("U-WH-000007");
        unit.setUnitCost(new BigDecimal("43992.61"));
        UUID shipmentId = UUID.randomUUID();

        service.addLandedCost(unit, new BigDecimal("205210.00"), shipmentId, "SHP-WH-2026-000001");

        assertThat(unit.getUnitCost()).isEqualByComparingTo("249202.61");
        ArgumentCaptor<StockCostEntry> captor = ArgumentCaptor.forClass(StockCostEntry.class);
        verify(costEntryRepo).save(captor.capture());
        StockCostEntry entry = captor.getValue();
        assertThat(entry.getType()).isEqualTo(CostEntryType.LANDED_COST);
        assertThat(entry.getAmount()).isEqualByComparingTo("205210.00");
        assertThat(entry.getCostAfter()).isEqualByComparingTo("249202.61");
        assertThat(entry.getRefType()).isEqualTo(StockService.REF_SHIPMENT);
        assertThat(entry.getRefId()).isEqualTo(shipmentId);
    }

    @Test
    void aCreditCannotTakeAUnitCostBelowZero() {
        StockUnit unit = new StockUnit();
        unit.setCode("U-WH-000007");
        unit.setUnitCost(new BigDecimal("100.00"));
        assertThatThrownBy(() -> service.addLandedCost(unit, new BigDecimal("-100.01"), UUID.randomUUID(), "SHP-WH-2026-000002"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getArgs()).containsExactly("U-WH-000007"));
        assertThat(unit.getUnitCost()).isEqualByComparingTo("100.00");
        verifyNoInteractions(costEntryRepo);
        // Down to exactly zero is allowed.
        service.addLandedCost(unit, new BigDecimal("-100.00"), UUID.randomUUID(), "SHP-WH-2026-000002");
        assertThat(unit.getUnitCost()).isEqualByComparingTo("0");
    }

    @Test
    void unitsInASlotCountTowardsItsRack() {
        when(unitRepo.loadByLocation(any())).thenReturn(List.of(
                new Object[]{rack.getId(), 4L, new BigDecimal("400")},
                new Object[]{slot.getId(), 2L, new BigDecimal("216.68")},
                new Object[]{zone.getId(), 7L, new BigDecimal("700")}));
        Map<UUID, RackLoad> loads = service.rackLoads(service.locationsById());
        assertThat(loads).containsOnlyKeys(rack.getId());
        assertThat(loads.get(rack.getId()).pieces()).isEqualTo(6);
        assertThat(loads.get(rack.getId()).kg()).isEqualByComparingTo("616.68");
    }

    @Test
    void cratesGoOnActiveRacksAndSlotsButNotOnOffcutRacks() {
        assertThat(service.receivingLocations(service.locationsById()))
                .extracting(Location::getCode).containsExactly("WH-A-R01", "WH-A-R01-S01");
    }

    @Test
    void statusFilterValues() {
        assertThat(StockService.parseStatus("available")).isEqualTo(StockStatus.AVAILABLE);
        assertThat(StockService.parseStatus("")).isNull();
        assertThat(StockService.parseStatus("all")).isNull();
        assertThat(StockStatus.onHand()).containsExactly(StockStatus.RECEIVED, StockStatus.AVAILABLE, StockStatus.RESERVED,
                StockStatus.IN_CUTTING, StockStatus.ON_VEHICLE);
    }

    private static Location location(String code, LocationType type, UUID parentId, boolean offcut, boolean enabled) {
        Location l = new Location();
        l.setId(UUID.randomUUID());
        l.setCode(code);
        l.setType(type);
        l.setParentId(parentId);
        l.setOffcut(offcut);
        l.setEnabled(enabled);
        return l;
    }
}
