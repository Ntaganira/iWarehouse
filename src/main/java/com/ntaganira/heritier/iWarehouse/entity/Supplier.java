package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.Incoterm;
import com.ntaganira.heritier.iWarehouse.enums.SupplierApprovalStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : Supplier.java
 * - Date      : 2026. 10. 08.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
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

    // --- The approved supplier register (CMP 08)

    @Column(name = "registration_number", length = 40)
    private String registrationNumber;

    @Column(name = "bank_name", length = 80)
    private String bankName;

    @Column(name = "bank_account", length = 40)
    private String bankAccount;

    @Column(name = "bank_swift", length = 11)
    private String bankSwift;

    /** One reference a line: who, and how to reach them. */
    @Column(name = "trade_references", length = 500)
    private String tradeReferences;

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_status", nullable = false, length = 10)
    private SupplierApprovalStatus approvalStatus = SupplierApprovalStatus.APPROVED;

    @Column(name = "approved_by", length = 50)
    private String approvedBy;

    @Column(name = "approved_by_id")
    private Long approvedById;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    /** The approver's note, or why it was rejected. */
    @Column(name = "approval_note", length = 255)
    private String approvalNote;

    public boolean isApproved() {
        return approvalStatus == SupplierApprovalStatus.APPROVED;
    }

    /** The references given, one a line. */
    public java.util.List<String> getReferenceList() {
        return tradeReferences == null ? java.util.List.of()
                : java.util.Arrays.stream(tradeReferences.split("\\R")).map(String::trim).filter(r -> !r.isEmpty()).toList();
    }
}
