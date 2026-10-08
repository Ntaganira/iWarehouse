package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.enums.BreakageReason;
import com.ntaganira.heritier.iWarehouse.enums.CuttingOutputKind;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : CuttingJobOutput.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : One thing a cut produced (PRD-03..08): a cut piece or off-cut (with its new stock unit),
 *               a leftover below the off-cut threshold or the trim (cullet), or glass broken while
 *               cutting (with its reason), each with its part of the source cost (PRD-07). The parts of
 *               a job add up to the source cost. Append-only (trg_cutting_job_outputs_append_only): each
 *               row is its own record, so it is not audited.
 * </pre>
 */
@Entity
@Immutable
@Table(name = "cutting_job_outputs")
@Getter
@Setter
@NoArgsConstructor
public class CuttingJobOutput {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "cutting_job_id", nullable = false)
    private UUID cuttingJobId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private CuttingOutputKind kind;

    /** The pieces wanted a cut piece belongs to. */
    @Column(name = "job_line_id")
    private UUID jobLineId;

    /** None for the trim (the cullet nobody measured). */
    @Column(name = "width_mm")
    private Integer widthMm;

    @Column(name = "height_mm")
    private Integer heightMm;

    @Column(nullable = false)
    private int quantity;

    /** m² of all of them. */
    @Column(name = "area_m2", nullable = false, precision = 10, scale = 4)
    private BigDecimal areaM2;

    @Column(name = "weight_kg", nullable = false, precision = 10, scale = 2)
    private BigDecimal weightKg;

    @Column(name = "stock_unit_id")
    private UUID stockUnitId;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private BreakageReason reason;

    @Column(length = 255)
    private String note;

    /** RWF part of the source cost. */
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal cost;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false, length = 50)
    private String username;
}
