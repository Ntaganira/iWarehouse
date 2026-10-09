package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.dto.StockCountDto;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * INV-08: stock counts by scanning. Starting holds the units of the place and every place under it; scans
 * record where labels were found; closing records missing, misplaced and extra units, moves the misplaced
 * ones, puts missing and lost-found units on one adjustment (approved as any other) and frees the units.
 */
class StockCountServiceTest {

    private final Location site = location("WH", LocationType.SITE, null, false);
    private final Location zone = location("WH-A", LocationType.ZONE, site.getId(), false);
    private final Location rack1 = location("WH-A-R01", LocationType.RACK, zone.getId(), false);
    private final Location rack2 = location("WH-A-R02", LocationType.RACK, zone.getId(), false);
    private final Location offcutRack = location("WH-A-OC", LocationType.RACK, zone.getId(), true);
    private final Location zoneB = location("WH-B", LocationType.ZONE, site.getId(), false);
    private final Location rackB1 = location("WH-B-R01", LocationType.RACK, zoneB.getId(), false);
    private final Product clear6 = product("CLR-6");
    private final Product mirror4 = product("MIR-4");

    private final List<StockUnit> units = new ArrayList<>();
    private final List<StockCount> counts = new ArrayList<>();
    private final List<Object[]> adjustmentHolds = new ArrayList<>();
    private final List<StockCountLine> savedLines = new ArrayList<>();
    private final Map<UUID, StockAdjustment> adjustments = new HashMap<>();
    private StockMovementRepository movementRepo;
    private StockCountService service;
    private StockService stockService;
    private int countNo = 1;

    @BeforeEach
    void setUp() {
        StockUnitRepository unitRepo = mock(StockUnitRepository.class);
        movementRepo = mock(StockMovementRepository.class);
        StockAdjustmentLineRepository adjustmentLineRepo = mock(StockAdjustmentLineRepository.class);
        StockCountRepository countRepo = mock(StockCountRepository.class);
        StockCountLineRepository lineRepo = mock(StockCountLineRepository.class);
        LocationRepository locationRepo = mock(LocationRepository.class);
        ProductRepository productRepo = mock(ProductRepository.class);
        StockAdjustmentRepository adjustmentRepo = mock(StockAdjustmentRepository.class);
        when(locationRepo.findAll()).thenReturn(List.of(site, zone, rack1, rack2, offcutRack, zoneB, rackB1));

        when(unitRepo.findByCodeIn(any())).thenAnswer(a -> {
            Collection<String> codes = a.getArgument(0);
            return units.stream().filter(u -> codes.contains(u.getCode())).toList();
        });
        when(unitRepo.findByIdIn(any())).thenAnswer(a -> {
            Collection<UUID> ids = a.getArgument(0);
            return units.stream().filter(u -> ids.contains(u.getId())).toList();
        });
        when(unitRepo.findByLocation_IdInAndStatusIn(any(), any())).thenAnswer(a -> {
            Collection<UUID> places = a.getArgument(0);
            Collection<StockStatus> statuses = a.getArgument(1);
            return units.stream().filter(u -> u.getLocation() != null && places.contains(u.getLocation().getId())
                    && statuses.contains(u.getStatus())).toList();
        });
        when(unitRepo.loadByLocation(any())).thenReturn(List.of());
        when(adjustmentLineRepo.findHolds(any(), any())).thenAnswer(a -> adjustmentHolds);
        // An open count holds the units on its places (of its glass)
        when(countRepo.findHolds(any())).thenAnswer(a -> {
            Collection<UUID> ids = a.getArgument(0);
            List<Object[]> rows = new ArrayList<>();
            for (StockCount c : counts) {
                if (!c.isOpen()) {
                    continue;
                }
                for (StockUnit u : units) {
                    if (ids.contains(u.getId()) && u.getLocation() != null && c.getPlaces().contains(u.getLocation().getId())
                            && (c.getProduct() == null || c.getProduct().equals(u.getProduct()))) {
                        rows.add(new Object[]{u.getId(), c.getNumber()});
                    }
                }
            }
            return rows;
        });
        when(countRepo.findOpenCovering(any())).thenAnswer(a -> {
            Collection<UUID> places = a.getArgument(0);
            return counts.stream().filter(c -> c.isOpen() && c.getPlaces().stream().anyMatch(places::contains))
                    .map(StockCount::getNumber).toList();
        });
        when(countRepo.save(any())).thenAnswer(a -> {
            StockCount c = a.getArgument(0);
            if (c.getId() == null) {
                c.setId(UUID.randomUUID());
                counts.add(c);
            }
            return c;
        });
        when(countRepo.lockById(any())).thenAnswer(a -> counts.stream().filter(c -> c.getId().equals(a.getArgument(0))).findFirst());
        when(lineRepo.save(any())).thenAnswer(a -> {
            savedLines.add(a.getArgument(0));
            return a.getArgument(0);
        });
        when(productRepo.findById(clear6.getId())).thenReturn(Optional.of(clear6));
        when(adjustmentRepo.save(any())).thenAnswer(a -> {
            StockAdjustment adj = a.getArgument(0);
            if (adj.getId() == null) {
                adj.setId(UUID.randomUUID());
            }
            adjustments.put(adj.getId(), adj);
            return adj;
        });

        DocumentNumberService numbers = mock(DocumentNumberService.class);
        when(numbers.next(DocumentType.STOCK_COUNT)).thenAnswer(a -> String.format("CNT-WH-2026-%06d", countNo++));
        when(numbers.next(DocumentType.ADJUSTMENT)).thenReturn("ADJ-WH-2026-000007");
        SettingService settings = mock(SettingService.class);
        when(settings.getDecimal(SettingKey.ADJUSTMENT_APPROVAL_LIMIT)).thenReturn(BigDecimal.ZERO);
        when(settings.getDecimal(SettingKey.GLASS_DENSITY)).thenReturn(new BigDecimal("2.5"));

        Clock clock = Clock.fixed(Instant.parse("2026-10-08T08:00:00Z"), ZoneId.of("Africa/Kigali"));
        stockService = new StockService(unitRepo, movementRepo, mock(StockCostEntryRepository.class), adjustmentLineRepo, countRepo,
                locationRepo, numbers, clock);
        StockAdjustmentService adjustmentService = new StockAdjustmentService(adjustmentRepo, unitRepo, productRepo, stockService,
                mock(PostingService.class), numbers, settings, clock);
        service = new StockCountService(countRepo, lineRepo, unitRepo, productRepo, stockService, adjustmentService, numbers, clock);
        signIn(7L, "supervisor1");
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- starting

    @Test
    void aZoneCountCoversTheZoneAndEveryRackUnderIt() {
        StockCount count = service.start(form(zone, null));

        assertThat(count.getNumber()).isEqualTo("CNT-WH-2026-000001");
        assertThat(count.getStatus()).isEqualTo(StockCountStatus.OPEN);
        assertThat(count.getPlaces()).containsExactlyInAnyOrder(zone.getId(), rack1.getId(), rack2.getId(), offcutRack.getId());
        assertThat(count.getStartedBy()).isEqualTo("supervisor1");
    }

    @Test
    void twoOpenCountsNeverCoverTheSamePlace() {
        service.start(form(rack1, null));

        assertThatThrownBy(() -> service.start(form(zone, null)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("messageKey", "count.overlap")
                .hasFieldOrPropertyWithValue("field", "locationId");
        // Another zone is fine
        assertThat(service.start(form(zoneB, null)).getPlaces()).contains(rackB1.getId());
    }

    @Test
    void whileACountIsOpenItsUnitsAreHeld() {
        StockUnit sheet = unit("U-1", StockStatus.AVAILABLE, rack1, clear6);
        StockUnit elsewhere = unit("U-2", StockStatus.AVAILABLE, rackB1, clear6);
        StockCount count = service.start(form(zone, null));

        assertThatThrownBy(() -> stockService.requireNotHeld(List.of(sheet), null))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "stock.held");
        assertThat(stockService.holds(List.of(sheet.getId(), elsewhere.getId()))).containsOnlyKeys(sheet.getId())
                .containsValue(count.getNumber());

        service.cancel(count.getId(), "Wrong zone");

        assertThat(count.getStatus()).isEqualTo(StockCountStatus.CANCELLED);
        assertThat(count.getCancelReason()).isEqualTo("Wrong zone");
        assertThat(count.getClosedAt()).isNotNull();
        assertThat(stockService.holds(List.of(sheet.getId()))).isEmpty();
    }

    @Test
    void aCountOfOneGlassHoldsOnlyThatGlass() {
        StockUnit clear = unit("U-1", StockStatus.AVAILABLE, rack1, clear6);
        StockUnit mirror = unit("U-2", StockStatus.AVAILABLE, rack1, mirror4);
        service.start(form(rack1, clear6));

        assertThat(stockService.holds(List.of(clear.getId(), mirror.getId()))).containsOnlyKeys(clear.getId());
    }

    // ---------------------------------------------------------------- scanning

    @Test
    void labelsAreScannedOnARackOfTheCount() {
        unit("U-1", StockStatus.AVAILABLE, rack1, clear6);
        StockCount count = service.start(form(zone, null));

        StockCountService.ScanResult r = service.scan(count.getId(), rack1.getId(), " u-1 ");

        assertThat(r.outcome()).isEqualTo(CountOutcome.MATCHED);
        assertThat(count.getScans()).singleElement().satisfies(s -> {
            assertThat(s.getCode()).isEqualTo("U-1");
            assertThat(s.getLocationId()).isEqualTo(rack1.getId());
            assertThat(s.getScannedBy()).isEqualTo("supervisor1");
        });
        assertThatThrownBy(() -> service.scan(count.getId(), rackB1.getId(), "U-1"))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "count.where.required");
        assertThatThrownBy(() -> service.scan(count.getId(), zone.getId(), "U-1"))   // a zone is not where glass stands
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "count.where.required");
        assertThatThrownBy(() -> service.scan(count.getId(), rack1.getId(), "  "))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "count.codes.required");
        // two labels run together (a paste into a one-line field) are refused, not stored
        assertThatThrownBy(() -> service.scan(count.getId(), rack1.getId(), "U-WH-000001U-WH-000002U-WH-000003"))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "count.code.tooLong");
        assertThat(count.getScans()).hasSize(1);
    }

    @Test
    void aLabelScannedAgainOnAnotherRackMovesItsScan() {
        StockUnit u = unit("U-1", StockStatus.AVAILABLE, rack1, clear6);
        StockCount count = service.start(form(zone, null));
        service.scan(count.getId(), rack1.getId(), "U-1");

        StockCountService.ScanResult r = service.scan(count.getId(), rack2.getId(), "U-1");

        assertThat(r.again()).isTrue();
        assertThat(r.outcome()).isEqualTo(CountOutcome.MISPLACED);
        assertThat(count.getScans()).singleElement().extracting(StockCountScan::getLocationId).isEqualTo(rack2.getId());
        assertThat(u.getLocation()).isEqualTo(rack1);   // nothing moves before the count closes
    }

    @Test
    void aCountOfOneGlassRefusesOtherGlass() {
        unit("U-2", StockStatus.AVAILABLE, rack1, mirror4);
        StockCount count = service.start(form(rack1, clear6));

        assertThatThrownBy(() -> service.scan(count.getId(), rack1.getId(), "U-2"))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "count.otherGlass");
        assertThat(count.getScans()).isEmpty();
    }

    @Test
    void severalCodesPastedAreScannedTogetherAndAScanCanBeRemoved() {
        unit("U-1", StockStatus.AVAILABLE, rack1, clear6);
        unit("U-2", StockStatus.AVAILABLE, rack1, clear6);
        StockCount count = service.start(form(rack1, null));

        StockCountService.ScanResult r = service.scan(count.getId(), rack1.getId(), "U-1\nU-2, X-9");

        assertThat(r.scanned()).isEqualTo(3);
        assertThat(count.getScans()).hasSize(3);
        count.getScans().forEach(sc -> sc.setId(UUID.randomUUID()));   // ids come from the database
        StockCountScan unknown = count.getScans().stream().filter(s -> s.getCode().equals("X-9")).findFirst().orElseThrow();
        assertThat(unknown.getStockUnitId()).isNull();
        service.removeScan(count.getId(), unknown.getId());
        assertThat(count.getScans()).extracting(StockCountScan::getCode).containsExactlyInAnyOrder("U-1", "U-2");
    }

    @Test
    void aRackLabelSaysWhereTheLabelsAfterItWereFound() {
        unit("U-1", StockStatus.AVAILABLE, rack1, clear6);
        unit("U-3", StockStatus.AVAILABLE, rack2, clear6);
        StockCount count = service.start(form(zone, null));

        StockCountService.ScanResult r = service.scan(count.getId(), null, "WH-A-R02\nU-3\nU-1");

        assertThat(r.place()).isEqualTo(rack2);
        assertThat(r.scanned()).isEqualTo(2);
        assertThat(count.getScans()).allSatisfy(s -> assertThat(s.getLocationId()).isEqualTo(rack2.getId()));

        StockCountService.ScanResult moved = service.scan(count.getId(), rack2.getId(), "wh-a-r01"); // a label alone: count there now
        assertThat(moved.scanned()).isZero();
        assertThat(moved.place()).isEqualTo(rack1);
        assertThat(count.getScans()).hasSize(2);

        assertThatThrownBy(() -> service.scan(count.getId(), rack1.getId(), "WH-B-R01 U-1"))   // not a place of this count
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "count.placeOutside");
        assertThatThrownBy(() -> service.scan(count.getId(), rack1.getId(), "WH-A"))           // a zone is not where glass stands
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "count.placeOutside");
        assertThatThrownBy(() -> service.scan(count.getId(), null, "U-1"))                     // no place chosen or scanned
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "count.where.required");
    }

    // ---------------------------------------------------------------- closing

    @Test
    void closingRecordsMissingMisplacedAndExtraAndActsOnThem() {
        StockUnit inPlace = unit("U-1", StockStatus.AVAILABLE, rack1, clear6);
        StockUnit missing = unit("U-2", StockStatus.RESERVED, rack1, clear6);
        StockUnit misplaced = unit("U-3", StockStatus.AVAILABLE, rack1, clear6);
        StockUnit lost = unit("U-4", StockStatus.LOST, null, clear6);
        StockUnit sold = unit("U-5", StockStatus.SOLD, null, clear6);
        StockUnit fromOtherZone = unit("U-6", StockStatus.AVAILABLE, rackB1, clear6);
        StockCount count = service.start(form(zone, null));
        service.scan(count.getId(), rack1.getId(), "U-1");
        service.scan(count.getId(), rack2.getId(), "U-3");
        service.scan(count.getId(), rack2.getId(), "U-4");
        service.scan(count.getId(), rack1.getId(), "U-5");
        service.scan(count.getId(), rack1.getId(), "X-99");
        service.scan(count.getId(), rack2.getId(), "U-6");

        StockCountService.CloseResult r = service.close(count.getId(), "Stock count CNT-WH-2026-000001");

        assertThat(count.getStatus()).isEqualTo(StockCountStatus.CLOSED);
        assertThat(count.getClosedBy()).isEqualTo("supervisor1");
        // expected on the zone: U-1, U-2, U-3 (U-6 stands in another zone); 6 labels scanned
        assertThat(count.getExpectedUnits()).isEqualTo(3);
        assertThat(count.getCountedUnits()).isEqualTo(6);
        assertThat(count.getMatchedUnits()).isEqualTo(1);
        assertThat(count.getMissingUnits()).isEqualTo(1);
        assertThat(count.getMisplacedUnits()).isEqualTo(2);
        assertThat(count.getExtraUnits()).isEqualTo(3);
        // misplaced units follow, with a COUNT movement
        assertThat(misplaced.getLocation()).isEqualTo(rack2);
        assertThat(fromOtherZone.getLocation()).isEqualTo(rack2);
        ArgumentCaptor<StockMovement> moves = ArgumentCaptor.forClass(StockMovement.class);
        verify(movementRepo, times(2)).save(moves.capture());
        assertThat(moves.getAllValues()).allSatisfy(m -> {
            assertThat(m.getType()).isEqualTo(MovementType.COUNT);
            assertThat(m.getRefNumber()).isEqualTo(count.getNumber());
        });
        // missing and lost-found units go on one adjustment, waiting for approval (limit 0)
        StockAdjustment adj = adjustments.get(count.getAdjustmentId());
        assertThat(count.getAdjustmentNumber()).isEqualTo("ADJ-WH-2026-000007");
        assertThat(adj.getStatus()).isEqualTo(AdjustmentStatus.PENDING_APPROVAL);
        assertThat(adj.getReason()).isEqualTo("Stock count CNT-WH-2026-000001");
        assertThat(adj.getLines()).extracting(StockAdjustmentLine::getUnitCode, StockAdjustmentLine::getKind)
                .containsExactlyInAnyOrder(tuple("U-2", AdjustmentKind.WRITE_OFF), tuple("U-4", AdjustmentKind.FOUND));
        assertThat(adj.getLines()).filteredOn(l -> l.getKind() == AdjustmentKind.WRITE_OFF)
                .extracting(StockAdjustmentLine::getCause).containsExactly(WriteOffCause.MISSING);
        assertThat(adj.getLines()).filteredOn(l -> l.getKind() == AdjustmentKind.FOUND)
                .extracting(l -> l.getLocation().getId()).containsExactly(rack2.getId());
        assertThat(missing.getStatus()).isEqualTo(StockStatus.RESERVED);   // written off only once approved
        assertThat(inPlace.getLocation()).isEqualTo(rack1);
        // one line per unit, with what was done
        assertThat(savedLines).extracting(StockCountLine::getCode, StockCountLine::getOutcome, StockCountLine::getAction)
                .containsExactly(
                        tuple("U-1", CountOutcome.MATCHED, CountAction.NONE),
                        tuple("U-3", CountOutcome.MISPLACED, CountAction.MOVED),
                        tuple("U-6", CountOutcome.MISPLACED, CountAction.MOVED),
                        tuple("U-2", CountOutcome.MISSING, CountAction.ADJUSTMENT),
                        tuple("U-4", CountOutcome.FOUND_LOST, CountAction.ADJUSTMENT),
                        tuple("U-5", CountOutcome.NOT_IN_STOCK, CountAction.NONE),
                        tuple("X-99", CountOutcome.UNKNOWN, CountAction.NONE));
        assertThat(savedLines).extracting(StockCountLine::getLineNo).containsExactly(1, 2, 3, 4, 5, 6, 7);
        assertThat(savedLines.get(5).getUnitStatus()).isEqualTo(StockStatus.SOLD);
        assertThat(r.racksOverLimit()).isEmpty();
        // closed, the count holds nothing more (the adjustment now holds its own units)
        assertThat(stockService.holds(List.of(inPlace.getId()))).isEmpty();
        assertThat(sold.getStatus()).isEqualTo(StockStatus.SOLD);
        assertThat(lost.getStatus()).isEqualTo(StockStatus.LOST);   // found only once the adjustment is approved
    }

    @Test
    void unitsHeldByAnotherDocumentAreOnlyReported() {
        StockUnit missing = unit("U-2", StockStatus.AVAILABLE, rack1, clear6);
        unit("U-1", StockStatus.AVAILABLE, rack1, clear6);
        StockCount count = service.start(form(rack1, null));
        service.scan(count.getId(), rack1.getId(), "U-1");
        adjustmentHolds.add(new Object[]{missing.getId(), "ADJ-WH-2026-000003"});

        service.close(count.getId(), "Stock count");

        assertThat(count.getAdjustmentId()).isNull();
        assertThat(savedLines).filteredOn(l -> l.getCode().equals("U-2")).singleElement().satisfies(l -> {
            assertThat(l.getOutcome()).isEqualTo(CountOutcome.MISSING);
            assertThat(l.getAction()).isEqualTo(CountAction.NONE);
            assertThat(l.getNote()).isEqualTo("ADJ-WH-2026-000003");
        });
    }

    @Test
    void aLostSheetFoundOnAnOffcutRackIsOnlyReported() {
        StockUnit lostSheet = unit("U-4", StockStatus.LOST, null, clear6);
        lostSheet.setKind(UnitKind.SHEET);
        StockCount count = service.start(form(zone, null));
        service.scan(count.getId(), offcutRack.getId(), "U-4");

        service.close(count.getId(), "Stock count");

        assertThat(count.getAdjustmentId()).isNull();
        assertThat(savedLines).singleElement().satisfies(l -> {
            assertThat(l.getOutcome()).isEqualTo(CountOutcome.FOUND_LOST);
            assertThat(l.getNote()).isEqualTo("OFFCUT_RACK");
        });
        assertThat(lostSheet.getStatus()).isEqualTo(StockStatus.LOST);
    }

    @Test
    void aCountWithoutScansIsNotClosedAndAClosedCountNeverChanges() {
        StockCount count = service.start(form(rack1, null));
        assertThatThrownBy(() -> service.close(count.getId(), "Stock count"))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "count.close.noScans");

        unit("U-1", StockStatus.AVAILABLE, rack1, clear6);
        service.scan(count.getId(), rack1.getId(), "U-1");
        service.close(count.getId(), "Stock count");

        assertThatThrownBy(() -> service.scan(count.getId(), rack1.getId(), "U-1"))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "count.notOpen");
        assertThatThrownBy(() -> service.cancel(count.getId(), "x"))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "count.notOpen");
    }

    // ---------------------------------------------------------------- helpers

    private static org.assertj.core.groups.Tuple tuple(Object... values) {
        return org.assertj.core.groups.Tuple.tuple(values);
    }

    private static StockCountDto form(Location place, Product product) {
        StockCountDto dto = new StockCountDto();
        dto.setLocationId(place.getId());
        dto.setProductId(product == null ? null : product.getId());
        return dto;
    }

    private StockUnit unit(String code, StockStatus status, Location location, Product product) {
        StockUnit u = new StockUnit();
        u.setId(UUID.randomUUID());
        u.setCode(code);
        u.setProduct(product);
        u.setKind(UnitKind.CUT_PIECE);
        u.setWidthMm(2000);
        u.setHeightMm(1000);
        u.setAreaM2(Pricing.areaM2(2000, 1000));
        u.setWeightKg(new BigDecimal("30.00"));
        u.setUnitCost(new BigDecimal("68956.07"));
        u.setStatus(status);
        u.setLocation(location);
        units.add(u);
        return u;
    }

    private static Product product(String code) {
        Product p = new Product();
        p.setId(UUID.randomUUID());
        p.setCode(code);
        p.setGlassType(GlassType.CLEAR);
        p.setThicknessMm(new BigDecimal("6.00"));
        p.setMacPerM2(new BigDecimal("34478.0353"));
        p.setEnabled(true);
        return p;
    }

    private static Location location(String code, LocationType type, UUID parentId, boolean offcut) {
        Location l = new Location();
        l.setId(UUID.randomUUID());
        l.setCode(code);
        l.setType(type);
        l.setParentId(parentId);
        l.setOffcut(offcut);
        l.setEnabled(true);
        return l;
    }

    private static void signIn(long id, String username) {
        AppUserPrincipal principal = new AppUserPrincipal(id, username, username, "x", true, true, List.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
