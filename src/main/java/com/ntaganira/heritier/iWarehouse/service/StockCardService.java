package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.Location;
import com.ntaganira.heritier.iWarehouse.entity.Product;
import com.ntaganira.heritier.iWarehouse.entity.StockMovement;
import com.ntaganira.heritier.iWarehouse.entity.StockUnit;
import com.ntaganira.heritier.iWarehouse.enums.MovementType;
import com.ntaganira.heritier.iWarehouse.enums.StockStatus;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.ProductRepository;
import com.ntaganira.heritier.iWarehouse.repository.StockMovementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : StockCardService.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : The stock card of a glass (IMP 7.1, STC-003), for the whole business or one site, over a period: the
 *               opening balance, every movement in or out with its date, document and officer, the running balance, in
 *               sheets and m², and the closing balance. Built from the movements: a unit counts while it is on hand (and,
 *               for one site, on one of its places, never while in transit between sites).
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class StockCardService {

    private final StockMovementRepository movementRepo;
    private final ProductRepository productRepo;
    private final StockService stockService;
    private final Clock clock;

    public StockCardService(StockMovementRepository movementRepo, ProductRepository productRepo, StockService stockService, Clock clock) {
        this.movementRepo = movementRepo;
        this.productRepo = productRepo;
        this.stockService = stockService;
        this.clock = clock;
    }

    /** One line of the card: a movement in (+) or out (-) and the balance after it. */
    public record Row(LocalDateTime at, MovementType type, String unitCode, String refNumber, String refType, UUID refId, String user,
                      int pieces, BigDecimal areaM2, int balancePieces, BigDecimal balanceM2) {

        public boolean isIn() {
            return pieces > 0;
        }
    }

    public record Card(Product product, Location site, LocalDate from, LocalDate to, int openingPieces, BigDecimal openingM2,
                       List<Row> rows, int inPieces, BigDecimal inM2, int outPieces, BigDecimal outM2, int closingPieces,
                       BigDecimal closingM2) {
    }

    public Card card(UUID productId, UUID siteId, LocalDate from, LocalDate to) {
        Product product = productRepo.findById(productId).orElseThrow(() -> new NotFoundException("Product", productId));
        Map<UUID, Location> byId = stockService.locationsById();
        Location site = siteId == null ? null : byId.get(siteId);
        Set<UUID> scope = null;
        if (site != null) {
            scope = new HashSet<>();
            for (Location l : byId.values()) {
                if (site.getId().equals(SiteTransferService.siteOf(l, byId))) {
                    scope.add(l.getId());
                }
            }
        }
        LocalDateTime start = from.atStartOfDay();
        int pieces = 0;
        BigDecimal area = BigDecimal.ZERO;
        int openingPieces = 0;
        BigDecimal openingM2 = BigDecimal.ZERO;
        boolean opened = false;
        int inPieces = 0, outPieces = 0;
        BigDecimal inM2 = BigDecimal.ZERO, outM2 = BigDecimal.ZERO;
        List<Row> rows = new ArrayList<>();
        for (Object[] r : movementRepo.ofProductUntil(productId, to.plusDays(1).atStartOfDay())) {
            StockMovement m = (StockMovement) r[0];
            StockUnit u = (StockUnit) r[1];
            if (!opened && !m.getMovedAt().isBefore(start)) {
                openingPieces = pieces;
                openingM2 = area;
                opened = true;
            }
            int delta = (counts(m.getToStatus(), m.getToLocationId(), scope) ? 1 : 0) - (counts(m.getFromStatus(), m.getFromLocationId(), scope) ? 1 : 0);
            if (delta == 0) {
                continue;
            }
            BigDecimal moved = u.getAreaM2().multiply(BigDecimal.valueOf(delta));
            pieces += delta;
            area = area.add(moved);
            if (opened) {
                rows.add(new Row(m.getMovedAt(), m.getType(), u.getCode(), m.getRefNumber(), m.getRefType(), m.getRefId(), m.getUsername(), delta,
                        moved, pieces, area));
                if (delta > 0) {
                    inPieces += delta;
                    inM2 = inM2.add(moved);
                } else {
                    outPieces -= delta;
                    outM2 = outM2.subtract(moved);
                }
            }
        }
        if (!opened) {
            openingPieces = pieces;
            openingM2 = area;
        }
        return new Card(product, site, from, to, openingPieces, openingM2, rows, inPieces, inM2, outPieces, outM2, pieces, area);
    }

    /** A unit counts on the card while on hand (on one of the site's places for one site; in transit for none). */
    private static boolean counts(StockStatus status, UUID locationId, Set<UUID> scope) {
        if (status == null || !status.isOnHand()) {
            return false;
        }
        if (scope == null) {
            return true;
        }
        return status != StockStatus.IN_TRANSIT && locationId != null && scope.contains(locationId);
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }
}
