package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.enums.ReturnOutcome;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : CreditNoteUnit.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : A unit brought back on a credit note (POS-09): the invoice line it was sold on, where it went (back on a
 *               rack, or cullet) and the cost that came back from cost of goods sold. A unit comes back once per invoice
 *               (uk_credit_note_units_unit). Append-only (trg_credit_note_units_append_only), so it is not audited.
 * </pre>
 */
@Entity
@Immutable
@Table(name = "credit_note_units")
@Getter
@Setter
@NoArgsConstructor
public class CreditNoteUnit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "credit_note_id", nullable = false)
    private UUID creditNoteId;

    @Column(name = "invoice_id", nullable = false)
    private UUID invoiceId;

    @Column(name = "invoice_line_id", nullable = false)
    private UUID invoiceLineId;

    @Column(name = "stock_unit_id", nullable = false)
    private UUID stockUnitId;

    @Column(name = "unit_code", nullable = false, length = 30)
    private String unitCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ReturnOutcome outcome;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_id")
    private Location location;

    @Column(name = "unit_cost", nullable = false, precision = 18, scale = 2)
    private BigDecimal unitCost;
}
