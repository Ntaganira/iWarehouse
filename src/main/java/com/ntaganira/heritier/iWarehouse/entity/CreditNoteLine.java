package com.ntaganira.heritier.iWarehouse.entity;

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
 * - File      : CreditNoteLine.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : What a credit note credits one invoice line (POS-09): the pieces back and their share of the line's
 *               amount, VAT included in whole RWF, with the line's tax letter and rate (TAX-01). Append-only
 *               (trg_credit_note_lines_append_only): the credit note records the change, so it is not audited.
 * </pre>
 */
@Entity
@Immutable
@Table(name = "credit_note_lines")
@Getter
@Setter
@NoArgsConstructor
public class CreditNoteLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "credit_note_id", nullable = false)
    private UUID creditNoteId;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Column(name = "invoice_line_id", nullable = false)
    private UUID invoiceLineId;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "tax_code", nullable = false, length = 1)
    private String taxCode;

    @Column(name = "vat_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal vatRate;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;
}
