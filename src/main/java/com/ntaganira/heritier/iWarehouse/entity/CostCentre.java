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
 * - File      : CostCentre.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : A cost centre (F-15 A: Commercial, Supply Chain, Finance, Head Office): every expense names one, so spending
 *               is reported by department (T.05). Its manager recommends its bills and approves its requisitions.
 * </pre>
 */
@Entity
@Table(name = "cost_centres")
@AuditedEntity(ref = "code")
@Getter
@Setter
@NoArgsConstructor
public class CostCentre extends BaseEntity {

    @Column(nullable = false, unique = true, length = 10)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "manager_user_id")
    private Long managerUserId;

    @Column(name = "manager_name", length = 100)
    private String managerName;

    @Column(nullable = false)
    private boolean enabled = true;

    public String getLabel() {
        return code + " · " + name;
    }
}
