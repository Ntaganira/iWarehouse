package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.dto.StockAdjustmentDto;
import com.ntaganira.heritier.iWarehouse.dto.StockTransferDto;
import com.ntaganira.heritier.iWarehouse.entity.*;
import com.ntaganira.heritier.iWarehouse.enums.*;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.repository.*;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Inventory operations (INV-05, INV-07): reservations, transfers within rack limits, adjustments with the
 * approval limit and a second person, write-offs (broken, lost), units found again, new units at MAC, resizes
 * at the same cost per m², and units held by a pending adjustment.
 */
class StockOperationsTest {

    private final Location zone = location("WH-A", LocationType.ZONE, null, false, null);
    private final Location rack1 = location("WH-A-R01", LocationType.RACK, zone.getId(), false, 30);
    private final Location rack2 = location("WH-A-R02", LocationType.RACK, zone.getId(), false, 3);
    private final Location offcutRack = location("WH-A-OC", LocationType.RACK, zone.getId(), true, 100);
    private final Product clear6 = product("CLR-6", "34478.0353");
    private final Product mirror4 = product("MIR-4", null);
    private final Customer umucyo = customer("Umucyo Builders Ltd");

    private final List<StockUnit> units = new ArrayList<>();
    private final List<Object[]> holds = new ArrayList<>();
    private final Map<UUID, StockAdjustment> adjustments = new HashMap<>();
    private StockMovementRepository movementRepo;
    private StockCostEntryRepository costEntryRepo;
    private BigDecimal limit = new BigDecimal("1000000");
    private StockService stockService;
    private StockTransferService transferService;
    private StockAdjustmentService adjustmentService;
    private StockUnit sheet;
    private StockUnit reserved;
    private StockUnit inCutting;
    private StockUnit offcut;

    @BeforeEach
    void setUp() {
        StockUnitRepository unitRepo = mock(StockUnitRepository.class);
        movementRepo = mock(StockMovementRepository.class);
        costEntryRepo = mock(StockCostEntryRepository.class);
        StockAdjustmentLineRepository lineRepo = mock(StockAdjustmentLineRepository.class);
        LocationRepository locationRepo = mock(LocationRepository.class);
        ProductRepository productRepo = mock(ProductRepository.class);
        StockAdjustmentRepository adjustmentRepo = mock(StockAdjustmentRepository.class);
        StockTransferRepository transferRepo = mock(StockTransferRepository.class);
        when(locationRepo.findAll()).thenReturn(List.of(zone, rack1, rack2, offcutRack));

        sheet = unit("U-WH-000036", UnitKind.SHEET, 3210, 2250, "108.34", "249017.61", StockStatus.AVAILABLE, rack1);
        reserved = unit("U-WH-000056", UnitKind.CUT_PIECE, 2000, 1000, "30.00", "68956.07", StockStatus.RESERVED, rack1);
        reserved.setReservedCustomer(umucyo);
        reserved.setReservedNote("CUT-WH-2026-000001");
        inCutting = unit("U-WH-000037", UnitKind.SHEET, 3210, 2250, "108.34", "249017.61", StockStatus.IN_CUTTING, rack1);
        offcut = unit("U-WH-000059", UnitKind.OFFCUT, 1800, 1000, "27.00", "62060.47", StockStatus.AVAILABLE, offcutRack);

        when(unitRepo.findByCodeIn(any())).thenAnswer(a -> {
            Collection<String> codes = a.getArgument(0);
            return units.stream().filter(u -> codes.contains(u.getCode())).toList();
        });
        when(unitRepo.findAllById(any())).thenAnswer(a -> {
            Collection<UUID> ids = a.getArgument(0);
            return units.stream().filter(u -> ids.contains(u.getId())).toList();
        });
        when(unitRepo.findByIdIn(any())).thenAnswer(a -> {
            Collection<UUID> ids = a.getArgument(0);
            return units.stream().filter(u -> ids.contains(u.getId())).toList();
        });
        when(unitRepo.save(any())).thenAnswer(a -> {
            StockUnit u = a.getArgument(0);
            u.setId(UUID.randomUUID());
            units.add(u);
            return u;
        });
        // Rack loads and m² held follow the units as they change.
        when(unitRepo.loadByLocation(any())).thenAnswer(a -> {
            Map<UUID, Object[]> rows = new LinkedHashMap<>();
            for (StockUnit u : units) {
                if (u.getStatus().isOnHand() && u.getLocation() != null) {
                    Object[] row = rows.computeIfAbsent(u.getLocation().getId(), k -> new Object[]{k, 0L, BigDecimal.ZERO});
                    row[1] = (Long) row[1] + 1;
                    row[2] = ((BigDecimal) row[2]).add(u.getWeightKg());
                }
            }
            return new ArrayList<>(rows.values());
        });
        when(unitRepo.sumArea(any(), any())).thenAnswer(a -> units.stream()
                .filter(u -> u.getProduct().getId().equals(a.getArgument(0)) && u.getStatus().isOnHand())
                .map(StockUnit::getAreaM2).reduce(BigDecimal.ZERO, BigDecimal::add));
        when(lineRepo.findHolds(any(), any())).thenAnswer(a -> holds);

        when(productRepo.findById(clear6.getId())).thenReturn(Optional.of(clear6));
        when(productRepo.findById(mirror4.getId())).thenReturn(Optional.of(mirror4));
        when(productRepo.lockAllById(any())).thenAnswer(a -> {
            Collection<UUID> ids = a.getArgument(0);
            return List.of(clear6, mirror4).stream().filter(p -> ids.contains(p.getId())).toList();
        });
        when(adjustmentRepo.save(any())).thenAnswer(a -> {
            StockAdjustment adj = a.getArgument(0);
            if (adj.getId() == null) {
                adj.setId(UUID.randomUUID());
            }
            adjustments.put(adj.getId(), adj);
            return adj;
        });
        when(adjustmentRepo.lockById(any())).thenAnswer(a -> Optional.ofNullable(adjustments.get(a.<UUID>getArgument(0))));
        when(adjustmentRepo.findDetailedById(any())).thenAnswer(a -> Optional.ofNullable(adjustments.get(a.<UUID>getArgument(0))));
        when(transferRepo.save(any())).thenAnswer(a -> {
            StockTransfer t = a.getArgument(0);
            t.setId(UUID.randomUUID());
            return t;
        });

        DocumentNumberService numbers = mock(DocumentNumberService.class);
        AtomicInteger unitNo = new AtomicInteger(61);
        when(numbers.next(DocumentType.STOCK_UNIT)).thenAnswer(a -> String.format("U-WH-%06d", unitNo.getAndIncrement()));
        when(numbers.next(DocumentType.TRANSFER)).thenReturn("TRF-WH-2026-000001");
        when(numbers.next(DocumentType.ADJUSTMENT)).thenReturn("ADJ-WH-2026-000001");
        SettingService settings = mock(SettingService.class);
        when(settings.getDecimal(SettingKey.ADJUSTMENT_APPROVAL_LIMIT)).thenAnswer(a -> limit);
        when(settings.getDecimal(SettingKey.GLASS_DENSITY)).thenReturn(new BigDecimal("2.5"));

        Clock clock = Clock.fixed(Instant.parse("2026-10-08T08:00:00Z"), ZoneId.of("Africa/Kigali"));
        stockService = new StockService(unitRepo, movementRepo, costEntryRepo, lineRepo, mock(StockCountRepository.class), mock(SalesInvoiceLineRepository.class), locationRepo, numbers, clock);
        transferService = new StockTransferService(transferRepo, unitRepo, stockService, numbers, clock);
        adjustmentService = new StockAdjustmentService(adjustmentRepo, unitRepo, productRepo, stockService, mock(PostingService.class), numbers,
                settings, clock);
        signIn(7L, "supervisor1");
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- reservations (INV-05)

    @Test
    void aReservedUnitNamesItsCustomerUntilReleased() {
        stockService.reserve(sheet, umucyo, "Quote Q-17");

        assertThat(sheet.getStatus()).isEqualTo(StockStatus.RESERVED);
        assertThat(sheet.getReservedCustomer()).isEqualTo(umucyo);
        assertThat(lastMovement().getType()).isEqualTo(MovementType.RESERVE);
        assertThat(lastMovement().getRefNumber()).isEqualTo(umucyo.getCode());
        assertThatThrownBy(() -> stockService.reserve(sheet, umucyo, null))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "stock.notAllowed.RESERVE");

        stockService.release(sheet, "Customer cancelled");

        assertThat(sheet.getStatus()).isEqualTo(StockStatus.AVAILABLE);
        assertThat(sheet.getReservedCustomer()).isNull();
        assertThat(sheet.getReservedNote()).isNull();
        assertThat(lastMovement().getType()).isEqualTo(MovementType.RELEASE);
        assertThat(lastMovement().getReason()).isEqualTo("Customer cancelled");
    }

    @Test
    void aUnitHeldByAPendingAdjustmentCannotBeCutOrReserved() {
        holds.add(new Object[]{sheet.getId(), "ADJ-WH-2026-000009"});

        assertThatThrownBy(() -> stockService.startCutting(sheet, UUID.randomUUID(), "CUT-1"))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "stock.held");
        assertThatThrownBy(() -> stockService.reserve(sheet, umucyo, null))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "stock.held");
        assertThat(sheet.getStatus()).isEqualTo(StockStatus.AVAILABLE);
    }

    // ---------------------------------------------------------------- transfers (INV-07)

    @Test
    void aTransferMovesTheUnitsAndRecordsWhereEachCameFrom() {
        StockTransfer transfer = transferService.create(transferForm(rack2, "u-wh-000036\nU-WH-000056, U-WH-000036"));

        assertThat(transfer.getNumber()).isEqualTo("TRF-WH-2026-000001");
        assertThat(transfer.getLines()).extracting(StockTransferLine::getUnitCode).containsExactly("U-WH-000036", "U-WH-000056");
        assertThat(transfer.getLines()).allSatisfy(l -> assertThat(l.getFromLocationId()).isEqualTo(rack1.getId()));
        assertThat(sheet.getLocation()).isEqualTo(rack2);
        assertThat(reserved.getLocation()).isEqualTo(rack2);
        assertThat(reserved.getStatus()).isEqualTo(StockStatus.RESERVED);   // a move does not end a reservation
        ArgumentCaptor<StockMovement> moves = ArgumentCaptor.forClass(StockMovement.class);
        verify(movementRepo, times(2)).save(moves.capture());
        assertThat(moves.getAllValues()).allSatisfy(m -> {
            assertThat(m.getType()).isEqualTo(MovementType.TRANSFER);
            assertThat(m.getFromLocationId()).isEqualTo(rack1.getId());
            assertThat(m.getToLocationId()).isEqualTo(rack2.getId());
        });
    }

    @Test
    void unknownCodesAreListed() {
        assertThatThrownBy(() -> transferService.create(transferForm(rack2, "U-WH-000036 U-WH-999998 U-WH-999999")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    assertThat(((BusinessException) e).getMessageKey()).isEqualTo("transfer.codes.unknown");
                    assertThat(((BusinessException) e).getArgs()).containsExactly("U-WH-999998, U-WH-999999");
                });
        assertThat(sheet.getLocation()).isEqualTo(rack1);
    }

    @Test
    void unitsBeingCutCannotBeMoved() {
        assertThatThrownBy(() -> transferService.create(transferForm(rack2, "U-WH-000037")))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "transfer.codes.notMovable");
    }

    @Test
    void fullSheetsNeverGoToAnOffcutRack() {
        assertThatThrownBy(() -> transferService.create(transferForm(offcutRack, "U-WH-000036")))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "transfer.sheetToOffcut");
        offcut.setLocation(rack1);
        transferService.create(transferForm(offcutRack, "U-WH-000059"));
        assertThat(offcut.getLocation()).isEqualTo(offcutRack);
    }

    @Test
    void theDestinationRackMustTakeTheUnits() {
        unit("U-WH-000070", UnitKind.SHEET, 1000, 1000, "15.00", "1000.00", StockStatus.AVAILABLE, rack2);
        unit("U-WH-000071", UnitKind.SHEET, 1000, 1000, "15.00", "1000.00", StockStatus.AVAILABLE, rack2);

        // WH-A-R02 takes 3 pieces and holds 2
        assertThatThrownBy(() -> transferService.create(transferForm(rack2, "U-WH-000036 U-WH-000056")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    assertThat(((BusinessException) e).getMessageKey()).isEqualTo("receipt.rack.pieces");
                    assertThat(((BusinessException) e).getField()).isEqualTo("toLocationId");
                });
        transferService.create(transferForm(rack2, "U-WH-000036"));
        assertThat(sheet.getLocation()).isEqualTo(rack2);
    }

    @Test
    void heldUnitsAndUnitsAlreadyThereAreRefused() {
        assertThatThrownBy(() -> transferService.create(transferForm(rack1, "U-WH-000036")))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "transfer.codes.alreadyThere");
        holds.add(new Object[]{sheet.getId(), "ADJ-WH-2026-000009"});
        assertThatThrownBy(() -> transferService.create(transferForm(rack2, "U-WH-000036")))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "transfer.codes.held");
    }

    @Test
    void aScannedRackLabelSaysWhereTheUnitsGo() {
        StockTransfer transfer = transferService.create(transferForm(null, "U-WH-000036\nwh-a-r02"));

        assertThat(transfer.getToLocation()).isEqualTo(rack2);
        assertThat(transfer.getLines()).extracting(StockTransferLine::getUnitCode).containsExactly("U-WH-000036");
        assertThat(sheet.getLocation()).isEqualTo(rack2);

        // the same place chosen in the list is fine; another one, or two labels, is a mistake to fix
        transferService.create(transferForm(rack1, "WH-A-R01 U-WH-000036"));
        assertThat(sheet.getLocation()).isEqualTo(rack1);
        assertThatThrownBy(() -> transferService.create(transferForm(offcutRack, "U-WH-000036 WH-A-R02")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    assertThat(((BusinessException) e).getMessageKey()).isEqualTo("transfer.to.scannedOther");
                    assertThat(((BusinessException) e).getArgs()).containsExactly("WH-A-R02", "WH-A-OC");
                });
        assertThatThrownBy(() -> transferService.create(transferForm(null, "WH-A-R02 U-WH-000036 WH-A-OC")))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "transfer.codes.twoPlaces");
        assertThatThrownBy(() -> transferService.create(transferForm(null, "U-WH-000036")))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "transfer.to.required");
        assertThatThrownBy(() -> transferService.create(transferForm(null, "WH-A-R02")))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "transfer.codes.required");
        assertThat(sheet.getLocation()).isEqualTo(rack1);
    }

    @Test
    void codesAreReadFromAnyScannedOrTypedList() {
        assertThat(StockTransferService.parseCodes(" u-wh-000001\r\nU-WH-000002,U-WH-000001;  U-WH-000003 "))
                .containsExactly("U-WH-000001", "U-WH-000002", "U-WH-000003");
        assertThat(StockTransferService.parseCodes("  ")).isEmpty();
    }

    // ---------------------------------------------------------------- adjustments (INV-07)

    @Test
    void aWriteOffWithinTheLimitPostsAtOnce() {
        StockAdjustment adj = adjustmentService.create(adjustment("Cracked while moving", writeOff("U-WH-000036", WriteOffCause.DAMAGED)));

        assertThat(adj.getStatus()).isEqualTo(AdjustmentStatus.POSTED);
        assertThat(adj.getPostedAt()).isNotNull();
        assertThat(adj.getRequestedBy()).isEqualTo("supervisor1");
        assertThat(adj.getValueChange()).isEqualByComparingTo("-249017.61");
        assertThat(adj.getValueMoved()).isEqualByComparingTo("249017.61");
        assertThat(sheet.getStatus()).isEqualTo(StockStatus.BROKEN);
        assertThat(sheet.getLocation()).isNull();
        assertThat(lastMovement().getType()).isEqualTo(MovementType.ADJUSTMENT);
        assertThat(lastMovement().getReason()).isEqualTo("Cracked while moving");
        // Written off at the MAC: the MAC stays
        assertThat(clear6.getMacPerM2()).isEqualByComparingTo("34478.0353");
    }

    @Test
    void aboveTheLimitASecondPersonApproves() {
        limit = BigDecimal.ZERO;
        StockAdjustment adj = adjustmentService.create(adjustment("Not on the rack at the count", writeOff("U-WH-000036", WriteOffCause.MISSING)));

        assertThat(adj.getStatus()).isEqualTo(AdjustmentStatus.PENDING_APPROVAL);
        assertThat(sheet.getStatus()).isEqualTo(StockStatus.AVAILABLE);   // nothing changes before approval
        assertThatThrownBy(() -> adjustmentService.approve(adj.getId(), null))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "adjustment.ownApproval");

        signIn(1L, "owner");
        adjustmentService.approve(adj.getId(), "Checked on site");

        assertThat(adj.getStatus()).isEqualTo(AdjustmentStatus.POSTED);
        assertThat(adj.getDecidedBy()).isEqualTo("owner");
        assertThat(adj.getDecisionNote()).isEqualTo("Checked on site");
        assertThat(sheet.getStatus()).isEqualTo(StockStatus.LOST);
        assertThatThrownBy(() -> adjustmentService.approve(adj.getId(), null))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "adjustment.notPending");
    }

    @Test
    void onlyOthersRejectAndOnlyTheRequesterWithdraws() {
        limit = BigDecimal.ZERO;
        StockAdjustment adj = adjustmentService.create(adjustment("Broken", writeOff("U-WH-000036", WriteOffCause.DAMAGED)));
        assertThatThrownBy(() -> adjustmentService.reject(adj.getId(), "No"))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "adjustment.ownReject");

        signIn(1L, "owner");
        assertThatThrownBy(() -> adjustmentService.cancel(adj.getId(), "Mine now"))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "adjustment.notYours");
        adjustmentService.reject(adj.getId(), "The sheet is fine, it was on R02");

        assertThat(adj.getStatus()).isEqualTo(AdjustmentStatus.REJECTED);
        assertThat(adj.getDecisionNote()).isEqualTo("The sheet is fine, it was on R02");
        assertThat(sheet.getStatus()).isEqualTo(StockStatus.AVAILABLE);
    }

    @Test
    void aLostUnitCanBeFoundAgainAtItsOldCost() {
        adjustmentService.create(adjustment("Missing at count", writeOff("U-WH-000036", WriteOffCause.MISSING)));
        assertThat(sheet.getStatus()).isEqualTo(StockStatus.LOST);

        StockAdjustmentDto.Line found = line(AdjustmentKind.FOUND, "U-WH-000036");
        found.setLocationId(rack2.getId());
        StockAdjustment adj = adjustmentService.create(adjustment("Found behind rack 2", found));

        assertThat(adj.getValueChange()).isEqualByComparingTo("249017.61");
        assertThat(sheet.getStatus()).isEqualTo(StockStatus.AVAILABLE);
        assertThat(sheet.getLocation()).isEqualTo(rack2);
        assertThat(sheet.getUnitCost()).isEqualByComparingTo("249017.61");
    }

    @Test
    void aPieceNobodyRecordedIsAddedAtTheMac() {
        StockAdjustmentDto.Line line = line(AdjustmentKind.NEW_UNIT, null);
        line.setProductId(clear6.getId());
        line.setUnitKind(UnitKind.OFFCUT);
        line.setWidthMm(1000);
        line.setHeightMm(500);
        line.setLocationId(offcutRack.getId());

        StockAdjustment adj = adjustmentService.create(adjustment("Off-cut found unlabelled", line));

        // 0.5 m² x 34,478.0353
        assertThat(adj.getValueChange()).isEqualByComparingTo("17239.02");
        StockUnit created = units.stream().filter(u -> u.getId().equals(adj.getLines().get(0).getResultUnitId())).findFirst().orElseThrow();
        assertThat(created.getCode()).isEqualTo("U-WH-000061");
        assertThat(created.getKind()).isEqualTo(UnitKind.OFFCUT);
        assertThat(created.getStatus()).isEqualTo(StockStatus.AVAILABLE);
        assertThat(created.getLocation()).isEqualTo(offcutRack);
        assertThat(created.getWeightKg()).isEqualByComparingTo("7.50");
        ArgumentCaptor<StockCostEntry> cost = ArgumentCaptor.forClass(StockCostEntry.class);
        verify(costEntryRepo).save(cost.capture());
        assertThat(cost.getValue().getType()).isEqualTo(CostEntryType.ADJUSTMENT);
        // Valued at MAC rounded to the franc cent: the MAC moves by the rounding only
        assertThat(clear6.getMacPerM2()).isCloseTo(new BigDecimal("34478.0353"), org.assertj.core.data.Offset.offset(new BigDecimal("0.0002")));
    }

    @Test
    void aNewUnitNeedsAnAverageCost() {
        StockAdjustmentDto.Line line = line(AdjustmentKind.NEW_UNIT, null);
        line.setProductId(mirror4.getId());
        line.setUnitKind(UnitKind.OFFCUT);
        line.setWidthMm(500);
        line.setHeightMm(500);
        line.setLocationId(offcutRack.getId());

        assertThatThrownBy(() -> adjustmentService.create(adjustment("Found", line)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    assertThat(((BusinessException) e).getMessageKey()).isEqualTo("adjustment.noMac");
                    assertThat(((BusinessException) e).getField()).isEqualTo("lines[0].productId");
                });
    }

    @Test
    void aWrongSizeIsReplacedAtTheSameCostPerM2() {
        StockAdjustmentDto.Line line = line(AdjustmentKind.RESIZE, "U-WH-000056");   // reserved for Umucyo
        line.setWidthMm(1990);
        line.setHeightMm(1000);

        StockAdjustment adj = adjustmentService.create(adjustment("Measured 1990, not 2000", line));

        // 68,956.07 x 1.99 / 2.0 = 68,611.29
        assertThat(adj.getValueChange()).isEqualByComparingTo("-344.78");
        assertThat(reserved.getStatus()).isEqualTo(StockStatus.CONSUMED);
        assertThat(reserved.getReservedCustomer()).isNull();
        StockUnit replacement = units.stream().filter(u -> u.getId().equals(adj.getLines().get(0).getResultUnitId())).findFirst().orElseThrow();
        assertThat(replacement.getWidthMm()).isEqualTo(1990);
        assertThat(replacement.getUnitCost()).isEqualByComparingTo("68611.29");
        assertThat(replacement.getParentUnitId()).isEqualTo(reserved.getId());
        assertThat(replacement.getLocation()).isEqualTo(rack1);
        assertThat(replacement.getStatus()).isEqualTo(StockStatus.RESERVED);   // the reservation follows
        assertThat(replacement.getReservedCustomer()).isEqualTo(umucyo);
    }

    @Test
    void aUnitIsOnOneAdjustmentAtATime() {
        holds.add(new Object[]{sheet.getId(), "ADJ-WH-2026-000009"});

        assertThatThrownBy(() -> adjustmentService.create(adjustment("Broken", writeOff("U-WH-000036", WriteOffCause.DAMAGED))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    assertThat(((BusinessException) e).getMessageKey()).isEqualTo("stock.held");
                    assertThat(((BusinessException) e).getField()).isEqualTo("lines[0].unitCode");
                });
        assertThatThrownBy(() -> adjustmentService.create(adjustment("Twice",
                writeOff("U-WH-000059", WriteOffCause.DAMAGED), writeOff("U-WH-000059", WriteOffCause.MISSING))))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("field", "lines[1].unitCode");
    }

    @Test
    void writingOffADearSheetLowersTheMac() {
        // Held: the sheet (7.2225 m², 249,017.61), the reserved piece, the sheet being cut and the off-cut, at MAC
        BigDecimal held = units.stream().filter(u -> u.getStatus().isOnHand()).map(StockUnit::getAreaM2).reduce(BigDecimal.ZERO, BigDecimal::add);
        sheet.setUnitCost(new BigDecimal("300000.00"));

        adjustmentService.create(adjustment("Broken", writeOff("U-WH-000036", WriteOffCause.DAMAGED)));

        BigDecimal expected = Costing.afterStockChange(held, new BigDecimal("34478.0353"), new BigDecimal("-7.2225"),
                new BigDecimal("-300000.00"));
        assertThat(clear6.getMacPerM2()).isEqualByComparingTo(expected);
        assertThat(clear6.getMacPerM2()).isLessThan(new BigDecimal("34478.0353"));
    }

    // ---------------------------------------------------------------- helpers

    private StockMovement lastMovement() {
        ArgumentCaptor<StockMovement> captor = ArgumentCaptor.forClass(StockMovement.class);
        verify(movementRepo, atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }

    private static void signIn(long id, String username) {
        AppUserPrincipal principal = new AppUserPrincipal(id, username, username, "x", true, true, List.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private static StockTransferDto transferForm(Location to, String codes) {
        StockTransferDto dto = new StockTransferDto();
        dto.setToLocationId(to == null ? null : to.getId());
        dto.setCodes(codes);
        return dto;
    }

    private static StockAdjustmentDto adjustment(String reason, StockAdjustmentDto.Line... lines) {
        StockAdjustmentDto dto = new StockAdjustmentDto();
        dto.setReason(reason);
        dto.getLines().addAll(List.of(lines));
        return dto;
    }

    private static StockAdjustmentDto.Line writeOff(String code, WriteOffCause cause) {
        StockAdjustmentDto.Line line = line(AdjustmentKind.WRITE_OFF, code);
        line.setCause(cause);
        return line;
    }

    private static StockAdjustmentDto.Line line(AdjustmentKind kind, String code) {
        StockAdjustmentDto.Line line = new StockAdjustmentDto.Line();
        line.setKind(kind);
        line.setUnitCode(code);
        return line;
    }

    private StockUnit unit(String code, UnitKind kind, int w, int h, String kg, String cost, StockStatus status, Location location) {
        StockUnit u = new StockUnit();
        u.setId(UUID.randomUUID());
        u.setCode(code);
        u.setProduct(clear6);
        u.setKind(kind);
        u.setWidthMm(w);
        u.setHeightMm(h);
        u.setAreaM2(Pricing.areaM2(w, h));
        u.setWeightKg(new BigDecimal(kg));
        u.setUnitCost(new BigDecimal(cost));
        u.setStatus(status);
        u.setLocation(location);
        units.add(u);
        return u;
    }

    private static Product product(String code, String mac) {
        Product p = new Product();
        p.setId(UUID.randomUUID());
        p.setCode(code);
        p.setGlassType(GlassType.CLEAR);
        p.setThicknessMm(new BigDecimal("6.00"));
        p.setMacPerM2(mac == null ? null : new BigDecimal(mac));
        p.setEnabled(true);
        return p;
    }

    private static Customer customer(String name) {
        Customer c = new Customer();
        c.setId(UUID.randomUUID());
        c.setCode("CUS-WH-00001");
        c.setName(name);
        c.setEnabled(true);
        return c;
    }

    private static Location location(String code, LocationType type, UUID parentId, boolean offcut, Integer maxPieces) {
        Location l = new Location();
        l.setId(UUID.randomUUID());
        l.setCode(code);
        l.setType(type);
        l.setParentId(parentId);
        l.setOffcut(offcut);
        l.setEnabled(true);
        l.setMaxPieces(maxPieces);
        l.setMaxWeightKg(maxPieces == null ? null : 3000);
        return l;
    }
}
