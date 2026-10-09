package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.service.Pricing;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : CuttingJobLine.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Pieces wanted on a cutting job (PRD-01): size, quantity, processing (codes of processing
 *               services, e.g. EDGING,DRILLING) and the customer's mark. Fixed once a source is taken;
 *               the quantity actually cut is set when the cut is recorded.
 * </pre>
 */
@Entity
@Table(name = "cutting_job_lines")
@AuditedEntity(ref = "lineNo")
@Getter
@Setter
@NoArgsConstructor
public class CuttingJobLine extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cutting_job_id", nullable = false, updatable = false)
    private CuttingJob job;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Column(name = "width_mm", nullable = false)
    private int widthMm;

    @Column(name = "height_mm", nullable = false)
    private int heightMm;

    @Column(nullable = false)
    private int quantity;

    /** Processing service codes, comma separated, sorted. */
    @Column(length = 200)
    private String processing;

    @Column(length = 60)
    private String mark;

    @Column(name = "cut_qty")
    private Integer cutQty;

    /** The sale's size this line cuts (POS-02), if any. */
    @Column(name = "sales_line_id")
    private UUID salesLineId;

    /** m² of one piece. */
    public BigDecimal getPieceAreaM2() {
        return Pricing.areaM2(widthMm, heightMm);
    }

    /** m² of all the pieces of the line. */
    public BigDecimal getAreaM2() {
        return getPieceAreaM2().multiply(BigDecimal.valueOf(quantity));
    }

    public List<String> getProcessingCodes() {
        return StringUtils.hasText(processing) ? Arrays.asList(processing.split(",")) : List.of();
    }

    /** Pieces still to cut after the job was completed. */
    public int getShortQty() {
        return cutQty == null ? 0 : quantity - cutQty;
    }
}
