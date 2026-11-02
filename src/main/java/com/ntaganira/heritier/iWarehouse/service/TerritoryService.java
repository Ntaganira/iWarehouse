package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.AdministrativeNode;
import com.ntaganira.heritier.iWarehouse.entity.Cell;
import com.ntaganira.heritier.iWarehouse.entity.District;
import com.ntaganira.heritier.iWarehouse.entity.Province;
import com.ntaganira.heritier.iWarehouse.entity.Sector;
import com.ntaganira.heritier.iWarehouse.entity.Territory;
import com.ntaganira.heritier.iWarehouse.entity.User;
import com.ntaganira.heritier.iWarehouse.entity.Village;
import com.ntaganira.heritier.iWarehouse.enums.AdministrativeTier;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.CellRepository;
import com.ntaganira.heritier.iWarehouse.repository.DistrictRepository;
import com.ntaganira.heritier.iWarehouse.repository.ProvinceRepository;
import com.ntaganira.heritier.iWarehouse.repository.SectorRepository;
import com.ntaganira.heritier.iWarehouse.repository.TerritoryRepository;
import com.ntaganira.heritier.iWarehouse.repository.UserRepository;
import com.ntaganira.heritier.iWarehouse.repository.VillageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : TerritoryService.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Sales territories and their Regional Sales Managers (BP 6, Res. 7; the number of territories is the
 *               client's to set, so they are records, not code): a code in capitals, unique, a name, a manager who is a
 *               user (their name copied) and the one Rwandan administrative unit the territory covers. Deactivated,
 *               never deleted.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class TerritoryService {

    private final TerritoryRepository repo;
    private final UserRepository userRepo;
    private final ProvinceRepository provinceRepo;
    private final DistrictRepository districtRepo;
    private final SectorRepository sectorRepo;
    private final CellRepository cellRepo;
    private final VillageRepository villageRepo;

    public TerritoryService(TerritoryRepository repo, UserRepository userRepo, ProvinceRepository provinceRepo,
                            DistrictRepository districtRepo, SectorRepository sectorRepo, CellRepository cellRepo,
                            VillageRepository villageRepo) {
        this.repo = repo;
        this.userRepo = userRepo;
        this.provinceRepo = provinceRepo;
        this.districtRepo = districtRepo;
        this.sectorRepo = sectorRepo;
        this.cellRepo = cellRepo;
        this.villageRepo = villageRepo;
    }

    /** What a territory is given: code, name, manager and the administrative unit it covers. */
    public record Form(String code, String name, Long managerUserId, AdministrativeTier tier,
                       UUID provinceId, UUID districtId, UUID sectorId, UUID cellId, UUID villageId) {
    }

    /** The administrative options the form shows for the current selection. */
    public record Options(List<Province> provinces, List<District> districts, List<Sector> sectors,
                          List<Cell> cells, List<Village> villages) {
    }

    /** One selectable unit for the cascading form and its JSON endpoint. */
    public record AreaOption(UUID id, String code, String name) {

        public String getLabel() {
            return code + " \u00b7 " + name;
        }
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

    public List<Territory> all() {
        return repo.findAllWithAreaByOrderByCodeAsc();
    }

    /** The list's filters: code, name or area, level and active state. */
    public List<Territory> search(String search, AdministrativeTier tier, String status) {
        String term = PartyRules.clean(search);
        String state = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
        String needle = term == null ? null : term.toLowerCase(Locale.ROOT);
        return all().stream()
                .filter(t -> tier == null || t.getTier() == tier)
                .filter(t -> switch (state) {
                    case "active" -> t.isEnabled();
                    case "inactive" -> !t.isEnabled();
                    default -> true;
                })
                .filter(t -> needle == null || matches(t, needle))
                .toList();
    }

    private static boolean matches(Territory t, String needle) {
        String path = t.getAreaPath();
        String areaCode = t.getAreaCode();
        return t.getCode().toLowerCase(Locale.ROOT).contains(needle)
                || t.getName().toLowerCase(Locale.ROOT).contains(needle)
                || (path != null && path.toLowerCase(Locale.ROOT).contains(needle))
                || (areaCode != null && areaCode.toLowerCase(Locale.ROOT).contains(needle));
    }

    public Territory find(UUID id) {
        return repo.findWithAreaById(id).orElseThrow(() -> new NotFoundException("Territory", id));
    }

    public Form formOf(Territory t) {
        return new Form(t.getCode(), t.getName(), t.getManagerUserId(), t.getTier(),
                idOf(t.getProvince()), idOf(t.getDistrict()), idOf(t.getSector()), idOf(t.getCell()),
                idOf(t.getVillage()));
    }

    /** Enabled users, who may manage a territory. */
    public List<User> managers() {
        return userRepo.findAll().stream().filter(User::isEnabled)
                .sorted((a, b) -> a.getFullName().compareToIgnoreCase(b.getFullName())).toList();
    }

    /** The options to render on the form; only the current chain is loaded, lower levels are fetched by the page. */
    public Options options(Form form) {
        Selection current = read(form);
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

    @Transactional
    public Territory create(Form form) {
        Territory t = new Territory();
        apply(t, form);
        return repo.save(t);
    }

    @Transactional
    public Territory update(UUID id, Form form) {
        Territory t = find(id);
        apply(t, form);
        return t;
    }

    @Transactional
    public Territory setEnabled(UUID id, boolean enabled) {
        Territory t = find(id);
        t.setEnabled(enabled);
        return t;
    }

    private void apply(Territory t, Form form) {
        String code = PartyRules.clean(form.code());
        if (code == null || !code.matches("[A-Za-z0-9-]{1,20}")) {
            throw BusinessException.onField("code", "territory.code.invalid");
        }
        code = code.toUpperCase(Locale.ROOT);
        String finalCode = code;
        if (repo.findByCode(code).filter(o -> !o.getId().equals(t.getId())).isPresent()) {
            throw BusinessException.onField("code", "territory.code.taken", finalCode);
        }
        String name = PartyRules.clean(form.name());
        if (name == null || name.length() > 100) {
            throw BusinessException.onField("name", "territory.name.required");
        }
        User manager = null;
        if (form.managerUserId() != null) {
            manager = userRepo.findById(form.managerUserId()).filter(User::isEnabled)
                    .orElseThrow(() -> BusinessException.onField("managerUserId", "territory.manager.invalid"));
        }
        Selection area = requireArea(t, form);
        checkAvailable(t, area);
        t.setCode(code);
        t.setName(name);
        t.setManagerUserId(manager == null ? null : manager.getId());
        t.setManagerName(manager == null ? null : manager.getFullName());
        t.setTier(area.tier());
        t.setProvince(area.province());
        t.setDistrict(area.district());
        t.setSector(area.sector());
        t.setCell(area.cell());
        t.setVillage(area.village());
    }

    private Selection requireArea(Territory territory, Form form) {
        if (form.tier() == null) {
            throw BusinessException.onField("tier", "territory.tier.required");
        }
        Selection area = read(form);
        if (area.node() == null) {
            throw BusinessException.onField(missingField(area, form.tier()), "territory.area.required");
        }
        if (!area.node().isEnabled() && !sameArea(territory, area)) {
            throw BusinessException.onField(levelField(form.tier()), "territory.area.disabled");
        }
        return area;
    }

    private void checkAvailable(Territory territory, Selection area) {
        UUID nodeId = area.node().getId();
        var duplicate = switch (area.tier()) {
            case PROVINCE -> repo.findByProvinceId(nodeId);
            case DISTRICT -> repo.findByDistrictId(nodeId);
            case SECTOR -> repo.findBySectorId(nodeId);
            case CELL -> repo.findByCellId(nodeId);
            case VILLAGE -> repo.findByVillageId(nodeId);
        };
        duplicate.filter(o -> !o.getId().equals(territory.getId())).ifPresent(o -> {
            throw BusinessException.onField(levelField(area.tier()), "territory.area.taken", o.getCode());
        });
    }

    private Selection read(Form form) {
        Province province = form.provinceId() == null ? null
                : provinceRepo.findById(form.provinceId()).orElse(null);
        District district = form.districtId() == null ? null
                : districtRepo.findById(form.districtId()).orElse(null);
        Sector sector = form.sectorId() == null ? null
                : sectorRepo.findById(form.sectorId()).orElse(null);
        Cell cell = form.cellId() == null ? null : cellRepo.findById(form.cellId()).orElse(null);
        Village village = form.villageId() == null ? null
                : villageRepo.findById(form.villageId()).orElse(null);

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

        if (form.tier() == null) {
            return new Selection(null, null, null, null, null, null);
        }
        return switch (form.tier()) {
            case PROVINCE -> new Selection(form.tier(), province, null, null, null, null);
            case DISTRICT -> new Selection(form.tier(), province, district, null, null, null);
            case SECTOR -> new Selection(form.tier(), province, district, sector, null, null);
            case CELL -> new Selection(form.tier(), province, district, sector, cell, null);
            case VILLAGE -> new Selection(form.tier(), province, district, sector, cell, village);
        };
    }

    private static boolean sameArea(Territory territory, Selection area) {
        if (territory == null || territory.getTier() != area.tier() || area.node() == null) {
            return false;
        }
        UUID current = territory.getAreaId();
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

    private static UUID idOf(AdministrativeNode node) {
        return node == null ? null : node.getId();
    }

    private static <T extends AdministrativeNode> List<T> selectable(List<T> nodes, T current) {
        UUID currentId = current == null ? null : current.getId();
        return nodes.stream().filter(n -> n.isEnabled() || n.getId().equals(currentId)).toList();
    }

    private static <T extends AdministrativeNode> List<AreaOption> areaOptions(List<T> nodes) {
        return nodes.stream().filter(AdministrativeNode::isEnabled)
                .map(n -> new AreaOption(n.getId(), n.getCode(), n.getName())).toList();
    }
}
