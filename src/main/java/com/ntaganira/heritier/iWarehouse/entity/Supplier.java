package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.Incoterm;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : Supplier.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : A supplier of glass or services (MD-05): country, invoicing currency (purchase orders
 *               are raised in it, PRC-01) and default incoterm. The code comes from DocumentNumberService
 *               and never changes. Deactivated, never deleted.
 * </pre>
 */
@Entity
@Table(name = "suppliers")
@AuditedEntity(ref = "code")
@Getter
@Setter
@NoArgsConstructor
public class Supplier extends BaseEntity {

    @Column(nullable = false, unique = true, length = 30, updatable = false)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    /** ISO 3166-1 alpha-2, e.g. CN. */
    @Column(name = "country_code", nullable = false, length = 2)
    private String countryCode;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Enumerated(EnumType.STRING)
    @Column(length = 3)
    private Incoterm incoterm;

    @Column(length = 30)
    private String tin;

    @Column(name = "payment_terms_days", nullable = false)
    private int paymentTermsDays;

    @Column(name = "contact_name", length = 100)
    private String contactName;

    @Column(length = 30)
    private String phone;

    @Column(length = 120)
    private String email;

    @Column(length = 255)
    private String address;

    @Column(length = 255)
    private String notes;

    @Column(nullable = false)
    private boolean enabled = true;
}
