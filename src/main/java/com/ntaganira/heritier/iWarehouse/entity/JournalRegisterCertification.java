package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : JournalRegisterCertification.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : A closed month's register of journal numbers certified by the Director of Finance (F-16): how many
 *               journals, the first and last numbers, any gap, the total; then sampled by the Internal Controller with a
 *               note of what was checked. One per month.
 * </pre>
 */
@Entity
@Table(name = "journal_register_certifications")
@AuditedEntity(ref = "periodEnd")
@Getter
@Setter
@NoArgsConstructor
public class JournalRegisterCertification extends BaseEntity {

    @Column(name = "period_end", nullable = false, unique = true, updatable = false)
    private LocalDate periodEnd;

    @Column(nullable = false, updatable = false)
    private int journals;

    @Column(name = "first_number", length = 30, updatable = false)
    private String firstNumber;

    @Column(name = "last_number", length = 30, updatable = false)
    private String lastNumber;

    @Column(length = 500, updatable = false)
    private String gaps;

    @Column(nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal total;

    @Column(name = "certified_by", nullable = false, length = 50, updatable = false)
    private String certifiedBy;

    @Column(name = "certified_by_id", updatable = false)
    private Long certifiedById;

    @Column(name = "certified_at", nullable = false, updatable = false)
    private LocalDateTime certifiedAt;

    @Column(name = "certify_note", length = 255, updatable = false)
    private String certifyNote;

    @Column(name = "sampled_by", length = 50)
    private String sampledBy;

    @Column(name = "sampled_by_id")
    private Long sampledById;

    @Column(name = "sampled_at")
    private LocalDateTime sampledAt;

    @Column(name = "sample_note", length = 500)
    private String sampleNote;

    public boolean isSampled() {
        return sampledAt != null;
    }
}
