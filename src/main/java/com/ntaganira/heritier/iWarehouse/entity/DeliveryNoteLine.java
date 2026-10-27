package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.enums.DeliveryLineKind;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Immutable;

import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : DeliveryNoteLine.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : One unit a delivery note releases: a whole sheet of the invoice or a cut piece. Append-only
 *               (trg_delivery_note_lines_append_only): the note's status says whether it left.
 * </pre>
 */
@Entity
@Immutable
@Table(name = "delivery_note_lines")
@Getter
@Setter
@NoArgsConstructor
public class DeliveryNoteLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "delivery_note_id", nullable = false)
    private UUID deliveryNoteId;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Column(name = "invoice_line_id", nullable = false)
    private UUID invoiceLineId;

    @Column(name = "stock_unit_id", nullable = false)
    private UUID stockUnitId;

    @Column(name = "unit_code", nullable = false, length = 30)
    private String unitCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 6)
    private DeliveryLineKind kind;

    @Column(name = "width_mm", nullable = false)
    private int widthMm;

    @Column(name = "height_mm", nullable = false)
    private int heightMm;
}
