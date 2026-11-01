package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : District.java
 * - Date      : 2026. 11. 01.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : A Rwandan district, under a province.
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "districts")
@AuditedEntity(ref = "code")
public class District extends BaseEntity implements AdministrativeNode {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "province_id", nullable = false)
    private Province province;

    @Column(nullable = false, length = 2, unique = true)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "is_enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "allow_edit", nullable = false)
    private boolean allowEdit = true;
}
