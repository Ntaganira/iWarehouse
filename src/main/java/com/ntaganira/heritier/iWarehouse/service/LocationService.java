package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.dto.LocationDto;
import com.ntaganira.heritier.iWarehouse.entity.Location;
import com.ntaganira.heritier.iWarehouse.enums.LocationType;
import com.ntaganira.heritier.iWarehouse.enums.RackOrientation;
import com.ntaganira.heritier.iWarehouse.enums.StockStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.LocationRepository;
import com.ntaganira.heritier.iWarehouse.repository.StockUnitRepository;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : LocationService.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : Location tree (MD-02) and rack limits (MD-03). A location without parent is a site;
 *               under a site go zones, under a zone racks, under a rack slots. Vehicle locations come
 *               with their vehicle (FLT-01) and are not added or changed here. A location is only
 *               deactivated once its sub-locations are, and only reactivated under an active parent.
 *               Racks and slots get labels (QR of the code); the first print fixes the code.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class LocationService {

    private final LocationRepository repo;
    private final StockUnitRepository unitRepo;
    private final Clock clock;

    public LocationService(LocationRepository repo, StockUnitRepository unitRepo, Clock clock) {
        this.repo = repo;
        this.unitRepo = unitRepo;
        this.clock = clock;
    }

    /** A labelled location by its code, as a scanned label gives it. */
    public Optional<Location> findByCode(String code) {
        return StringUtils.hasText(code) ? repo.findByCode(code.trim().toUpperCase(Locale.ROOT)) : Optional.empty();
    }

    /** The whole tree in display order; inactive locations only when asked for. */
    public List<LocationTree.Row<Location>> tree(boolean includeInactive) {
        List<Location> all = repo.findAllByOrderByCodeAsc();
        List<Location> shown = includeInactive ? all : all.stream().filter(Location::isEnabled).toList();
        return LocationTree.flatten(shown, Location::getId, Location::getParentId, Comparator.comparing(Location::getCode));
    }

    public Location findById(UUID id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException("Location", id));
    }

    /** The locations above this one, site first (for breadcrumbs). */
    public List<Location> ancestors(Location location) {
        LinkedList<Location> path = new LinkedList<>();
        UUID parentId = location.getParentId();
        while (parentId != null && path.size() < LocationType.values().length) {
            Location parent = findById(parentId);
            path.addFirst(parent);
            parentId = parent.getParentId();
        }
        return path;
    }

    public List<Location> children(UUID id) {
        return repo.findByParentIdOrderByCodeAsc(id);
    }

    /** Next free code for a new location under {@code parent} (WH-A-R03), or null. */
    public String suggestCode(Location parent) {
        if (parent == null) {
            return null;
        }
        Set<String> taken = new HashSet<>(repo.findAllCodes());
        return LocationTree.suggestCode(parent.getCode(), parent.getType().getChildType(), taken);
    }

    @Transactional
    public Location create(LocationDto dto) {
        Location parent = null;
        LocationType type = LocationType.SITE;
        if (dto.getParentId() != null) {
            parent = findById(dto.getParentId());
            type = parent.getType().getChildType();
            if (type == null || !type.isManual()) {
                throw BusinessException.of("location.parent.noChildren", parent.getCode());
            }
            if (!parent.isEnabled()) {
                throw BusinessException.of("location.parent.inactive", parent.getCode());
            }
        }
        if (repo.existsByCode(dto.getCode())) {
            throw BusinessException.onField("code", "location.code.taken", dto.getCode());
        }
        Location location = new Location();
        location.setType(type);
        location.setParentId(parent == null ? null : parent.getId());
        location.setCode(dto.getCode());
        apply(location, dto);
        return repo.save(location);
    }

    @Transactional
    public Location update(UUID id, LocationDto dto) {
        Location location = findById(id);
        requireManual(location);
        if (!dto.getCode().equals(location.getCode()) && repo.existsByCodeAndIdNot(dto.getCode(), id)) {
            throw BusinessException.onField("code", "location.code.taken", dto.getCode());
        }
        if (location.isLabelled() && !dto.getCode().equals(location.getCode())) {
            throw BusinessException.onField("code", "location.code.fixed", location.getCode());
        }
        location.setCode(dto.getCode());
        apply(location, dto);
        return location;
    }

    // ---------------------------------------------------------------- labels (MD-02)

    /** The active racks and slots labelled from this place: itself if it is one, and every one under it. */
    public List<Location> labelPlaces(UUID id) {
        Location root = findById(id);
        Map<UUID, List<Location>> children = new HashMap<>();
        for (Location l : repo.findAllByOrderByCodeAsc()) {
            if (l.getParentId() != null) {
                children.computeIfAbsent(l.getParentId(), k -> new ArrayList<>()).add(l);
            }
        }
        List<Location> places = new ArrayList<>();
        Deque<Location> todo = new ArrayDeque<>(List.of(root));
        while (!todo.isEmpty()) {
            Location l = todo.pop();
            if (!l.isEnabled()) {
                continue; // an inactive place has no active place under it
            }
            if (l.isLabelKind()) {
                places.add(l);
            }
            todo.addAll(children.getOrDefault(l.getId(), List.of()));
        }
        places.sort(Comparator.comparing(Location::getCode));
        return places;
    }

    /** The places of {@link #labelPlaces} whose label has been printed: what the label page shows. */
    public List<Location> labelledPlaces(UUID id) {
        return labelPlaces(id).stream().filter(Location::isLabelled).toList();
    }

    /** The codes above each place, site first ("WH", "WH-A"): where a label tells it is. */
    public Map<UUID, List<String>> pathCodes(List<Location> places) {
        Map<UUID, Location> byId = new HashMap<>();
        for (Location l : repo.findAllByOrderByCodeAsc()) {
            byId.put(l.getId(), l);
        }
        Map<UUID, List<String>> paths = new HashMap<>();
        for (Location place : places) {
            LinkedList<String> path = new LinkedList<>();
            Location parent = place.getParentId() == null ? null : byId.get(place.getParentId());
            while (parent != null && path.size() < LocationType.values().length) {
                path.addFirst(parent.getCode());
                parent = parent.getParentId() == null ? null : byId.get(parent.getParentId());
            }
            paths.put(place.getId(), path);
        }
        return paths;
    }

    /** What printing the labels of a place did: the places labelled, and those whose code it fixed. */
    public record LabelRun(Location location, List<Location> places, List<Location> fixed) {
    }

    /**
     * Prints the labels of a place: its active racks and slots. The first print of each fixes its code
     * (who and when are kept); a reprint changes nothing.
     */
    @Transactional
    public LabelRun printLabels(UUID id) {
        Location location = findById(id);
        requireManual(location);
        List<Location> places = labelPlaces(id);
        if (places.isEmpty()) {
            throw BusinessException.of("location.labels.none", location.getCode());
        }
        LocalDateTime now = LocalDateTime.now(clock);
        String user = AppUserPrincipal.current().map(AppUserPrincipal::getUsername).orElse("system");
        List<Location> fixed = new ArrayList<>();
        for (Location place : places) {
            if (!place.isLabelled()) {
                place.setLabelPrintedAt(now);
                place.setLabelPrintedBy(user);
                fixed.add(place);
            }
        }
        return new LabelRun(location, places, fixed);
    }

    @Transactional
    public Location setEnabled(UUID id, boolean enabled) {
        Location location = findById(id);
        requireManual(location);
        if (!enabled) {
            long active = repo.countByParentIdAndEnabledTrue(id);
            if (active > 0) {
                throw BusinessException.of("location.disable.children", location.getCode(), active);
            }
            long held = unitRepo.countByLocation_IdAndStatusIn(id, StockStatus.onHand());
            if (held > 0) {
                throw BusinessException.of("location.disable.stock", location.getCode(), held);
            }
        } else if (location.getParentId() != null) {
            Location parent = findById(location.getParentId());
            if (!parent.isEnabled()) {
                throw BusinessException.of("location.enable.parentInactive", location.getCode(), parent.getCode());
            }
        }
        location.setEnabled(enabled);
        return location;
    }

    /** Name for every type; off-cut flag, limits and orientation for racks only (chk_locations_rack_only). */
    private static void apply(Location location, LocationDto dto) {
        location.setName(StringUtils.hasText(dto.getName()) ? dto.getName().trim() : null);
        if (location.isRack()) {
            location.setOffcut(dto.isOffcut());
            location.setMaxWeightKg(dto.getMaxWeightKg());
            location.setMaxPieces(dto.getMaxPieces());
            location.setOrientation(dto.getOrientation() == null ? RackOrientation.BOTH : dto.getOrientation());
        } else {
            location.setOffcut(false);
            location.setMaxWeightKg(null);
            location.setMaxPieces(null);
            location.setOrientation(null);
        }
    }

    private static void requireManual(Location location) {
        if (!location.getType().isManual()) {
            throw BusinessException.of("location.vehicle.managed", location.getCode());
        }
    }
}
