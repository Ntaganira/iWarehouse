package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.StockUnit;
import com.ntaganira.heritier.iWarehouse.entity.Trip;
import com.ntaganira.heritier.iWarehouse.entity.TripCount;
import com.ntaganira.heritier.iWarehouse.entity.TripCountLine;
import com.ntaganira.heritier.iWarehouse.enums.NotificationKind;
import com.ntaganira.heritier.iWarehouse.enums.StockStatus;
import com.ntaganira.heritier.iWarehouse.enums.TripCountKind;
import com.ntaganira.heritier.iWarehouse.enums.TripCountOutcome;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.StockUnitRepository;
import com.ntaganira.heritier.iWarehouse.repository.TripCountLineRepository;
import com.ntaganira.heritier.iWarehouse.repository.TripCountRepository;
import com.ntaganira.heritier.iWarehouse.repository.TripRepository;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : TripCountService.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Counting the glass on a vehicle on the road (SDP 6, BP 7.3 rule 10, F-08; N.12, L.02): the driver's
 *               daily count from the phone (DAILY, sent twice, taken once) or a supervisor's unannounced one (SPOT, never
 *               the trip's driver): the labels scanned against the units on board (ON_VEHICLE at the vehicle, VanCounts),
 *               kept with each unit found, missing or not expected. Units missing are told to ALERT_EOD holders.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class TripCountService {

    private final TripCountRepository repo;
    private final TripCountLineRepository lineRepo;
    private final TripRepository tripRepo;
    private final StockUnitRepository unitRepo;
    private final Notifier notifier;
    private final Clock clock;

    public TripCountService(TripCountRepository repo, TripCountLineRepository lineRepo, TripRepository tripRepo, StockUnitRepository unitRepo,
                            Notifier notifier, Clock clock) {
        this.repo = repo;
        this.lineRepo = lineRepo;
        this.tripRepo = tripRepo;
        this.unitRepo = unitRepo;
        this.notifier = notifier;
        this.clock = clock;
    }

    public record Counted(TripCount count, VanCounts.Result result) {
    }

    public List<TripCount> ofTrip(UUID tripId) {
        return repo.findByTripIdOrderByCountedAtDesc(tripId);
    }

    public List<TripCountLine> lines(UUID countId) {
        return lineRepo.findByCountIdOrderByOutcomeAscUnitCodeAsc(countId);
    }

    public Optional<TripCount> byClientId(UUID clientId) {
        return clientId == null ? Optional.empty() : repo.findByClientId(clientId);
    }

    /** The units on a vehicle now: what a count expects. */
    public List<StockUnit> onBoard(Trip trip) {
        return unitRepo.findByLocation_IdAndStatusOrderByCodeAsc(trip.getVehicle().getLocation().getId(), StockStatus.ON_VEHICLE);
    }

    /** Counts a trip on the road from the labels scanned. The same clientId sent again gets the count already taken. */
    @Transactional
    public Counted count(UUID tripId, TripCountKind kind, Collection<String> codes, String note, UUID clientId) {
        Optional<TripCount> already = byClientId(clientId);
        if (already.isPresent()) {
            TripCount c = already.get();
            List<TripCountLine> lines = lines(c.getId());
            return new Counted(c, new VanCounts.Result(codesOf(lines, TripCountOutcome.FOUND), codesOf(lines, TripCountOutcome.MISSING),
                    codesOf(lines, TripCountOutcome.EXTRA)));
        }
        tripRepo.lockById(tripId).orElseThrow(() -> new NotFoundException("Trip", tripId));
        Trip trip = tripRepo.findDetailedById(tripId).orElseThrow(() -> new NotFoundException("Trip", tripId));
        if (!trip.isDeparted()) {
            throw BusinessException.of("tripCount.notOnRoad", trip.getNumber());
        }
        AppUserPrincipal user = AppUserPrincipal.current().orElseThrow(() -> BusinessException.of("till.noUser"));
        boolean driver = trip.getDriver().getUser().getId().equals(user.getId());
        if (kind == TripCountKind.SPOT && driver) {
            throw BusinessException.of("tripCount.spot.driver");
        }
        if (kind == TripCountKind.DAILY && !driver) {
            throw BusinessException.of("tripCount.daily.notDriver", trip.getNumber());
        }
        if (codes == null || codes.stream().allMatch(c -> c == null || c.isBlank())) {
            throw BusinessException.onField("codes", "tripCount.nothing");
        }
        String clean = PartyRules.clean(note);
        if (clean != null && clean.length() > 255) {
            throw BusinessException.onField("note", "tripCount.noteSize");
        }
        Map<String, StockUnit> board = onBoard(trip).stream().collect(Collectors.toMap(StockUnit::getCode, Function.identity()));
        VanCounts.Result result = VanCounts.compare(board.keySet(), codes);
        Map<String, StockUnit> extras = result.extra().isEmpty() ? Map.of()
                : unitRepo.findByCodeIn(result.extra()).stream().collect(Collectors.toMap(StockUnit::getCode, Function.identity()));
        TripCount c = new TripCount();
        c.setTripId(tripId);
        c.setClientId(clientId);
        c.setKind(kind);
        c.setCountedAt(LocalDateTime.now(clock));
        c.setExpected(board.size());
        c.setFound(result.found().size());
        c.setMissing(result.missing().size());
        c.setExtra(result.extra().size());
        c.setNote(clean);
        c.setUserId(user.getId());
        c.setUsername(user.getUsername());
        repo.save(c);
        result.found().forEach(code -> line(c, board.get(code), code, TripCountOutcome.FOUND));
        result.missing().forEach(code -> line(c, board.get(code), code, TripCountOutcome.MISSING));
        result.extra().forEach(code -> line(c, extras.get(code), code, TripCountOutcome.EXTRA));
        if (!result.missing().isEmpty()) {
            notifier.holders("ALERT_EOD", user.getId(), NotificationKind.EOD, "notify.tripCount.missing", "notify.tripCount.missingText",
                    "/trips/" + tripId + "?tab=counts", trip.getNumber(), String.valueOf(result.missing().size()),
                    String.join(", ", result.missing().subList(0, Math.min(10, result.missing().size()))));
        }
        return new Counted(c, result);
    }

    private void line(TripCount c, StockUnit unit, String code, TripCountOutcome outcome) {
        TripCountLine l = new TripCountLine();
        l.setCountId(c.getId());
        l.setStockUnitId(unit == null ? null : unit.getId());
        l.setUnitCode(code);
        l.setOutcome(outcome);
        lineRepo.save(l);
    }

    private static List<String> codesOf(List<TripCountLine> lines, TripCountOutcome outcome) {
        return lines.stream().filter(l -> l.getOutcome() == outcome).map(TripCountLine::getUnitCode).toList();
    }
}
