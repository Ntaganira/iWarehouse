package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.dto.SettingsDto;
import com.ntaganira.heritier.iWarehouse.dto.TaxCategoryDto;
import com.ntaganira.heritier.iWarehouse.entity.Setting;
import com.ntaganira.heritier.iWarehouse.entity.TaxCategory;
import com.ntaganira.heritier.iWarehouse.enums.SettingKey;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.repository.NumberSequenceRepository;
import com.ntaganira.heritier.iWarehouse.repository.SettingRepository;
import com.ntaganira.heritier.iWarehouse.repository.TaxCategoryRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Settings values (ADM-03) and the tax category default rules (TAX-01). */
class SettingsRulesTest {

    // --- SettingService ---

    @Test
    void numbersAreStoredWithoutTrailingZeros() {
        assertThat(SettingService.normalize(new BigDecimal("0.2500"))).isEqualTo("0.25");
        assertThat(SettingService.normalize(new BigDecimal("100"))).isEqualTo("100");
        assertThat(SettingService.normalize(new BigDecimal("0.00"))).isEqualTo("0");
        assertThat(SettingService.normalize("  ")).isNull();
        assertThat(SettingService.normalize(300)).isEqualTo("300");
    }

    @Test
    void onlyChangedValuesAreSavedAndListed() {
        Map<SettingKey, Setting> rows = seededRows();
        SettingService service = settingService(rows);
        SettingsDto dto = service.load();
        dto.setOffcutMinArea(new BigDecimal("0.300"));
        dto.setCompanyTin("123 456 789");

        List<String> changes = service.update(dto);

        assertThat(changes).containsExactly(
                "company.tin: (empty) -> 123456789",
                "production.offcut.min-area: 0.25 -> 0.3");
        assertThat(rows.get(SettingKey.OFFCUT_MIN_AREA).getSettingValue()).isEqualTo("0.3");
        assertThat(rows.get(SettingKey.GLASS_DENSITY).getSettingValue()).isEqualTo("2.5");
    }

    @Test
    void branchWithoutSequencesIsRefused() {
        SettingService service = settingService(seededRows());
        SettingsDto dto = service.load();
        dto.setBranchCode("v02");

        assertThatThrownBy(() -> service.update(dto))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getField()).isEqualTo("branchCode");
                    assertThat(e.getArgs()).containsExactly("V02");
                });
    }

    @Test
    void unreadableNumberFallsBackToTheDefault() {
        SettingRepository repo = mock(SettingRepository.class);
        Setting broken = new Setting();
        broken.setSettingKey(SettingKey.GLASS_DENSITY.key());
        broken.setSettingValue("abc");
        when(repo.findBySettingKey(SettingKey.GLASS_DENSITY.key())).thenReturn(Optional.of(broken));
        SettingService service = new SettingService(repo, mock(NumberSequenceRepository.class));

        assertThat(service.getDecimal(SettingKey.GLASS_DENSITY)).isEqualByComparingTo("2.5");
        assertThat(service.getInt(SettingKey.OFFCUT_MIN_SIDE)).isEqualTo(300); // no row: default
    }

    // --- TaxCategoryService ---

    @Test
    void defaultCategoryStaysActive() {
        TaxCategoryRepository repo = mock(TaxCategoryRepository.class);
        TaxCategory standard = category("STANDARD", true);
        when(repo.findById(standard.getId())).thenReturn(Optional.of(standard));

        assertThatThrownBy(() -> new TaxCategoryService(repo).setEnabled(standard.getId(), false))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessageKey()).isEqualTo("tax.default.disable"));
        assertThat(standard.isEnabled()).isTrue();
    }

    @Test
    void defaultFlagCannotJustBeRemoved() {
        TaxCategoryRepository repo = mock(TaxCategoryRepository.class);
        TaxCategory standard = category("STANDARD", true);
        when(repo.findById(standard.getId())).thenReturn(Optional.of(standard));

        assertThatThrownBy(() -> new TaxCategoryService(repo).update(standard.getId(), dto("STANDARD", false)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getField()).isEqualTo("defaultCategory"));
    }

    @Test
    void newDefaultClearsTheOldOneFirst() {
        TaxCategoryRepository repo = mock(TaxCategoryRepository.class);
        TaxCategory standard = category("STANDARD", true);
        when(repo.findByDefaultCategoryTrue()).thenReturn(Optional.of(standard));
        when(repo.save(any(TaxCategory.class))).thenAnswer(i -> i.getArgument(0));

        TaxCategory reduced = new TaxCategoryService(repo).create(dto("REDUCED", true));

        assertThat(reduced.isDefaultCategory()).isTrue();
        assertThat(standard.isDefaultCategory()).isFalse();
        InOrder order = inOrder(repo);
        order.verify(repo).saveAndFlush(standard); // the unique index allows one default at a time
        order.verify(repo).save(reduced);
    }

    @Test
    void duplicateCodeIsReportedOnTheField() {
        TaxCategoryRepository repo = mock(TaxCategoryRepository.class);
        when(repo.existsByCodeIgnoreCase("ZERO")).thenReturn(true);

        assertThatThrownBy(() -> new TaxCategoryService(repo).create(dto("zero", false)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getField()).isEqualTo("code");
                    assertThat(e.getMessageKey()).isEqualTo("tax.code.taken");
                });
    }

    // --- helpers ---

    /** Rows as seeded by V5, keyed by setting. */
    private static Map<SettingKey, Setting> seededRows() {
        Map<SettingKey, Setting> rows = new EnumMap<>(SettingKey.class);
        for (SettingKey key : SettingKey.values()) {
            Setting row = new Setting();
            row.setSettingKey(key.key());
            row.setSettingValue(key.defaultValue());
            rows.put(key, row);
        }
        return rows;
    }

    private static SettingService settingService(Map<SettingKey, Setting> rows) {
        SettingRepository repo = mock(SettingRepository.class);
        when(repo.findAll()).thenReturn(new ArrayList<>(rows.values()));
        NumberSequenceRepository sequences = mock(NumberSequenceRepository.class);
        when(sequences.existsByBranchCode("WH")).thenReturn(true);
        return new SettingService(repo, sequences);
    }

    private static TaxCategory category(String code, boolean isDefault) {
        TaxCategory c = new TaxCategory();
        c.setId(UUID.randomUUID());
        c.setCode(code);
        c.setName(code);
        c.setRate(new BigDecimal("18.00"));
        c.setEbmCode("B");
        c.setDefaultCategory(isDefault);
        return c;
    }

    private static TaxCategoryDto dto(String code, boolean isDefault) {
        TaxCategoryDto dto = new TaxCategoryDto();
        dto.setCode(code);
        dto.setName("Reduced rate");
        dto.setRate(new BigDecimal("10"));
        dto.setEbmCode("b");
        dto.setDefaultCategory(isDefault);
        return dto;
    }
}
