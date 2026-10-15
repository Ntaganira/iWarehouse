package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.CustomerType;
import com.ntaganira.heritier.iWarehouse.enums.Market;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

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

    // --- Know your customer (SDP 11, F-02 A-B)

    /** The RDB company code, or a sole trader's national ID. */
    @Column(name = "registration_number", length = 40)
    private String registrationNumber;

    @Column(length = 60)
    private String district;

    @Column(name = "country_code", length = 2)
    @JdbcTypeCode(SqlTypes.CHAR)                         // a CHAR(2) column
    private String countryCode;

    @Column(name = "bank_name", length = 80)
    private String bankName;

    @Column(name = "bank_account", length = 40)
    private String bankAccount;

    /** The mobile money number registered to the customer. */
    @Column(name = "momo_number", length = 30)
    private String momoNumber;

    /** Expected purchases a month, RWF. */
    @Column(name = "expected_monthly", precision = 18, scale = 2)
    private BigDecimal expectedMonthly;

    @Column(name = "trade_references", length = 500)
    private String tradeReferences;

    /** A guarantee, a post-dated cheque, a deposit... */
    @Column(name = "security_offered", length = 255)
    private String securityOffered;

    // --- The credit review (F-02 C)

    /** The day its last credit application was approved. */
    @Column(name = "credit_reviewed_on")
    private LocalDate creditReviewedOn;

    /** When its credit limit is next reviewed; null with credit = never reviewed. */
    @Column(name = "credit_review_due")
    private LocalDate creditReviewDue;

    // --- Where it buys (F-01 A, BP 6)

    /** Its territory, by id (the regional sales manager's). */
    @Column(name = "territory_id")
    private UUID territoryId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Market market = Market.DOMESTIC;

    /** An established agent selling on to others (BP 6): orders can name it as the agent. */
    @Column(nullable = false)
    private boolean agent;

    public boolean hasCredit() {
        return creditLimit.signum() > 0 || paymentTermsDays > 0;
    }
}
