package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.enums.PieceCheckOutcome;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Immutable;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : CuttingPieceCheck.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : A cut piece measured by another person than who recorded the cut (IMP 10 step 3, PF-03): accepted,
 *               or out of specification with the reason and the job its size was cut again on. Once per piece,
 *               append-only (trg_cutting_piece_checks_append_only).
 * </pre>
 */
@Entity
@Immutable
@Table(name = "cutting_piece_checks")
@Getter
@Setter
@NoArgsConstructor
public class CuttingPieceCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "cutting_job_id", nullable = false)
    private UUID cuttingJobId;

    @Column(name = "stock_unit_id", nullable = false)
    private UUID stockUnitId;

    @Column(name = "unit_code", nullable = false, length = 30)
    private String unitCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PieceCheckOutcome outcome;

    @Column(length = 255)
    private String reason;

    @Column(name = "rework_job_id")
    private UUID reworkJobId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false, length = 50)
    private String username;

    public boolean isPassed() {
        return outcome == PieceCheckOutcome.PASSED;
    }
}
