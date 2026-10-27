package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.VehicleCostCategory;
import com.ntaganira.heritier.iWarehouse.enums.VehicleCostPayment;
import com.ntaganira.heritier.iWarehouse.enums.VehicleCostStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : VehicleCost.java
 * - Date      : 2026. 10. 12.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : A vehicle's running cost (VC-WH-2026-000001): fuel, a service, a repair, spare parts, tyres, washing,
 *               parking or tolls, on a day and, when known, at an odometer reading. Its amount is RWF with VAT; the VAT
 *               of a registered garage's EBM receipt is claimed back. Paid from the vault, the bank or mobile money, owed
 *               to the garage (CREDIT), or paid by the trip's driver from its cash (DRIVER_FLOAT). Posted when recorded
 *               within the approval limit, otherwise when another person approves it; a posted cost is reversed, never
 *               edited (VehicleCostService).
 * </pre>
 */
@Entity
@Table(name = "vehicle_costs")
@AuditedEntity(ref = "number")
@Getter
@Setter
@NoArgsConstructor
public class VehicleCost extends BaseEntity {

    @Column(nullable = false, unique = true, length = 30, updatable = false)
    private String number;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vehicle_id", nullable = false, updatable = false)
    private Vehicle vehicle;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trip_id", updatable = false)
    private Trip trip;

    @Column(name = "cost_date", nullable = false, updatable = false)
    private LocalDate costDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private VehicleCostCategory category;

    @Column(nullable = false, length = 255, updatable = false)
    private String description;

    @Column(name = "odometer_km", updatable = false)
    private Integer odometerKm;

    @Column(precision = 8, scale = 2, updatable = false)
    private BigDecimal litres;

    /** RWF, VAT included. */
    @Column(nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(name = "vat_amount", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal vatAmount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15, updatable = false)
    private VehicleCostPayment payment;

    @Column(name = "payment_ref", length = 60, updatable = false)
    private String paymentRef;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id", updatable = false)
    private Supplier supplier;

    @Column(name = "supplier_ref", length = 60, updatable = false)
    private String supplierRef;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "driver_id", updatable = false)
    private Driver driver;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "service_plan_id", updatable = false)
    private VehicleServicePlan servicePlan;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private VehicleCostStatus status;

    @Column(name = "requested_by_id", updatable = false)
    private Long requestedById;

    @Column(name = "requested_by", nullable = false, length = 50, updatable = false)
    private String requestedBy;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private LocalDateTime requestedAt;

    /** Approved, rejected or withdrawn by. */
    @Column(name = "decided_by", length = 50)
    private String decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    /** The reason of a rejection or withdrawal, or the approver's note. */
    @Column(name = "decision_note", length = 255)
    private String decisionNote;

    @Column(name = "journal_id")
    private UUID journalId;

    @Column(name = "posted_at")
    private LocalDateTime postedAt;

    @Column(name = "reversal_journal_id")
    private UUID reversalJournalId;

    @Column(name = "reversal_date")
    private LocalDate reversalDate;

    @Column(name = "reversal_reason", length = 255)
    private String reversalReason;

    @Column(name = "reversed_by", length = 50)
    private String reversedBy;

    @Column(name = "reversed_at")
    private LocalDateTime reversedAt;

    public boolean isPending() {
        return status == VehicleCostStatus.PENDING_APPROVAL;
    }

    public boolean isPosted() {
        return status == VehicleCostStatus.POSTED;
    }

    /** What the vehicle cost without the VAT claimed back: the expense. */
    public BigDecimal getNetAmount() {
        return amount.subtract(vatAmount);
    }
}
