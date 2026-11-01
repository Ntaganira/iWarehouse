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
 * - File      : Village.java
 * - Date      : 2026. 11. 01.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : A Rwandan village, under a cell; the 8-digit official code is the one scanned.
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "villages")
@AuditedEntity(ref = "code")
public class Village extends BaseEntity implements AdministrativeNode {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cell_id", nullable = false)
    private Cell cell;

    @Column(nullable = false, length = 8, unique = true)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "is_enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "allow_edit", nullable = false)
    private boolean allowEdit = true;
}
