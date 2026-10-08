package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.CuttingJobStatus;
import com.ntaganira.heritier.iWarehouse.enums.CuttingPurpose;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : CuttingJob.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : A cutting job (SRS 6.1 CuttingJob, PRD-01..09): the pieces wanted, for stock or for a
 *               customer; the source unit the operator took (PRD-02); and, once cut, the areas of pieces,
 *               off-cuts, cullet and breakage, the cullet kg, the spoilage cost and the yield. What the
 *               cut produced, unit by unit, is in cutting_job_outputs.
 * </pre>
 */
@Entity
@Table(name = "cutting_jobs")
@AuditedEntity(ref = "number")
@Getter
@Setter
@NoArgsConstructor
public class CuttingJob extends BaseEntity {

    @Column(nullable = false, unique = true, length = 30, updatable = false)
    private String number;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private CuttingJobStatus status = CuttingJobStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private CuttingPurpose purpose = CuttingPurpose.STOCK;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    /** Quote or order reference the customer was given. */
    @Column(name = "customer_ref", length = 60)
    private String customerRef;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(length = 500)
    private String notes;

    /** The job this one cuts the rest of. */
    @Column(name = "parent_job_id", updatable = false)
    private UUID parentJobId;

    // ---------------------------------------------------------------- the source taken (PRD-02)

    @Column(name = "source_unit_id")
    private UUID sourceUnitId;

    @Column(name = "source_code", length = 30)
    private String sourceCode;

    @Column(name = "source_width_mm")
    private Integer sourceWidthMm;

    @Column(name = "source_height_mm")
    private Integer sourceHeightMm;

    @Column(name = "source_area_m2", precision = 10, scale = 4)
    private BigDecimal sourceAreaM2;

    @Column(name = "operator_id")
    private Long operatorId;

    @Column(name = "operator_name", length = 50)
    private String operatorName;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    // ---------------------------------------------------------------- the cut (PRD-03..07)

    /** RWF cost of the source when it was cut. */
    @Column(name = "source_cost", precision = 18, scale = 2)
    private BigDecimal sourceCost;

    @Column(name = "pieces_area_m2", precision = 10, scale = 4)
    private BigDecimal piecesAreaM2;

    @Column(name = "offcut_area_m2", precision = 10, scale = 4)
    private BigDecimal offcutAreaM2;

    /** Leftovers below the off-cut threshold and the trim (PRD-05). */
    @Column(name = "cullet_area_m2", precision = 10, scale = 4)
    private BigDecimal culletAreaM2;

    @Column(name = "cullet_kg", precision = 10, scale = 2)
    private BigDecimal culletKg;

    @Column(name = "broken_area_m2", precision = 10, scale = 4)
    private BigDecimal brokenAreaM2;

    /** RWF expensed to spoilage for the cullet. */
    @Column(name = "cullet_cost", precision = 18, scale = 2)
    private BigDecimal culletCost;

    /** RWF expensed to spoilage for glass broken while cutting (PRD-08). */
    @Column(name = "broken_cost", precision = 18, scale = 2)
    private BigDecimal brokenCost;

    /** Pieces and off-cuts as a percentage of the source area (PRD-09). */
    @Column(name = "yield_percent", precision = 5, scale = 2)
    private BigDecimal yieldPercent;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "completed_by", length = 50)
    private String completedBy;

    @Column(name = "cancel_reason", length = 255)
    private String cancelReason;

    @OneToMany(mappedBy = "job", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo")
    private List<CuttingJobLine> lines = new ArrayList<>();

    public int getPieces() {
        return lines.stream().mapToInt(CuttingJobLine::getQuantity).sum();
    }

    /** m² of the pieces wanted. */
    public BigDecimal getPiecesWantedM2() {
        return lines.stream().map(CuttingJobLine::getAreaM2).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Pieces cut so far (completed jobs). */
    public int getPiecesCut() {
        return lines.stream().mapToInt(l -> l.getCutQty() == null ? 0 : l.getCutQty()).sum();
    }

    /** A completed job that did not cut every piece wanted: the rest can be cut from another source. */
    public boolean isShort() {
        return status == CuttingJobStatus.COMPLETED && getPiecesCut() < getPieces();
    }

    /** The m² that left stock as spoilage: cullet and breakage (PRD-05, PRD-08). */
    public BigDecimal getSpoilageM2() {
        return culletAreaM2 == null ? null : culletAreaM2.add(brokenAreaM2);
    }

    public BigDecimal getSpoilageCost() {
        return culletCost == null ? null : culletCost.add(brokenCost);
    }

    /** RWF per m² of the source when it was cut, 4 decimals. */
    public BigDecimal getSourceCostPerM2() {
        return sourceCost == null || sourceAreaM2 == null ? null : sourceCost.divide(sourceAreaM2, 4, RoundingMode.HALF_UP);
    }

    /** m² of the pieces and off-cuts that went to stock. */
    public BigDecimal getStockedAreaM2() {
        return piecesAreaM2 == null ? null : piecesAreaM2.add(offcutAreaM2);
    }

    /** m² the source has beyond the pieces wanted: what will be off-cuts or cullet. */
    public BigDecimal getLeftOverM2() {
        return sourceAreaM2 == null ? null : sourceAreaM2.subtract(getPiecesWantedM2()).max(BigDecimal.ZERO);
    }
}
