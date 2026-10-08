package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : Setting.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : One configurable setting (ADM-03), stored as text and typed by SettingKey.
 *               Audited: each change keeps the old and new value under the setting's key.
 * </pre>
 */
@Entity
@Table(name = "settings")
@AuditedEntity(ref = "settingKey")
@Getter
@Setter
@NoArgsConstructor
public class Setting extends BaseEntity {

    @Column(name = "setting_key", nullable = false, unique = true, length = 100)
    private String settingKey;

    @Column(name = "setting_value", length = 500)
    private String settingValue;
}
