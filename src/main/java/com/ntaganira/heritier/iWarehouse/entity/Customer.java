package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.CustomerType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : Customer.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : A customer (MD-04): walk-in, account or contractor, with TIN for invoices (TAX-04), credit
 *               limit and payment terms (POS-05) and an optional price list (none = the default list).
 *               The default customer takes anonymous counter sales: it stays a walk-in and stays active.
 *               The code comes from DocumentNumberService and never changes. Deactivated, never deleted.
 * </pre>
 */
@Entity
@Table(name = "customers")
@AuditedEntity(ref = "code")
@Getter
@Setter
@NoArgsConstructor
public class Customer extends BaseEntity {

    @Column(nullable = false, unique = true, length = 30, updatable = false)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "customer_type", nullable = false, length = 12)
    private CustomerType type;

    @Column(length = 9)
    private String tin;

    @Column(length = 30)
    private String phone;

    @Column(length = 120)
    private String email;

    @Column(name = "contact_name", length = 100)
    private String contactName;

    @Column(length = 255)
    private String address;

    /** RWF; 0 = cash only. */
    @Column(name = "credit_limit", nullable = false, precision = 18, scale = 2)
    private BigDecimal creditLimit = BigDecimal.ZERO;

    @Column(name = "payment_terms_days", nullable = false)
    private int paymentTermsDays;

    /** Null = the default price list. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "price_list_id")
    private PriceList priceList;

    @Column(name = "is_default", nullable = false, updatable = false)
    private boolean defaultCustomer;

    @Column(length = 255)
    private String notes;

    @Column(nullable = false)
    private boolean enabled = true;
}
