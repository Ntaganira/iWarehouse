package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.Cell;
import com.ntaganira.heritier.iWarehouse.entity.District;
import com.ntaganira.heritier.iWarehouse.entity.Province;
import com.ntaganira.heritier.iWarehouse.entity.Sector;
import com.ntaganira.heritier.iWarehouse.entity.Village;
import com.ntaganira.heritier.iWarehouse.enums.AdministrativeTier;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.repository.CellRepository;
import com.ntaganira.heritier.iWarehouse.repository.DistrictRepository;
import com.ntaganira.heritier.iWarehouse.repository.ProvinceRepository;
import com.ntaganira.heritier.iWarehouse.repository.SectorRepository;
import com.ntaganira.heritier.iWarehouse.repository.VillageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : HierarchyServiceTest.java
 * - Date      : 2026. 11. 02.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The hierarchy dashboard's rules: effective visibility, empty filters that must not hide every
 *               row, a parent filter that completes the chain, rename validation and the allow_edit lock.
 * </pre>
 */
class HierarchyServiceTest {

    private ProvinceRepository provinceRepo;
    private DistrictRepository districtRepo;
    private SectorRepository sectorRepo;
    private CellRepository cellRepo;
    private VillageRepository villageRepo;
    private HierarchyService service;

    @BeforeEach
    void setUp() {
        provinceRepo = mock(ProvinceRepository.class);
        districtRepo = mock(DistrictRepository.class);
        sectorRepo = mock(SectorRepository.class);
        cellRepo = mock(CellRepository.class);
        villageRepo = mock(VillageRepository.class);
        when(provinceRepo.findAllByOrderByCodeAsc()).thenReturn(List.of());
        when(villageRepo.search(anyString(), anyString(), any(), any(Pageable.class))).thenReturn(Page.empty());
        when(cellRepo.search(anyString(), anyString(), any(), any(Pageable.class))).thenReturn(Page.empty());
        service = new HierarchyService(provinceRepo, districtRepo, sectorRepo, cellRepo, villageRepo);
    }

    @Test
    void aRowIsVisibleOnlyWhenItAndEveryAncestorAreEnabled() {
        HierarchyService.Row visible = new HierarchyService.Row(
                AdministrativeTier.VILLAGE, UUID.randomUUID(), "01010101", "Village", "Path",
                true, true, true, 0, List.of());
        HierarchyService.Row hidden = new HierarchyService.Row(
                AdministrativeTier.VILLAGE, UUID.randomUUID(), "01010102", "Village", "Path",
                true, false, true, 0, List.of());
        HierarchyService.Row inactive = new HierarchyService.Row(
                AdministrativeTier.VILLAGE, UUID.randomUUID(), "01010103", "Village", "Path",
                false, false, true, 0, List.of());

        assertThat(visible.getStatusKey()).isEqualTo("VISIBLE");
        assertThat(visible.isHiddenByParent()).isFalse();
        assertThat(hidden.getStatusKey()).isEqualTo("HIDDEN");
        assertThat(hidden.isHiddenByParent()).isTrue();
        assertThat(inactive.getStatusKey()).isEqualTo("INACTIVE");
        assertThat(inactive.isHiddenByParent()).isFalse();
    }

    @Test
    void anEmptyStatusAndSearchMeanNoFilterNotAnEmptyResult() {
        HierarchyService.Dashboard dashboard = service.dashboard(new HierarchyService.Filter(
                AdministrativeTier.VILLAGE, "   ", "not-a-status", null, null, null, null, 0));

        assertThat(dashboard.q()).isNull();
        assertThat(dashboard.status()).isNull();
        verify(villageRepo).search(eq(""), eq(""), isNull(), any(Pageable.class));
    }

    @Test
    void aLowerFilterCompletesTheChainAboveIt() {
        Province province = province("01", true, true);
        District district = district(province, "0101", true, true);
        Sector sector = sector(district, "010101", true, true);
        Cell cell = cell(sector, "01010101", true, true);
        when(cellRepo.findById(cell.getId())).thenReturn(Optional.of(cell));

        HierarchyService.Dashboard dashboard = service.dashboard(new HierarchyService.Filter(
                AdministrativeTier.CELL, null, null, null, null, null, cell.getId(), 0));

        assertThat(dashboard.provinceId()).isEqualTo(province.getId());
        assertThat(dashboard.districtId()).isEqualTo(district.getId());
        assertThat(dashboard.sectorId()).isEqualTo(sector.getId());
        assertThat(dashboard.cellId()).isEqualTo(cell.getId());
        verify(cellRepo).search(eq(""), eq(""), eq(sector.getId()), any(Pageable.class));
    }

    @Test
    void enablingAUnitUnderADisabledParentIsRefused() {
        Province province = province("01", false, true);
        District district = district(province, "0101", false, true);
        when(districtRepo.findById(district.getId())).thenReturn(Optional.of(district));

        assertThatThrownBy(() -> service.toggle(AdministrativeTier.DISTRICT, district.getId()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("messageKey", "hierarchy.toggle.parentDisabled");
    }

    @Test
    void aLockedRowIsNeitherRenamedNorDeletedButMayBeHidden() {
        Province province = province("01", true, false);
        when(provinceRepo.findById(province.getId())).thenReturn(Optional.of(province));

        assertThatThrownBy(() -> service.rename(AdministrativeTier.PROVINCE, province.getId(), "New name"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("messageKey", "hierarchy.error.locked");
        assertThatThrownBy(() -> service.delete(AdministrativeTier.PROVINCE, province.getId(), "01"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("messageKey", "hierarchy.error.locked");

        HierarchyService.ToggleResult result = service.toggle(AdministrativeTier.PROVINCE, province.getId());
        assertThat(result.enabled()).isFalse();
        assertThat(province.isEnabled()).isFalse();
    }

    @Test
    void aRenameIsTrimmedAndCollapsed() {
        Province province = province("01", true, true);
        province.setName("Kigali City");
        when(provinceRepo.findById(province.getId())).thenReturn(Optional.of(province));

        HierarchyService.RenameResult result = service.rename(
                AdministrativeTier.PROVINCE, province.getId(), "  Kigali   Ville  ");

        assertThat(result.before()).isEqualTo("Kigali City");
        assertThat(result.after()).isEqualTo("Kigali Ville");
        assertThat(province.getName()).isEqualTo("Kigali Ville");
    }

    @Test
    void aBlankOrTooLongNameIsRefused() {
        Province province = province("01", true, true);
        when(provinceRepo.findById(province.getId())).thenReturn(Optional.of(province));

        assertThatThrownBy(() -> service.rename(AdministrativeTier.PROVINCE, province.getId(), "   "))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("messageKey", "hierarchy.name.required");
        assertThatThrownBy(() -> service.rename(AdministrativeTier.PROVINCE, province.getId(), "x".repeat(101)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("messageKey", "hierarchy.name.tooLong");
    }

    @Test
    void aDeleteNeedsTheTypedCode() {
        Province province = province("01", true, true);
        when(provinceRepo.findById(province.getId())).thenReturn(Optional.of(province));

        assertThatThrownBy(() -> service.delete(AdministrativeTier.PROVINCE, province.getId(), "02"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("messageKey", "hierarchy.delete.codeMismatch");
    }

    @Test
    void aBranchWithALockedDescendantIsNeverDeleted() {
        Cell cell = cell(sector(district(province("01", true, true), "0101", true, true),
                "010101", true, true), "01010101", true, true);
        Village locked = village(cell, "0101010101", true, false);
        when(cellRepo.findById(cell.getId())).thenReturn(Optional.of(cell));
        when(villageRepo.findByCodeStartingWithOrderByCodeAsc(cell.getCode())).thenReturn(List.of(locked));

        assertThatThrownBy(() -> service.delete(AdministrativeTier.CELL, cell.getId(), cell.getCode()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("messageKey", "hierarchy.delete.lockedDescendant");
    }

    @Test
    void aDeleteRemovesTheWholeBranchBottomUp() {
        Cell cell = cell(sector(district(province("01", true, true), "0101", true, true),
                "010101", true, true), "01010101", true, true);
        Village first = village(cell, "0101010101", true, true);
        Village second = village(cell, "0101010102", true, true);
        when(cellRepo.findById(cell.getId())).thenReturn(Optional.of(cell));
        when(villageRepo.findByCodeStartingWithOrderByCodeAsc(cell.getCode())).thenReturn(List.of(first, second));

        HierarchyService.DeleteResult result = service.delete(
                AdministrativeTier.CELL, cell.getId(), cell.getCode());

        assertThat(result.total()).isEqualTo(3);
        assertThat(result.descendants()).isEqualTo(2);
        verify(villageRepo).deleteAll(List.of(first, second));
        verify(cellRepo).delete(cell);
    }

    private static Province province(String code, boolean enabled, boolean allowEdit) {
        Province province = new Province();
        province.setId(UUID.randomUUID());
        province.setCode(code);
        province.setName("Province " + code);
        province.setEnabled(enabled);
        province.setAllowEdit(allowEdit);
        return province;
    }

    private static District district(Province province, String code, boolean enabled, boolean allowEdit) {
        District district = new District();
        district.setId(UUID.randomUUID());
        district.setProvince(province);
        district.setCode(code);
        district.setName("District " + code);
        district.setEnabled(enabled);
        district.setAllowEdit(allowEdit);
        return district;
    }

    private static Sector sector(District district, String code, boolean enabled, boolean allowEdit) {
        Sector sector = new Sector();
        sector.setId(UUID.randomUUID());
        sector.setDistrict(district);
        sector.setCode(code);
        sector.setName("Sector " + code);
        sector.setEnabled(enabled);
        sector.setAllowEdit(allowEdit);
        return sector;
    }

    private static Cell cell(Sector sector, String code, boolean enabled, boolean allowEdit) {
        Cell cell = new Cell();
        cell.setId(UUID.randomUUID());
        cell.setSector(sector);
        cell.setCode(code);
        cell.setName("Cell " + code);
        cell.setEnabled(enabled);
        cell.setAllowEdit(allowEdit);
        return cell;
    }

    private static Village village(Cell cell, String code, boolean enabled, boolean allowEdit) {
        Village village = new Village();
        village.setId(UUID.randomUUID());
        village.setCell(cell);
        village.setCode(code);
        village.setName("Village " + code);
        village.setEnabled(enabled);
        village.setAllowEdit(allowEdit);
        return village;
    }
}
