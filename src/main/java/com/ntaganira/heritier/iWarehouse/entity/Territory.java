package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : Territory.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : A sales territory and its Regional Sales Manager (BP 6, Res. 7): customers and orders belong to one, so
 *               sales are reported by territory. Deactivated, never deleted.
 * </pre>
 */
@Entity
@Table(name = "territories")
@AuditedEntity(ref = "code")
@Getter
@Setter
@NoArgsConstructor
public class Territory extends BaseEntity {

    @Column(nullable = false, unique = true, length = 20)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    /** The Regional Sales Manager, a user. */
    @Column(name = "manager_user_id")
    private Long managerUserId;

    @Column(name = "manager_name", length = 100)
    private String managerName;

    @Column(nullable = false)
    private boolean enabled = true;
}
