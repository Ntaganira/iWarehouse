package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.Location;
import com.ntaganira.heritier.iWarehouse.entity.Product;
import com.ntaganira.heritier.iWarehouse.entity.StockMovement;
import com.ntaganira.heritier.iWarehouse.entity.StockUnit;
import com.ntaganira.heritier.iWarehouse.enums.LocationType;
import com.ntaganira.heritier.iWarehouse.enums.MovementType;
import com.ntaganira.heritier.iWarehouse.enums.StockStatus;
import com.ntaganira.heritier.iWarehouse.repository.ProductRepository;
import com.ntaganira.heritier.iWarehouse.repository.StockMovementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The stock card (IMP 7.1, STC-003): the opening balance before the period, each movement in or out with the balance
 * after it, and the closing balance; for one site a unit counts while on one of its places, never while in transit.
 */
class StockCardServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-15T08:00:00Z"), ZoneId.of("Africa/Kigali"));
    private static final LocalDate FROM = LocalDate.of(2026, 10, 1);
    private static final LocalDate TO = LocalDate.of(2026, 10, 15);

    private final Location kigali = location("WH", LocationType.SITE, null);
    private final Location kigaliRack = location("WH-A-R01", LocationType.RACK, kigali.getId());
    private final Location musanze = location("MSZ", LocationType.SITE, null);
    private final Location musanzeRack = location("MSZ-A-R01", LocationType.RACK, musanze.getId());
    private final Product clear6 = new Product();
    private final List<Object[]> rows = new ArrayList<>();
    private StockCardService service;

    @BeforeEach
    void setUp() {
        clear6.setId(UUID.randomUUID());
        clear6.setCode("CLR-6");
        Map<UUID, Location> byId = new HashMap<>();
        for (Location l : List.of(kigali, kigaliRack, musanze, musanzeRack)) {
            byId.put(l.getId(), l);
        }
        StockMovementRepository movements = mock(StockMovementRepository.class);
        when(movements.ofProductUntil(eq(clear6.getId()), any())).thenAnswer(a -> rows.stream()
                .filter(r -> ((StockMovement) r[0]).getMovedAt().isBefore(a.getArgument(1))).toList());
        ProductRepository products = mock(ProductRepository.class);
        when(products.findById(clear6.getId())).thenReturn(Optional.of(clear6));
        StockService stock = mock(StockService.class);
        when(stock.locationsById()).thenReturn(byId);
        service = new StockCardService(movements, products, stock, CLOCK);

        StockUnit a = unit("U-WH-000001");
        StockUnit b = unit("U-WH-000002");
        // Before the period: two sheets received in Kigali
        move(a, "2026-09-20T09:00", MovementType.RECEIPT, null, null, kigaliRack, StockStatus.AVAILABLE);
        move(b, "2026-09-20T09:00", MovementType.RECEIPT, null, null, kigaliRack, StockStatus.AVAILABLE);
        // In it: one reserved (no change), one sent to Musanze and arrived, then sold there
        move(a, "2026-10-02T10:00", MovementType.RESERVE, kigaliRack, StockStatus.AVAILABLE, kigaliRack, StockStatus.RESERVED);
        move(b, "2026-10-05T08:00", MovementType.DISPATCH, kigaliRack, StockStatus.AVAILABLE, kigaliRack, StockStatus.IN_TRANSIT);
        move(b, "2026-10-05T15:00", MovementType.ARRIVAL, kigaliRack, StockStatus.IN_TRANSIT, musanzeRack, StockStatus.AVAILABLE);
        move(b, "2026-10-09T11:00", MovementType.SALE, musanzeRack, StockStatus.AVAILABLE, musanzeRack, StockStatus.SOLD);
    }

    @Test
    void forTheBusinessATransferBetweenSitesMovesNothing() {
        StockCardService.Card card = service.card(clear6.getId(), null, FROM, TO);
        assertThat(card.openingPieces()).isEqualTo(2);
        assertThat(card.openingM2()).isEqualByComparingTo("14.4450");
        assertThat(card.rows()).singleElement().satisfies(r -> {
            assertThat(r.type()).isEqualTo(MovementType.SALE);
            assertThat(r.pieces()).isEqualTo(-1);
            assertThat(r.balancePieces()).isEqualTo(1);
        });
        assertThat(card.outPieces()).isEqualTo(1);
        assertThat(card.closingPieces()).isEqualTo(1);
        assertThat(card.closingM2()).isEqualByComparingTo("7.2225");
    }

    @Test
    void forOneSiteTheSheetLeavesOnDispatchAndArrivesOnArrival() {
        StockCardService.Card kigaliCard = service.card(clear6.getId(), kigali.getId(), FROM, TO);
        assertThat(kigaliCard.openingPieces()).isEqualTo(2);
        assertThat(kigaliCard.rows()).extracting(StockCardService.Row::type).containsExactly(MovementType.DISPATCH);
        assertThat(kigaliCard.closingPieces()).isEqualTo(1);

        StockCardService.Card musanzeCard = service.card(clear6.getId(), musanze.getId(), FROM, TO);
        assertThat(musanzeCard.openingPieces()).isZero();
        assertThat(musanzeCard.rows()).extracting(StockCardService.Row::type).containsExactly(MovementType.ARRIVAL, MovementType.SALE);
        assertThat(musanzeCard.rows()).extracting(StockCardService.Row::balancePieces).containsExactly(1, 0);
        assertThat(musanzeCard.inPieces()).isEqualTo(1);
        assertThat(musanzeCard.outPieces()).isEqualTo(1);
        assertThat(musanzeCard.closingPieces()).isZero();
    }

    @Test
    void aPeriodWithNoMovementsOpensAndClosesOnTheSameBalance() {
        StockCardService.Card card = service.card(clear6.getId(), null, LocalDate.of(2026, 9, 25), LocalDate.of(2026, 9, 30));
        assertThat(card.rows()).isEmpty();
        assertThat(card.openingPieces()).isEqualTo(2);
        assertThat(card.closingPieces()).isEqualTo(2);
    }

    // ---------------------------------------------------------------- helpers

    private void move(StockUnit unit, String at, MovementType type, Location from, StockStatus fromStatus, Location to, StockStatus toStatus) {
        StockMovement m = new StockMovement();
        m.setId(UUID.randomUUID());
        m.setStockUnitId(unit.getId());
        m.setMovedAt(LocalDateTime.parse(at));
        m.setType(type);
        m.setFromLocationId(from == null ? null : from.getId());
        m.setFromStatus(fromStatus);
        m.setToLocationId(to.getId());
        m.setToStatus(toStatus);
        m.setUsername("store1");
        rows.add(new Object[]{m, unit});
    }

    private StockUnit unit(String code) {
        StockUnit u = new StockUnit();
        u.setId(UUID.randomUUID());
        u.setCode(code);
        u.setProduct(clear6);
        u.setWidthMm(3210);
        u.setHeightMm(2250);
        u.setAreaM2(Pricing.areaM2(3210, 2250));
        return u;
    }

    private static Location location(String code, LocationType type, UUID parentId) {
        Location l = new Location();
        l.setId(UUID.randomUUID());
        l.setCode(code);
        l.setName(code);
        l.setType(type);
        l.setParentId(parentId);
        return l;
    }
}
