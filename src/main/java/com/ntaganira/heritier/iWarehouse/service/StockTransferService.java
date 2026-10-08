package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.dto.StockTransferDto;
import com.ntaganira.heritier.iWarehouse.entity.Location;
import com.ntaganira.heritier.iWarehouse.entity.StockTransfer;
import com.ntaganira.heritier.iWarehouse.entity.StockTransferLine;
import com.ntaganira.heritier.iWarehouse.entity.StockUnit;
import com.ntaganira.heritier.iWarehouse.enums.DocumentType;
import com.ntaganira.heritier.iWarehouse.enums.StockAction;
import com.ntaganira.heritier.iWarehouse.enums.UnitKind;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.StockTransferRepository;
import com.ntaganira.heritier.iWarehouse.repository.StockUnitRepository;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : StockTransferService.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Transfers between racks and slots (INV-07). The units, scanned or typed, must be movable
 *               (INV-05: in stock, not being cut or on a vehicle, not held by a pending adjustment) and
 *               the destination must take them within its rack limits (MD-03); full sheets never go to an
 *               off-cut rack. Posted when saved, one TRANSFER movement per unit.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class StockTransferService {

    /** Most units one transfer takes. */
    static final int MAX_UNITS = 200;

    private final StockTransferRepository repo;
    private final StockUnitRepository unitRepo;
    private final StockService stockService;
    private final DocumentNumberService numbers;
    private final Clock clock;

    public StockTransferService(StockTransferRepository repo, StockUnitRepository unitRepo, StockService stockService,
                                DocumentNumberService numbers, Clock clock) {
        this.repo = repo;
        this.unitRepo = unitRepo;
        this.stockService = stockService;
        this.numbers = numbers;
        this.clock = clock;
    }

    /** A place units can be moved to, with what its rack holds now. */
    public record Place(Location location, Location rack, RackLoad load) {
    }

    public Page<StockTransfer> findPage(String search, int page, int size) {
        Specification<StockTransfer> spec = (root, query, cb) -> {
            Predicate p = cb.conjunction();
            if (StringUtils.hasText(search)) {
                String term = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                var sub = query.subquery(UUID.class);
                var line = sub.from(StockTransferLine.class);
                sub.select(line.get("transfer").get("id")).where(cb.like(cb.lower(line.get("unitCode")), term));
                p = cb.and(p, cb.or(
                        cb.like(cb.lower(root.get("number")), term),
                        cb.like(cb.lower(cb.coalesce(root.get("note"), "")), term),
                        root.get("id").in(sub)));
            }
            return p;
        };
        return repo.findAll(spec, PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "postedAt").and(Sort.by(Sort.Direction.DESC, "number"))));
    }

    /** Units per transfer of a page, in one query. */
    public Map<UUID, Long> unitCounts(Collection<StockTransfer> transfers) {
        if (transfers.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : repo.countLines(transfers.stream().map(StockTransfer::getId).toList())) {
            counts.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        return counts;
    }

    public StockTransfer findDetailed(UUID id) {
        return repo.findDetailedById(id).orElseThrow(() -> new NotFoundException("StockTransfer", id));
    }

    /** The units of a transfer by id, with product and location. */
    public Map<UUID, StockUnit> unitsOf(StockTransfer transfer) {
        return unitRepo.findByIdIn(transfer.getLines().stream().map(StockTransferLine::getStockUnitId).toList()).stream()
                .collect(Collectors.toMap(StockUnit::getId, Function.identity()));
    }

    /** Racks and slots units can go to, with their loads. */
    public List<Place> places() {
        Map<UUID, Location> byId = stockService.locationsById();
        Map<UUID, RackLoad> loads = stockService.rackLoads(byId);
        return stockService.storagePlaces(byId).stream()
                .map(l -> {
                    Location rack = StockService.rackOf(l, byId);
                    return new Place(l, rack, loads.getOrDefault(rack.getId(), RackLoad.EMPTY));
                })
                .toList();
    }

    /** Label codes in a scanned or typed list: upper case, without repeats, in order. */
    static List<String> parseCodes(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        return Arrays.stream(text.split("[\\s,;]+"))
                .map(String::trim).filter(StringUtils::hasText)
                .map(c -> c.toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    /** Checks and posts a transfer (INV-07). */
    @Transactional
    public StockTransfer create(StockTransferDto dto) {
        Map<UUID, Location> byId = stockService.locationsById();
        Location to = stockService.storagePlaces(byId).stream().filter(l -> l.getId().equals(dto.getToLocationId()))
                .findFirst()
                .orElseThrow(() -> BusinessException.onField("toLocationId", "transfer.to.invalid"));

        List<String> codes = parseCodes(dto.getCodes());
        if (codes.isEmpty()) {
            throw BusinessException.onField("codes", "transfer.codes.required");
        }
        if (codes.size() > MAX_UNITS) {
            throw BusinessException.onField("codes", "transfer.codes.tooMany", MAX_UNITS, codes.size());
        }
        Map<String, StockUnit> found = unitRepo.findByCodeIn(codes).stream()
                .collect(Collectors.toMap(StockUnit::getCode, Function.identity()));
        List<String> unknown = codes.stream().filter(c -> !found.containsKey(c)).toList();
        if (!unknown.isEmpty()) {
            throw BusinessException.onField("codes", "transfer.codes.unknown", String.join(", ", unknown));
        }
        List<StockUnit> units = codes.stream().map(found::get).toList();
        refuse(units, u -> !StockAction.TRANSFER.allows(u.getStatus()), "codes", "transfer.codes.notMovable");
        refuse(units, u -> u.getLocation() != null && u.getLocation().getId().equals(to.getId()), "codes",
                "transfer.codes.alreadyThere");
        Location toRack = StockService.rackOf(to, byId);
        if (toRack.isOffcut()) {
            refuse(units, u -> u.getKind() == UnitKind.SHEET, "toLocationId", "transfer.sheetToOffcut");
        }
        Map<UUID, String> holds = stockService.holds(units.stream().map(StockUnit::getId).toList());
        // A pending adjustment or an open stock count holds them: name each unit with its document
        List<String> held = units.stream().filter(u -> holds.containsKey(u.getId()))
                .map(u -> u.getCode() + " (" + holds.get(u.getId()) + ")").toList();
        if (!held.isEmpty()) {
            throw BusinessException.onField("codes", "transfer.codes.held", String.join(", ", held));
        }
        checkRack(units, toRack, byId);

        LocalDateTime now = LocalDateTime.now(clock);
        StockTransfer transfer = new StockTransfer();
        transfer.setNumber(numbers.next(DocumentType.TRANSFER));
        transfer.setToLocation(to);
        transfer.setNote(PartyRules.clean(dto.getNote()));
        transfer.setPostedAt(now);
        transfer.setPostedBy(AppUserPrincipal.current().map(AppUserPrincipal::getUsername).orElse("system"));
        int no = 0;
        for (StockUnit unit : units) {
            StockTransferLine line = new StockTransferLine();
            line.setTransfer(transfer);
            line.setLineNo(++no);
            line.setStockUnitId(unit.getId());
            line.setUnitCode(unit.getCode());
            line.setFromLocationId(unit.getLocation().getId());
            transfer.getLines().add(line);
        }
        repo.save(transfer);
        for (StockUnit unit : units) {
            stockService.transfer(unit, to, transfer.getId(), transfer.getNumber());
        }
        return transfer;
    }

    /** Lists the codes of the units a rule refuses, under the field. */
    private static void refuse(List<StockUnit> units, java.util.function.Predicate<StockUnit> refused, String field, String key) {
        List<String> codes = units.stream().filter(refused).map(StockUnit::getCode).toList();
        if (!codes.isEmpty()) {
            throw BusinessException.onField(field, key, String.join(", ", codes));
        }
    }

    /** The destination rack must take the units on top of what it holds; units already on it do not count twice (MD-03). */
    private void checkRack(List<StockUnit> units, Location toRack, Map<UUID, Location> byId) {
        RackLoad now = stockService.rackLoads(byId).getOrDefault(toRack.getId(), RackLoad.EMPTY);
        long pieces = 0;
        BigDecimal kg = BigDecimal.ZERO;
        for (StockUnit unit : units) {
            Location fromRack = StockService.rackOf(unit.getLocation(), byId);
            if (fromRack == null || !fromRack.getId().equals(toRack.getId())) {
                pieces++;
                kg = kg.add(unit.getWeightKg());
            }
        }
        RackLoad after = now.plus(pieces, kg);
        if (after.exceedsPieces(toRack.getMaxPieces())) {
            throw BusinessException.onField("toLocationId", "receipt.rack.pieces", toRack.getCode(), now.pieces(),
                    toRack.getMaxPieces(), pieces);
        }
        if (after.exceedsKg(toRack.getMaxWeightKg())) {
            throw BusinessException.onField("toLocationId", "receipt.rack.weight", toRack.getCode(), now.kg(),
                    toRack.getMaxWeightKg(), kg);
        }
    }
}
