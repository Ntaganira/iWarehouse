package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.dto.SettingsDto;
import com.ntaganira.heritier.iWarehouse.entity.Setting;
import com.ntaganira.heritier.iWarehouse.enums.SettingKey;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.repository.NumberSequenceRepository;
import com.ntaganira.heritier.iWarehouse.repository.SettingRepository;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.PropertyAccessorFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : SettingService.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : Configurable settings (ADM-03): typed reads for other modules (off-cut threshold,
 *               glass density, approval limits...) and the settings form. Each changed value is saved
 *               through JPA, so the change log keeps its before/after under the setting key.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class SettingService {

    private final SettingRepository settingRepo;
    private final NumberSequenceRepository sequenceRepo;

    public SettingService(SettingRepository settingRepo, NumberSequenceRepository sequenceRepo) {
        this.settingRepo = settingRepo;
        this.sequenceRepo = sequenceRepo;
    }

    /** Stored value, or the key's default when the row is missing. May be null (e.g. no TIN yet). */
    public String get(SettingKey key) {
        return settingRepo.findBySettingKey(key.key()).map(Setting::getSettingValue).orElse(key.defaultValue());
    }

    /** Numeric setting; falls back to the default if the stored text is empty or not a number. */
    public BigDecimal getDecimal(SettingKey key) {
        String value = get(key);
        if (value != null && !value.isBlank()) {
            try {
                return new BigDecimal(value.trim());
            } catch (NumberFormatException e) {
                // fall through to the default
            }
        }
        return new BigDecimal(key.defaultValue());
    }

    public int getInt(SettingKey key) {
        return getDecimal(key).intValueExact();
    }

    /** Branch used to number documents when the caller gives none (MD-07). */
    public String branchCode() {
        return get(SettingKey.BRANCH_CODE);
    }

    /** Current values in the form object; missing rows show their defaults. */
    public SettingsDto load() {
        Map<String, Setting> rows = rowsByKey();
        SettingsDto dto = new SettingsDto();
        BeanWrapper form = PropertyAccessorFactory.forBeanPropertyAccess(dto);
        for (SettingKey key : SettingKey.values()) {
            Setting row = rows.get(key.key());
            form.setPropertyValue(key.property(), row != null ? row.getSettingValue() : key.defaultValue());
        }
        return dto;
    }

    /**
     * Saves every value that changed and returns one line per change ("key: old -> new") for the
     * activity log. An empty list means nothing changed.
     */
    @Transactional
    public List<String> update(SettingsDto dto) {
        if (!sequenceRepo.existsByBranchCode(dto.getBranchCode())) {
            // Without sequences for the branch no document could be numbered (MD-07).
            throw BusinessException.onField("branchCode", "settings.branchCode.noSequences", dto.getBranchCode());
        }
        Map<String, Setting> rows = rowsByKey();
        BeanWrapper form = PropertyAccessorFactory.forBeanPropertyAccess(dto);
        List<String> changes = new ArrayList<>();
        for (SettingKey key : SettingKey.values()) {
            String value = normalize(form.getPropertyValue(key.property()));
            Setting row = rows.get(key.key());
            if (row == null) {
                row = new Setting();
                row.setSettingKey(key.key());
                row.setSettingValue(value);
                settingRepo.save(row);
                changes.add(key.key() + ": " + display(value));
            } else if (!Objects.equals(row.getSettingValue(), value)) {
                changes.add(key.key() + ": " + display(row.getSettingValue()) + " -> " + display(value));
                row.setSettingValue(value);
            }
        }
        return changes;
    }

    /** Text stored for a form value: numbers without trailing zeros (0.2500 = 0.25), blanks as null. */
    static String normalize(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal number) {
            return number.stripTrailingZeros().toPlainString();
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private static String display(String value) {
        return value == null ? "(empty)" : value;
    }

    private Map<String, Setting> rowsByKey() {
        return settingRepo.findAll().stream()
                .collect(Collectors.toMap(Setting::getSettingKey, Function.identity()));
    }
}
