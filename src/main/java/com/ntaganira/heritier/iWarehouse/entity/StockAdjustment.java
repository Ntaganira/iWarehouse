package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.AdjustmentStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : StockAdjustment.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : A stock adjustment with its reason (INV-07): units written off, found, added or resized.
 *               Posted at once when the value moved is within the approval limit, otherwise pending
 *               until another person approves or rejects it. Its units are held while it is pending.
 * </pre>
 */
@Entity
@Table(name = "stock_adjustments")
@AuditedEntity(ref = "number")
@Getter
@Setter
@NoArgsConstructor
public class StockAdjustment extends BaseEntity {

    @Column(nullable = false, unique = true, length = 30, updatable = false)
    private String number;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AdjustmentStatus status = AdjustmentStatus.PENDING_APPROVAL;

    @Column(nullable = false, length = 255, updatable = false)
    private String reason;

    /** RWF: stock value after minus before (negative for a loss). */
    @Column(name = "value_change", nullable = false, precision = 18, scale = 2)
    private BigDecimal valueChange = BigDecimal.ZERO;

    /** RWF: the lines' values without sign, added up; what the approval limit is compared with. */
    @Column(name = "value_moved", nullable = false, precision = 18, scale = 2)
    private BigDecimal valueMoved = BigDecimal.ZERO;

    @Column(name = "requested_by", nullable = false, length = 50, updatable = false)
    private String requestedBy;

    @Column(name = "requested_by_id", updatable = false)
    private Long requestedById;

    @Column(name = "decided_by", length = 50)
    private String decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    /** Why it was rejected or withdrawn, or the approver's note. */
    @Column(name = "decision_note", length = 255)
    private String decisionNote;

    @Column(name = "posted_at")
    private LocalDateTime postedAt;

    @OneToMany(mappedBy = "adjustment", cascade = CascadeType.ALL)
    @OrderBy("lineNo")
    private List<StockAdjustmentLine> lines = new ArrayList<>();

    public boolean isPending() {
        return status == AdjustmentStatus.PENDING_APPROVAL;
    }
}
