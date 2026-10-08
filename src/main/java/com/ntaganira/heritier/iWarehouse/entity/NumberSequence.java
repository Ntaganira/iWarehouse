package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditIgnore;
import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.DocumentType;
import com.ntaganira.heritier.iWarehouse.enums.ResetPolicy;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : NumberSequence.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : Numbering sequence for one document type and branch (MD-07), e.g. INV-WH-2026-000123.
 *               The format (prefix, reset policy, padding) is audited. The counter (nextValue,
 *               periodKey) moves with every document issued, and the document's own audit row
 *               already records its number, so the counter is @AuditIgnore. Manual counter changes
 *               go to the activity log instead.
 * </pre>
 */
@Entity
@Table(name = "number_sequences")
@AuditedEntity(ref = "prefix")
@Getter
@Setter
@NoArgsConstructor
public class NumberSequence extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "doc_type", nullable = false, length = 30, updatable = false)
    private DocumentType docType;

    @Column(name = "branch_code", nullable = false, length = 10, updatable = false)
    private String branchCode;

    @Column(nullable = false, length = 10)
    private String prefix;

    @Enumerated(EnumType.STRING)
    @Column(name = "reset_policy", nullable = false, length = 10)
    private ResetPolicy resetPolicy = ResetPolicy.YEARLY;

    @Column(nullable = false)
    private int padding = 6;

    @AuditIgnore
    @Column(name = "next_value", nullable = false)
    private long nextValue = 1;

    /** Period the counter belongs to: "2026", "2026-10", or "" when it never resets or was never used. */
    @AuditIgnore
    @Column(name = "period_key", nullable = false, length = 7)
    private String periodKey = "";

    /** When the last number was issued; null until the first one. After that the reset policy is fixed. */
    @AuditIgnore
    @Column(name = "last_issued_at")
    private LocalDateTime lastIssuedAt;

    public boolean isUsed() {
        return lastIssuedAt != null;
    }
}
