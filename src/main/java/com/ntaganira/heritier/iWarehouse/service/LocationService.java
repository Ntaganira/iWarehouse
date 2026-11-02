package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.dto.LocationDto;
import com.ntaganira.heritier.iWarehouse.entity.AdministrativeNode;
import com.ntaganira.heritier.iWarehouse.entity.Cell;
import com.ntaganira.heritier.iWarehouse.entity.District;
import com.ntaganira.heritier.iWarehouse.entity.Location;
import com.ntaganira.heritier.iWarehouse.entity.Province;
import com.ntaganira.heritier.iWarehouse.entity.Sector;
import com.ntaganira.heritier.iWarehouse.entity.Village;
import com.ntaganira.heritier.iWarehouse.enums.AdministrativeTier;
import com.ntaganira.heritier.iWarehouse.enums.LocationType;
import com.ntaganira.heritier.iWarehouse.enums.RackOrientation;
import com.ntaganira.heritier.iWarehouse.enums.StockStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.CellRepository;
import com.ntaganira.heritier.iWarehouse.repository.DistrictRepository;
import com.ntaganira.heritier.iWarehouse.repository.LocationRepository;
import com.ntaganira.heritier.iWarehouse.repository.ProvinceRepository;
import com.ntaganira.heritier.iWarehouse.repository.SectorRepository;
import com.ntaganira.heritier.iWarehouse.repository.StockUnitRepository;
import com.ntaganira.heritier.iWarehouse.repository.VillageRepository;
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
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
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
    private final ProvinceRepository provinceRepo;
    private final DistrictRepository districtRepo;
    private final SectorRepository sectorRepo;
    private final CellRepository cellRepo;
    private final VillageRepository villageRepo;
    private final Clock clock;

    public LocationService(LocationRepository repo, StockUnitRepository unitRepo, ProvinceRepository provinceRepo,
                           DistrictRepository districtRepo, SectorRepository sectorRepo, CellRepository cellRepo,
                           VillageRepository villageRepo, Clock clock) {
        this.repo = repo;
        this.unitRepo = unitRepo;
        this.provinceRepo = provinceRepo;
        this.districtRepo = districtRepo;
        this.sectorRepo = sectorRepo;
        this.cellRepo = cellRepo;
        this.villageRepo = villageRepo;
        this.clock = clock;
    }

    /** The administrative options the site form shows for the current selection. */
    public record Options(List<Province> provinces, List<District> districts, List<Sector> sectors,
                          List<Cell> cells, List<Village> villages) {
    }

    /** One selectable administrative unit for the cascading form and its JSON endpoint. */
    public record AreaOption(UUID id, String code, String name) {

        public String getLabel() {
            return code + " \u00b7 " + name;
        }
    }

    /**
     * The administrative area a location shows: a site's own mapping, or the one inherited
     * from its site. A vehicle has none.
     */
    public record Area(AdministrativeTier tier, UUID id, String code, String name, String path, boolean mapped,
                       boolean inherited, String siteCode) {

        static Area unmapped(String siteCode, boolean inherited) {
            return new Area(null, null, null, null, null, false, inherited, siteCode);
        }
    }

    /** The tree and the area of every location on the page. */
    public record TreeView(List<LocationTree.Row<Location>> rows, Map<UUID, Area> areas) {
    }

    private record Selection(AdministrativeTier tier, Province province, District district, Sector sector,
                             Cell cell, Village village) {

        private AdministrativeNode node() {
            if (tier == null) {
                return null;
            }
            return switch (tier) {
                case PROVINCE -> province;
                case DISTRICT -> district;
                case SECTOR -> sector;
                case CELL -> cell;
                case VILLAGE -> village;
            };
        }
    }

    /** A labelled location by its code, as a scanned label gives it. */
    public Optional<Location> findByCode(String code) {
        return StringUtils.hasText(code) ? repo.findByCode(code.trim().toUpperCase(Locale.ROOT)) : Optional.empty();
    }

    /** The whole tree in display order; inactive locations only when asked for. */
    public List<LocationTree.Row<Location>> tree(boolean includeInactive) {
        List<Location> all = repo.findAllByOrderByCodeAsc();
        return rows(all, includeInactive);
    }

    /** The tree and each row's area, loaded in one pass. */
    public TreeView treeView(boolean includeInactive) {
        List<Location> all = repo.findAllByOrderByCodeAsc();
        return new TreeView(rows(all, includeInactive), areas(all));
    }

    public Location findById(UUID id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException("Location", id));
    }

    /** The area of one location, or null for a vehicle. */
    public Area areaOf(Location location) {
        Map<UUID, Location> byId = new HashMap<>();
        for (Location l : repo.findAllByOrderByCodeAsc()) {
            byId.put(l.getId(), l);
        }
        return areaOf(location, byId);
    }

    /** The form's administrative options; only the current chain is loaded, lower levels are fetched by the page. */
    public Options options(LocationDto dto) {
        Selection current = read(dto);
        List<Province> provinces = selectable(provinceRepo.findAllByOrderByCodeAsc(), current.province());
        List<District> districts = current.province() == null ? List.of()
                : selectable(districtRepo.findByProvinceIdOrderByCodeAsc(current.province().getId()),
                current.district());
        List<Sector> sectors = current.district() == null ? List.of()
                : selectable(sectorRepo.findByDistrictIdOrderByCodeAsc(current.district().getId()),
                current.sector());
        List<Cell> cells = current.sector() == null ? List.of()
                : selectable(cellRepo.findBySectorIdOrderByCodeAsc(current.sector().getId()), current.cell());
        List<Village> villages = current.cell() == null ? List.of()
                : selectable(villageRepo.findByCellIdOrderByCodeAsc(current.cell().getId()), current.village());
        return new Options(provinces, districts, sectors, cells, villages);
    }

    /** Children of one unit for the cascading selects; disabled units are not offered for a new mapping. */
    public List<AreaOption> children(AdministrativeTier tier, UUID parentId) {
        if (tier == null) {
            return List.of();
        }
        return switch (tier) {
            case PROVINCE -> areaOptions(provinceRepo.findAllByOrderByCodeAsc());
            case DISTRICT -> parentId == null ? List.of()
                    : areaOptions(districtRepo.findByProvinceIdOrderByCodeAsc(parentId));
            case SECTOR -> parentId == null ? List.of()
                    : areaOptions(sectorRepo.findByDistrictIdOrderByCodeAsc(parentId));
            case CELL -> parentId == null ? List.of()
                    : areaOptions(cellRepo.findBySectorIdOrderByCodeAsc(parentId));
            case VILLAGE -> parentId == null ? List.of()
                    : areaOptions(villageRepo.findByCellIdOrderByCodeAsc(parentId));
        };
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
    private void apply(Location location, LocationDto dto) {
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
        if (location.isSite()) {
            applyArea(location, dto);
        } else {
            clearArea(location);
        }
    }

    /** Only a site carries an area; zones, racks and slots inherit it, and vehicles are never mapped. */
    private void applyArea(Location location, LocationDto dto) {
        if (dto.getTier() == null) {
            clearArea(location);
            return;
        }
        Selection area = read(dto);
        if (area.node() == null) {
            throw BusinessException.onField(missingField(area, dto.getTier()), "location.area.required");
        }
        if (!area.node().isEnabled() && !sameArea(location, area)) {
            throw BusinessException.onField(levelField(dto.getTier()), "location.area.disabled");
        }
        location.setTier(area.tier());
        location.setProvince(area.province());
        location.setDistrict(area.district());
        location.setSector(area.sector());
        location.setCell(area.cell());
        location.setVillage(area.village());
    }

    private static void clearArea(Location location) {
        location.setTier(null);
        location.setProvince(null);
        location.setDistrict(null);
        location.setSector(null);
        location.setCell(null);
        location.setVillage(null);
    }

    private Selection read(LocationDto dto) {
        Province province = dto.getProvinceId() == null ? null
                : provinceRepo.findById(dto.getProvinceId()).orElse(null);
        District district = dto.getDistrictId() == null ? null
                : districtRepo.findById(dto.getDistrictId()).orElse(null);
        Sector sector = dto.getSectorId() == null ? null
                : sectorRepo.findById(dto.getSectorId()).orElse(null);
        Cell cell = dto.getCellId() == null ? null : cellRepo.findById(dto.getCellId()).orElse(null);
        Village village = dto.getVillageId() == null ? null
                : villageRepo.findById(dto.getVillageId()).orElse(null);

        // A lower unit fixes its whole chain, so a hand-edited URL cannot mix two branches.
        if (village != null) {
            cell = village.getCell();
        }
        if (cell != null) {
            sector = cell.getSector();
        }
        if (sector != null) {
            district = sector.getDistrict();
        }
        if (district != null) {
            province = district.getProvince();
        }

        if (dto.getTier() == null) {
            return new Selection(null, null, null, null, null, null);
        }
        return switch (dto.getTier()) {
            case PROVINCE -> new Selection(dto.getTier(), province, null, null, null, null);
            case DISTRICT -> new Selection(dto.getTier(), province, district, null, null, null);
            case SECTOR -> new Selection(dto.getTier(), province, district, sector, null, null);
            case CELL -> new Selection(dto.getTier(), province, district, sector, cell, null);
            case VILLAGE -> new Selection(dto.getTier(), province, district, sector, cell, village);
        };
    }

    private static boolean sameArea(Location location, Selection area) {
        if (location == null || !location.isSite() || location.getTier() != area.tier() || area.node() == null) {
            return false;
        }
        UUID current = location.getAreaId();
        return current != null && current.equals(area.node().getId());
    }

    private static String missingField(Selection area, AdministrativeTier tier) {
        if (area.province() == null) {
            return "provinceId";
        }
        return switch (tier) {
            case PROVINCE -> "provinceId";
            case DISTRICT -> "districtId";
            case SECTOR -> area.district() == null ? "districtId" : "sectorId";
            case CELL -> area.district() == null ? "districtId"
                    : area.sector() == null ? "sectorId" : "cellId";
            case VILLAGE -> area.district() == null ? "districtId"
                    : area.sector() == null ? "sectorId"
                    : area.cell() == null ? "cellId" : "villageId";
        };
    }

    private static String levelField(AdministrativeTier tier) {
        return switch (tier) {
            case PROVINCE -> "provinceId";
            case DISTRICT -> "districtId";
            case SECTOR -> "sectorId";
            case CELL -> "cellId";
            case VILLAGE -> "villageId";
        };
    }

    private static List<LocationTree.Row<Location>> rows(List<Location> all, boolean includeInactive) {
        List<Location> shown = includeInactive ? all : all.stream().filter(Location::isEnabled).toList();
        return LocationTree.flatten(shown, Location::getId, Location::getParentId, Comparator.comparing(Location::getCode));
    }

    private static Map<UUID, Area> areas(List<Location> all) {
        Map<UUID, Location> byId = new HashMap<>();
        for (Location location : all) {
            byId.put(location.getId(), location);
        }
        Map<UUID, Area> areas = new HashMap<>();
        for (Location location : all) {
            Area area = areaOf(location, byId);
            if (area != null) {
                areas.put(location.getId(), area);
            }
        }
        return areas;
    }

    private static Area areaOf(Location location, Map<UUID, Location> byId) {
        if (location.isVehicle()) {
            return null;
        }
        Location site = siteOf(location, byId);
        if (site == null) {
            return null;
        }
        AdministrativeNode node = site.getArea();
        boolean inherited = !location.isSite();
        if (node == null) {
            return Area.unmapped(site.getCode(), inherited);
        }
        return new Area(site.getTier(), node.getId(), node.getCode(), node.getName(), site.getAreaPath(),
                true, inherited, site.getCode());
    }

    private static Location siteOf(Location location, Map<UUID, Location> byId) {
        Location current = location;
        Set<UUID> seen = new HashSet<>();
        while (current != null && !current.isSite()) {
            if (current.isVehicle() || current.getParentId() == null || !seen.add(current.getId())) {
                return null;
            }
            current = byId.get(current.getParentId());
        }
        return current;
    }

    private static <T extends AdministrativeNode> List<T> selectable(List<T> nodes, T current) {
        UUID currentId = current == null ? null : current.getId();
        return nodes.stream().filter(n -> n.isEnabled() || n.getId().equals(currentId)).toList();
    }

    private static <T extends AdministrativeNode> List<AreaOption> areaOptions(List<T> nodes) {
        return nodes.stream().filter(AdministrativeNode::isEnabled)
                .map(n -> new AreaOption(n.getId(), n.getCode(), n.getName())).toList();
    }

    private static void requireManual(Location location) {
        if (!location.getType().isManual()) {
            throw BusinessException.of("location.vehicle.managed", location.getCode());
        }
    }
}
