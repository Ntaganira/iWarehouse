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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

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
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class LocationService {

    private final LocationRepository repo;
    private final StockUnitRepository unitRepo;

    public LocationService(LocationRepository repo, StockUnitRepository unitRepo) {
        this.repo = repo;
        this.unitRepo = unitRepo;
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
        location.setCode(dto.getCode());
        apply(location, dto);
        return location;
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
