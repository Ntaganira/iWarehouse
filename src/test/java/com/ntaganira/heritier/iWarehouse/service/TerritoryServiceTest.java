package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.Cell;
import com.ntaganira.heritier.iWarehouse.entity.District;
import com.ntaganira.heritier.iWarehouse.entity.Province;
import com.ntaganira.heritier.iWarehouse.entity.Sector;
import com.ntaganira.heritier.iWarehouse.entity.Territory;
import com.ntaganira.heritier.iWarehouse.entity.Village;
import com.ntaganira.heritier.iWarehouse.enums.AdministrativeTier;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.repository.CellRepository;
import com.ntaganira.heritier.iWarehouse.repository.DistrictRepository;
import com.ntaganira.heritier.iWarehouse.repository.ProvinceRepository;
import com.ntaganira.heritier.iWarehouse.repository.SectorRepository;
import com.ntaganira.heritier.iWarehouse.repository.TerritoryRepository;
import com.ntaganira.heritier.iWarehouse.repository.UserRepository;
import com.ntaganira.heritier.iWarehouse.repository.VillageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : TerritoryServiceTest.java
 * - Date      : 2026. 11. 02.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The territory mapping rules: the deepest selected unit fixes the chain, one territory per unit,
 *               disabled units are not newly mapped, and a legacy territory may stay on the unit it already covers.
 * </pre>
 */
class TerritoryServiceTest {

    private TerritoryRepository territories;
    private UserRepository users;
    private ProvinceRepository provinces;
    private DistrictRepository districts;
    private SectorRepository sectors;
    private CellRepository cells;
    private VillageRepository villages;
    private TerritoryService service;

    @BeforeEach
    void setUp() {
        territories = mock(TerritoryRepository.class);
        users = mock(UserRepository.class);
        provinces = mock(ProvinceRepository.class);
        districts = mock(DistrictRepository.class);
        sectors = mock(SectorRepository.class);
        cells = mock(CellRepository.class);
        villages = mock(VillageRepository.class);
        service = new TerritoryService(territories, users, provinces, districts, sectors, cells, villages);

        when(territories.findByCode(anyString())).thenReturn(Optional.empty());
        when(territories.findByProvinceId(any())).thenReturn(Optional.empty());
        when(territories.findByDistrictId(any())).thenReturn(Optional.empty());
        when(territories.findBySectorId(any())).thenReturn(Optional.empty());
        when(territories.findByCellId(any())).thenReturn(Optional.empty());
        when(territories.findByVillageId(any())).thenReturn(Optional.empty());
        when(territories.save(any(Territory.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(provinces.findById(any())).thenReturn(Optional.empty());
        when(districts.findById(any())).thenReturn(Optional.empty());
        when(sectors.findById(any())).thenReturn(Optional.empty());
        when(cells.findById(any())).thenReturn(Optional.empty());
        when(villages.findById(any())).thenReturn(Optional.empty());
    }

    @Test
    void aDistrictTerritoryKeepsTheProvinceAndDropsTheLowerLevels() {
        Province province = province("01", "Kigali City", true);
        District district = district(province, "0101", "Gasabo", true);
        Sector sector = sector(district, "010101", "Kacyiru", true);
        Cell cell = cell(sector, "01010101", "Kamatamu", true);
        Village village = village(cell, "0101010101", "Amahoro", true);
        when(districts.findById(district.getId())).thenReturn(Optional.of(district));
        when(sectors.findById(sector.getId())).thenReturn(Optional.of(sector));
        when(cells.findById(cell.getId())).thenReturn(Optional.of(cell));
        when(villages.findById(village.getId())).thenReturn(Optional.of(village));

        Territory t = service.create(new TerritoryService.Form("KGL", "Kigali", null, AdministrativeTier.DISTRICT,
                province.getId(), district.getId(), sector.getId(), cell.getId(), village.getId()));

        assertThat(t.getTier()).isEqualTo(AdministrativeTier.DISTRICT);
        assertThat(t.getProvince()).isSameAs(province);
        assertThat(t.getDistrict()).isSameAs(district);
        assertThat(t.getSector()).isNull();
        assertThat(t.getCell()).isNull();
        assertThat(t.getVillage()).isNull();
        assertThat(t.getAreaPath()).isEqualTo("Kigali City / Gasabo");
        assertThat(t.getAreaCode()).isEqualTo("0101");
    }

    @Test
    void aVillageTerritoryKeepsTheWholeChain() {
        Province province = province("01", "Kigali City", true);
        District district = district(province, "0101", "Gasabo", true);
        Sector sector = sector(district, "010101", "Kacyiru", true);
        Cell cell = cell(sector, "01010101", "Kamatamu", true);
        Village village = village(cell, "0101010101", "Amahoro", true);
        when(villages.findById(village.getId())).thenReturn(Optional.of(village));

        Territory t = service.create(new TerritoryService.Form("AMH", "Amahoro", null,
                AdministrativeTier.VILLAGE, null, null, null, null, village.getId()));

        assertThat(t.getTier()).isEqualTo(AdministrativeTier.VILLAGE);
        assertThat(t.getProvince()).isSameAs(province);
        assertThat(t.getDistrict()).isSameAs(district);
        assertThat(t.getSector()).isSameAs(sector);
        assertThat(t.getCell()).isSameAs(cell);
        assertThat(t.getVillage()).isSameAs(village);
        assertThat(t.getAreaPath()).isEqualTo("Kigali City / Gasabo / Kacyiru / Kamatamu / Amahoro");
        assertThat(t.getAreaCode()).isEqualTo("0101010101");
    }

    @Test
    void aTerritoryMustHaveALevel() {
        assertThatThrownBy(() -> service.create(new TerritoryService.Form("KGL", "Kigali", null, null,
                null, null, null, null, null)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getField()).isEqualTo("tier");
                    assertThat(e.getMessageKey()).isEqualTo("territory.tier.required");
                });
    }

    @Test
    void aMissingParentIsRefusedUnderItsOwnField() {
        Province province = province("01", "Kigali City", true);
        when(provinces.findById(province.getId())).thenReturn(Optional.of(province));

        assertThatThrownBy(() -> service.create(new TerritoryService.Form("KGL", "Kigali", null,
                AdministrativeTier.DISTRICT, province.getId(), null, null, null, null)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getField()).isEqualTo("districtId");
                    assertThat(e.getMessageKey()).isEqualTo("territory.area.required");
                });
    }

    @Test
    void aDuplicateAdministrativeUnitIsRefused() {
        Province province = province("01", "Kigali City", true);
        District district = district(province, "0101", "Gasabo", true);
        Territory existing = new Territory();
        existing.setId(UUID.randomUUID());
        existing.setCode("GAS");
        when(districts.findById(district.getId())).thenReturn(Optional.of(district));
        when(territories.findByDistrictId(district.getId())).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.create(new TerritoryService.Form("KGL", "Kigali", null,
                AdministrativeTier.DISTRICT, province.getId(), district.getId(), null, null, null)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getField()).isEqualTo("districtId");
                    assertThat(e.getMessageKey()).isEqualTo("territory.area.taken");
                    assertThat(e.getArgs()).containsExactly("GAS");
                });
    }

    @Test
    void aDisabledUnitIsNotNewlyMapped() {
        Province province = province("01", "Kigali City", true);
        District district = district(province, "0101", "Gasabo", false);
        when(districts.findById(district.getId())).thenReturn(Optional.of(district));

        assertThatThrownBy(() -> service.create(new TerritoryService.Form("KGL", "Kigali", null,
                AdministrativeTier.DISTRICT, province.getId(), district.getId(), null, null, null)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getField()).isEqualTo("districtId");
                    assertThat(e.getMessageKey()).isEqualTo("territory.area.disabled");
                });
    }

    @Test
    void aTerritoryAlreadyOnADisabledUnitMayKeepIt() {
        Province province = province("01", "Kigali City", true);
        District district = district(province, "0101", "Gasabo", false);
        Territory current = new Territory();
        current.setId(UUID.randomUUID());
        current.setCode("KGL");
        current.setName("Kigali");
        current.setTier(AdministrativeTier.DISTRICT);
        current.setProvince(province);
        current.setDistrict(district);
        when(districts.findById(district.getId())).thenReturn(Optional.of(district));
        when(territories.findWithAreaById(current.getId())).thenReturn(Optional.of(current));
        when(territories.findByCode("KGL")).thenReturn(Optional.of(current));
        when(territories.findByDistrictId(district.getId())).thenReturn(Optional.of(current));

        service.update(current.getId(), new TerritoryService.Form("KGL", "Kigali City", null,
                AdministrativeTier.DISTRICT, province.getId(), district.getId(), null, null, null));

        assertThat(current.getName()).isEqualTo("Kigali City");
        assertThat(current.getDistrict()).isSameAs(district);
    }

    @Test
    void optionsKeepTheCurrentDisabledUnitButNotOtherDisabledOnes() {
        Province province = province("01", "Kigali City", true);
        District current = district(province, "0101", "Gasabo", false);
        District other = district(province, "0102", "Kicukiro", false);
        when(provinces.findAllByOrderByCodeAsc()).thenReturn(List.of(province));
        when(districts.findByProvinceIdOrderByCodeAsc(province.getId())).thenReturn(List.of(current, other));
        when(districts.findById(current.getId())).thenReturn(Optional.of(current));

        TerritoryService.Options options = service.options(new TerritoryService.Form(null, null, null,
                AdministrativeTier.DISTRICT, province.getId(), current.getId(), null, null, null));

        assertThat(options.provinces()).containsExactly(province);
        assertThat(options.districts()).containsExactly(current);
    }

    @Test
    void childrenOfferOnlyEnabledUnits() {
        Province province = province("01", "Kigali City", true);
        District enabled = district(province, "0101", "Gasabo", true);
        District disabled = district(province, "0102", "Kicukiro", false);
        when(districts.findByProvinceIdOrderByCodeAsc(province.getId())).thenReturn(List.of(enabled, disabled));

        List<TerritoryService.AreaOption> children = service.children(AdministrativeTier.DISTRICT, province.getId());

        assertThat(children).extracting(TerritoryService.AreaOption::code).containsExactly("0101");
        assertThat(children.get(0).getLabel()).isEqualTo("0101 · Gasabo");
    }

    private static Province province(String code, String name, boolean enabled) {
        Province p = new Province();
        p.setId(UUID.randomUUID());
        p.setCode(code);
        p.setName(name);
        p.setEnabled(enabled);
        return p;
    }

    private static District district(Province province, String code, String name, boolean enabled) {
        District d = new District();
        d.setId(UUID.randomUUID());
        d.setProvince(province);
        d.setCode(code);
        d.setName(name);
        d.setEnabled(enabled);
        return d;
    }

    private static Sector sector(District district, String code, String name, boolean enabled) {
        Sector s = new Sector();
        s.setId(UUID.randomUUID());
        s.setDistrict(district);
        s.setCode(code);
        s.setName(name);
        s.setEnabled(enabled);
        return s;
    }

    private static Cell cell(Sector sector, String code, String name, boolean enabled) {
        Cell c = new Cell();
        c.setId(UUID.randomUUID());
        c.setSector(sector);
        c.setCode(code);
        c.setName(name);
        c.setEnabled(enabled);
        return c;
    }

    private static Village village(Cell cell, String code, String name, boolean enabled) {
        Village v = new Village();
        v.setId(UUID.randomUUID());
        v.setCell(cell);
        v.setCode(code);
        v.setName(name);
        v.setEnabled(enabled);
        return v;
    }
}
