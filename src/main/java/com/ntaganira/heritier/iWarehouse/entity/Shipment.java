package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.AllocationMethod;
import com.ntaganira.heritier.iWarehouse.enums.ClaimStatus;
import com.ntaganira.heritier.iWarehouse.enums.ShipmentCostStatus;
import com.ntaganira.heritier.iWarehouse.enums.ShipmentStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : Shipment.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : One import shipment (SRS 6.1 Shipment): the goods receipts that came in it, its import
 *               costs in their own currencies (PRC-03) and how they are shared between the crates
 *               (PRC-04). Costs are posted as the bills arrive; each posting adds landed cost to the
 *               units (PRC-05). Also carries the claim for sheets broken on arrival (PRC-06).
 * </pre>
 */
@Entity
@Table(name = "shipments")
@AuditedEntity(ref = "number")
@Getter
@Setter
@NoArgsConstructor
public class Shipment extends BaseEntity {

    @Column(nullable = false, unique = true, length = 30, updatable = false)
    private String number;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ShipmentStatus status = ShipmentStatus.OPEN;

    /** Container or bill of lading number. */
    @Column(length = 60)
    private String reference;

    @Column(name = "arrival_date", nullable = false)
    private LocalDate arrivalDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "allocation_method", nullable = false, length = 10)
    private AllocationMethod allocationMethod = AllocationMethod.AREA;

    @Column(length = 500)
    private String notes;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "closed_by", length = 50)
    private String closedBy;

    @Column(name = "cancel_reason", length = 255)
    private String cancelReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "claim_status", nullable = false, length = 10)
    private ClaimStatus claimStatus = ClaimStatus.NONE;

    /** Supplier or insurer the claim is sent to. */
    @Column(name = "claim_party", length = 100)
    private String claimParty;

    @Column(name = "claim_ref", length = 60)
    private String claimRef;

    @Column(name = "claim_date")
    private LocalDate claimDate;

    /** RWF claimed. */
    @Column(name = "claim_amount", precision = 18, scale = 2)
    private BigDecimal claimAmount;

    /** RWF received. */
    @Column(name = "claim_settled_amount", precision = 18, scale = 2)
    private BigDecimal claimSettledAmount;

    /** Settlement note, or why the claim was rejected. */
    @Column(name = "claim_note", length = 255)
    private String claimNote;

    /** Sets, so a page fetches receipts and cost lines in one query without repeating rows. */
    @OneToMany(mappedBy = "shipment", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("receiptNumber")
    private Set<ShipmentReceipt> receipts = new LinkedHashSet<>();

    @OneToMany(mappedBy = "shipment", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo")
    private Set<ShipmentCost> costs = new LinkedHashSet<>();

    public boolean hasPostedCosts() {
        return costs.stream().anyMatch(c -> c.getStatus() == ShipmentCostStatus.POSTED);
    }

    public boolean hasDraftCosts() {
        return costs.stream().anyMatch(c -> c.getStatus() == ShipmentCostStatus.DRAFT);
    }

    /** Postings so far (the last cost lines' posting number). */
    public int getPostings() {
        return costs.stream().map(ShipmentCost::getPostingNo).filter(n -> n != null).mapToInt(Integer::intValue)
                .max().orElse(0);
    }
}
