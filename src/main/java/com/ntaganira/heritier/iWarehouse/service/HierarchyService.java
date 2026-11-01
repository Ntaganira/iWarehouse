package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.entity.AdministrativeNode;
import com.ntaganira.heritier.iWarehouse.entity.Cell;
import com.ntaganira.heritier.iWarehouse.entity.District;
import com.ntaganira.heritier.iWarehouse.entity.Province;
import com.ntaganira.heritier.iWarehouse.entity.Sector;
import com.ntaganira.heritier.iWarehouse.entity.Village;
import com.ntaganira.heritier.iWarehouse.enums.AdministrativeTier;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.CellRepository;
import com.ntaganira.heritier.iWarehouse.repository.DistrictRepository;
import com.ntaganira.heritier.iWarehouse.repository.ProvinceRepository;
import com.ntaganira.heritier.iWarehouse.repository.SectorRepository;
import com.ntaganira.heritier.iWarehouse.repository.VillageRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : HierarchyService.java
 * - Date      : 2026. 11. 01.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The Rwandan administrative hierarchy dashboard: cascading filters, paginated rollup paths,
 *               effective visibility, rename, visibility toggles and guarded cascade deletion. A row's
 *               allow_edit flag is the system lock: a locked row is never renamed or deleted, and a branch
 *               containing a locked row is never deleted.
 * </pre>
 */
@Service
public class HierarchyService {

    public static final String MODULE = "Administrative hierarchy";
    public static final List<String> STATUSES = List.of("VISIBLE", "HIDDEN", "INACTIVE");

    private static final String PATH_SEPARATOR = " / ";
    private static final int NAME_MAX = 100;

    private final ProvinceRepository provinceRepo;
    private final DistrictRepository districtRepo;
    private final SectorRepository sectorRepo;
    private final CellRepository cellRepo;
    private final VillageRepository villageRepo;

    public HierarchyService(ProvinceRepository provinceRepo, DistrictRepository districtRepo, SectorRepository sectorRepo,
                            CellRepository cellRepo, VillageRepository villageRepo) {
        this.provinceRepo = provinceRepo;
        this.districtRepo = districtRepo;
        this.sectorRepo = sectorRepo;
        this.cellRepo = cellRepo;
        this.villageRepo = villageRepo;
    }

    // ---------------------------------------------------------------- views

    /** One row of the grid. hiddenByParent is independent of the row's own enabled flag. */
    public record Row(AdministrativeTier tier, UUID id, String code, String name, String path,
                      boolean enabled, boolean effectiveEnabled, boolean allowEdit, long descendants,
                      List<PathLink> pathLinks) {

        public boolean isHiddenByParent() {
            return enabled && !effectiveEnabled;
        }

        public boolean isLocked() {
            return !allowEdit;
        }

        public String getStatusKey() {
            if (!enabled) {
                return "INACTIVE";
            }
            return effectiveEnabled ? "VISIBLE" : "HIDDEN";
        }
    }

    /** One step of a row's breadcrumb: the tier its link opens and the filters that reach it. */
    public record PathLink(String name, AdministrativeTier tier, UUID provinceId, UUID districtId,
                           UUID sectorId, UUID cellId, String q) {
    }

    public record TierStat(AdministrativeTier tier, long total, long active) {
    }

    public record Dashboard(AdministrativeTier tier, Page<Row> rows, List<TierStat> stats,
                            List<Province> provinces, List<District> districts, List<Sector> sectors,
                            List<Cell> cells, UUID provinceId, UUID districtId, UUID sectorId, UUID cellId,
                            String q, String status, String query, String returnQuery) {
    }

    public record Filter(AdministrativeTier tier, String q, String status, UUID provinceId, UUID districtId,
                         UUID sectorId, UUID cellId, int page) {
    }

    public record RenameResult(AdministrativeTier tier, String code, String before, String after) {
    }

    public record ToggleResult(AdministrativeTier tier, String code, String name, boolean enabled) {
    }

    public record DeleteResult(AdministrativeTier tier, String code, String name, long total) {

        public long descendants() {
            return total - 1;
        }
    }

    @Transactional(readOnly = true)
    public Dashboard dashboard(Filter requested) {
        AdministrativeTier tier = requested.tier() == null ? AdministrativeTier.VILLAGE : requested.tier();
        String q = text(requested.q());
        String status = status(requested.status());

        Province province = requested.provinceId() == null ? null : provinceRepo.findById(requested.provinceId()).orElse(null);
        District district = requested.districtId() == null ? null : districtRepo.findById(requested.districtId()).orElse(null);
        Sector sector = requested.sectorId() == null ? null : sectorRepo.findById(requested.sectorId()).orElse(null);
        Cell cell = requested.cellId() == null ? null : cellRepo.findById(requested.cellId()).orElse(null);

        // A lower filter alone completes the chain above it; a lower filter that does not belong to the
        // selected parent is dropped, so a hand-edited URL never produces a misleading page.
        if (cell != null && sector == null) {
            sector = cell.getSector();
        }
        if (sector != null && district == null) {
            district = sector.getDistrict();
        }
        if (district != null && province == null) {
            province = district.getProvince();
        }
        if (district != null && province != null && !district.getProvince().getId().equals(province.getId())) {
            district = null;
            sector = null;
            cell = null;
        }
        if (sector != null && district != null && !sector.getDistrict().getId().equals(district.getId())) {
            sector = null;
            cell = null;
        }
        if (cell != null && sector != null && !cell.getSector().getId().equals(sector.getId())) {
            cell = null;
        }

        Page<Row> rows = rows(tier, q, status,
                province == null ? null : province.getId(),
                district == null ? null : district.getId(),
                sector == null ? null : sector.getId(),
                cell == null ? null : cell.getId(),
                Math.max(requested.page(), 0), Paging.SIZE);
        List<Province> provinces = provinceRepo.findAllByOrderByCodeAsc();
        List<District> districts = province == null ? List.of() : districtRepo.findByProvinceIdOrderByCodeAsc(province.getId());
        List<Sector> sectors = district == null ? List.of() : sectorRepo.findByDistrictIdOrderByCodeAsc(district.getId());
        List<Cell> cells = sector == null ? List.of() : cellRepo.findBySectorIdOrderByCodeAsc(sector.getId());
        String query = queryString(tier, q, status,
                province == null ? null : province.getId(),
                district == null ? null : district.getId(),
                sector == null ? null : sector.getId(),
                cell == null ? null : cell.getId());
        String returnQuery = query + "&page=" + Math.max(requested.page(), 0);
        return new Dashboard(tier, rows, stats(), provinces, districts, sectors, cells,
                province == null ? null : province.getId(),
                district == null ? null : district.getId(),
                sector == null ? null : sector.getId(),
                cell == null ? null : cell.getId(),
                q, status, query, returnQuery);
    }

    /** The first rows of one tier for the header search; no dashboard filters. */
    @Transactional(readOnly = true)
    public Page<Row> findRows(AdministrativeTier tier, String q, int page, int size) {
        return rows(tier == null ? AdministrativeTier.VILLAGE : tier, text(q), null,
                null, null, null, null, Math.max(page, 0), Math.max(size, 1));
    }

    private Page<Row> rows(AdministrativeTier tier, String q, String status, UUID provinceId, UUID districtId,
                           UUID sectorId, UUID cellId, int page, int size) {
        PageRequest request = PageRequest.of(page, size);
        // PostgreSQL cannot infer a type for a null bound into lower(...), and Hibernate would send bytea.
        // An empty string means "no value" in every search query, so the filter clauses stay text typed.
        String search = q == null ? "" : q;
        String visibility = status == null ? "" : status;
        return switch (tier) {
            case PROVINCE -> provinceRepo.search(search, visibility, request).map(this::row);
            case DISTRICT -> districtRepo.search(search, visibility, provinceId, request).map(this::row);
            case SECTOR -> sectorRepo.search(search, visibility, districtId, request).map(this::row);
            case CELL -> cellRepo.search(search, visibility, sectorId, request).map(this::row);
            case VILLAGE -> villageRepo.search(search, visibility, cellId, request).map(this::row);
        };
    }

    private List<TierStat> stats() {
        return List.of(
                new TierStat(AdministrativeTier.PROVINCE, provinceRepo.count(), provinceRepo.countByEnabledTrue()),
                new TierStat(AdministrativeTier.DISTRICT, districtRepo.count(), districtRepo.countByEnabledTrue()),
                new TierStat(AdministrativeTier.SECTOR, sectorRepo.count(), sectorRepo.countByEnabledTrue()),
                new TierStat(AdministrativeTier.CELL, cellRepo.count(), cellRepo.countByEnabledTrue()),
                new TierStat(AdministrativeTier.VILLAGE, villageRepo.count(), villageRepo.countByEnabledTrue()));
    }

    private Row row(Province p) {
        long descendants = districtRepo.countByCodeStartingWith(p.getCode())
                + sectorRepo.countByCodeStartingWith(p.getCode())
                + cellRepo.countByCodeStartingWith(p.getCode())
                + villageRepo.countByCodeStartingWith(p.getCode());
        List<PathLink> links = List.of(provinceLink(p));
        return new Row(AdministrativeTier.PROVINCE, p.getId(), p.getCode(), p.getName(), joinPath(links),
                p.isEnabled(), p.isEnabled(), p.isAllowEdit(), descendants, links);
    }

    private Row row(District d) {
        List<PathLink> links = List.of(provinceLink(d.getProvince()), districtLink(d));
        long descendants = sectorRepo.countByCodeStartingWith(d.getCode())
                + cellRepo.countByCodeStartingWith(d.getCode())
                + villageRepo.countByCodeStartingWith(d.getCode());
        return new Row(AdministrativeTier.DISTRICT, d.getId(), d.getCode(), d.getName(), joinPath(links),
                d.isEnabled(), d.isEnabled() && d.getProvince().isEnabled(), d.isAllowEdit(), descendants, links);
    }

    private Row row(Sector s) {
        List<PathLink> links = List.of(provinceLink(s.getDistrict().getProvince()),
                districtLink(s.getDistrict()), sectorLink(s));
        long descendants = cellRepo.countByCodeStartingWith(s.getCode())
                + villageRepo.countByCodeStartingWith(s.getCode());
        return new Row(AdministrativeTier.SECTOR, s.getId(), s.getCode(), s.getName(), joinPath(links),
                s.isEnabled(), s.isEnabled() && s.getDistrict().isEnabled() && s.getDistrict().getProvince().isEnabled(),
                s.isAllowEdit(), descendants, links);
    }

    private Row row(Cell c) {
        List<PathLink> links = List.of(provinceLink(c.getSector().getDistrict().getProvince()),
                districtLink(c.getSector().getDistrict()), sectorLink(c.getSector()), cellLink(c));
        long descendants = villageRepo.countByCodeStartingWith(c.getCode());
        return new Row(AdministrativeTier.CELL, c.getId(), c.getCode(), c.getName(), joinPath(links),
                c.isEnabled(), c.isEnabled() && c.getSector().isEnabled() && c.getSector().getDistrict().isEnabled()
                && c.getSector().getDistrict().getProvince().isEnabled(), c.isAllowEdit(), descendants, links);
    }

    private Row row(Village v) {
        List<PathLink> links = List.of(provinceLink(v.getCell().getSector().getDistrict().getProvince()),
                districtLink(v.getCell().getSector().getDistrict()), sectorLink(v.getCell().getSector()),
                cellLink(v.getCell()), villageLink(v));
        return new Row(AdministrativeTier.VILLAGE, v.getId(), v.getCode(), v.getName(), joinPath(links),
                v.isEnabled(), v.isEnabled() && v.getCell().isEnabled() && v.getCell().getSector().isEnabled()
                && v.getCell().getSector().getDistrict().isEnabled()
                && v.getCell().getSector().getDistrict().getProvince().isEnabled(), v.isAllowEdit(), 0, links);
    }

    private static PathLink provinceLink(Province p) {
        return new PathLink(p.getName(), AdministrativeTier.DISTRICT, p.getId(), null, null, null, null);
    }

    private static PathLink districtLink(District d) {
        return new PathLink(d.getName(), AdministrativeTier.SECTOR, d.getProvince().getId(), d.getId(),
                null, null, null);
    }

    private static PathLink sectorLink(Sector s) {
        District d = s.getDistrict();
        return new PathLink(s.getName(), AdministrativeTier.CELL, d.getProvince().getId(), d.getId(),
                s.getId(), null, null);
    }

    private static PathLink cellLink(Cell c) {
        Sector s = c.getSector();
        return new PathLink(c.getName(), AdministrativeTier.VILLAGE, s.getDistrict().getProvince().getId(),
                s.getDistrict().getId(), s.getId(), c.getId(), null);
    }

    private static PathLink villageLink(Village v) {
        Cell c = v.getCell();
        return new PathLink(v.getName(), AdministrativeTier.VILLAGE, c.getSector().getDistrict().getProvince().getId(),
                c.getSector().getDistrict().getId(), c.getSector().getId(), c.getId(), v.getCode());
    }

    private static String joinPath(List<PathLink> links) {
        return String.join(PATH_SEPARATOR, links.stream().map(PathLink::name).toList());
    }

    // ---------------------------------------------------------------- commands

    @Transactional
    public RenameResult rename(AdministrativeTier tier, UUID id, String rawName) {
        AdministrativeNode node = requireNode(tier, id);
        requireEditable(node);
        String after = name(rawName);
        String before = node.getName();
        node.setName(after);
        return new RenameResult(tier, node.getCode(), before, after);
    }

    @Transactional
    public ToggleResult toggle(AdministrativeTier tier, UUID id) {
        AdministrativeNode node = requireNode(tier, id);
        boolean enable = !node.isEnabled();
        if (enable) {
            String blocked = disabledAncestor(tier, node);
            if (blocked != null) {
                throw BusinessException.of("hierarchy.toggle.parentDisabled", node.getCode(), blocked);
            }
        }
        node.setEnabled(enable);
        return new ToggleResult(tier, node.getCode(), node.getName(), enable);
    }

    @Transactional
    public DeleteResult delete(AdministrativeTier tier, UUID id, String confirmCode) {
        AdministrativeNode root = requireNode(tier, id);
        requireEditable(root);
        String typed = confirmCode == null ? "" : confirmCode.trim();
        if (!root.getCode().equalsIgnoreCase(typed)) {
            throw BusinessException.onField("confirmCode", "hierarchy.delete.codeMismatch", root.getCode());
        }

        List<District> districts = List.of();
        List<Sector> sectors = List.of();
        List<Cell> cells = List.of();
        List<Village> villages = List.of();
        switch (tier) {
            case PROVINCE -> {
                districts = districtRepo.findByCodeStartingWithOrderByCodeAsc(root.getCode());
                sectors = sectorRepo.findByCodeStartingWithOrderByCodeAsc(root.getCode());
                cells = cellRepo.findByCodeStartingWithOrderByCodeAsc(root.getCode());
                villages = villageRepo.findByCodeStartingWithOrderByCodeAsc(root.getCode());
            }
            case DISTRICT -> {
                sectors = sectorRepo.findByCodeStartingWithOrderByCodeAsc(root.getCode());
                cells = cellRepo.findByCodeStartingWithOrderByCodeAsc(root.getCode());
                villages = villageRepo.findByCodeStartingWithOrderByCodeAsc(root.getCode());
            }
            case SECTOR -> {
                cells = cellRepo.findByCodeStartingWithOrderByCodeAsc(root.getCode());
                villages = villageRepo.findByCodeStartingWithOrderByCodeAsc(root.getCode());
            }
            case CELL -> villages = villageRepo.findByCodeStartingWithOrderByCodeAsc(root.getCode());
            case VILLAGE -> {
            }
        }
        requireUnlocked(districts);
        requireUnlocked(sectors);
        requireUnlocked(cells);
        requireUnlocked(villages);

        long total = 1L + districts.size() + sectors.size() + cells.size() + villages.size();
        // Bottom-up, each delete through JPA so the change log keeps every removed row.
        villageRepo.deleteAll(villages);
        villageRepo.flush();
        cellRepo.deleteAll(cells);
        cellRepo.flush();
        sectorRepo.deleteAll(sectors);
        sectorRepo.flush();
        districtRepo.deleteAll(districts);
        districtRepo.flush();
        deleteRoot(tier, root);
        return new DeleteResult(tier, root.getCode(), root.getName(), total);
    }

    // ---------------------------------------------------------------- rules

    private AdministrativeNode requireNode(AdministrativeTier tier, UUID id) {
        if (tier == null || id == null) {
            throw new NotFoundException("Administrative unit", id);
        }
        return switch (tier) {
            case PROVINCE -> provinceRepo.findById(id).orElseThrow(() -> new NotFoundException("Province", id));
            case DISTRICT -> districtRepo.findById(id).orElseThrow(() -> new NotFoundException("District", id));
            case SECTOR -> sectorRepo.findById(id).orElseThrow(() -> new NotFoundException("Sector", id));
            case CELL -> cellRepo.findById(id).orElseThrow(() -> new NotFoundException("Cell", id));
            case VILLAGE -> villageRepo.findById(id).orElseThrow(() -> new NotFoundException("Village", id));
        };
    }

    private void requireEditable(AdministrativeNode node) {
        if (!node.isAllowEdit()) {
            throw BusinessException.of("hierarchy.error.locked", node.getCode());
        }
    }

    private void requireUnlocked(List<? extends AdministrativeNode> nodes) {
        for (AdministrativeNode node : nodes) {
            if (!node.isAllowEdit()) {
                throw BusinessException.of("hierarchy.delete.lockedDescendant", node.getCode());
            }
        }
    }

    private String disabledAncestor(AdministrativeTier tier, AdministrativeNode node) {
        return switch (tier) {
            case PROVINCE -> null;
            case DISTRICT -> {
                Province p = ((District) node).getProvince();
                yield p.isEnabled() ? null : p.getCode();
            }
            case SECTOR -> {
                District d = ((Sector) node).getDistrict();
                yield !d.getProvince().isEnabled() ? d.getProvince().getCode()
                        : !d.isEnabled() ? d.getCode() : null;
            }
            case CELL -> {
                Sector s = ((Cell) node).getSector();
                yield !s.getDistrict().getProvince().isEnabled() ? s.getDistrict().getProvince().getCode()
                        : !s.getDistrict().isEnabled() ? s.getDistrict().getCode()
                        : !s.isEnabled() ? s.getCode() : null;
            }
            case VILLAGE -> {
                Cell c = ((Village) node).getCell();
                yield !c.getSector().getDistrict().getProvince().isEnabled() ? c.getSector().getDistrict().getProvince().getCode()
                        : !c.getSector().getDistrict().isEnabled() ? c.getSector().getDistrict().getCode()
                        : !c.getSector().isEnabled() ? c.getSector().getCode()
                        : !c.isEnabled() ? c.getCode() : null;
            }
        };
    }

    private void deleteRoot(AdministrativeTier tier, AdministrativeNode node) {
        switch (tier) {
            case PROVINCE -> provinceRepo.delete((Province) node);
            case DISTRICT -> districtRepo.delete((District) node);
            case SECTOR -> sectorRepo.delete((Sector) node);
            case CELL -> cellRepo.delete((Cell) node);
            case VILLAGE -> villageRepo.delete((Village) node);
        }
        switch (tier) {
            case PROVINCE -> provinceRepo.flush();
            case DISTRICT -> districtRepo.flush();
            case SECTOR -> sectorRepo.flush();
            case CELL -> cellRepo.flush();
            case VILLAGE -> villageRepo.flush();
        }
    }

    private String name(String raw) {
        String value = raw == null ? null : raw.trim().replaceAll("\\s+", " ");
        if (!StringUtils.hasText(value)) {
            throw BusinessException.onField("name", "hierarchy.name.required");
        }
        if (value.length() > NAME_MAX) {
            throw BusinessException.onField("name", "hierarchy.name.tooLong", NAME_MAX);
        }
        return value;
    }

    private static String text(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String value = raw.trim().replaceAll("\\s+", " ");
        return value.length() > NAME_MAX ? value.substring(0, NAME_MAX) : value;
    }

    private static String status(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String value = raw.trim().toUpperCase();
        return STATUSES.contains(value) ? value : null;
    }

    private static String queryString(AdministrativeTier tier, String q, String status, UUID provinceId,
                                      UUID districtId, UUID sectorId, UUID cellId) {
        StringBuilder query = new StringBuilder("tier=").append(tier.name());
        append(query, "q", q);
        append(query, "status", status);
        append(query, "province", provinceId);
        append(query, "district", districtId);
        append(query, "sector", sectorId);
        append(query, "cell", cellId);
        return query.toString();
    }

    private static void append(StringBuilder query, String name, Object value) {
        if (value == null || value.toString().isBlank()) {
            return;
        }
        query.append('&').append(name).append('=').append(URLEncoder.encode(value.toString(), StandardCharsets.UTF_8));
    }
}
