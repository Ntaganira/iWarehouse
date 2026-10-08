package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.ChargeUnit;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : ProcessingService.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Processing charged on top of the glass (MD-06, POS-02): edging, polishing, drilling,
 *               tempering... priced per list in its charge unit. Deactivated, never deleted.
 * </pre>
 */
@Entity
@Table(name = "processing_services")
@AuditedEntity(ref = "code")
@Getter
@Setter
@NoArgsConstructor
public class ProcessingService extends BaseEntity {

    @Column(nullable = false, unique = true, length = 20, updatable = false)
    private String code;

    @Column(nullable = false, length = 60)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "charge_unit", nullable = false, length = 10)
    private ChargeUnit chargeUnit;

    @Column(nullable = false)
    private boolean enabled = true;
}
