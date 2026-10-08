package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.enums.CountAction;
import com.ntaganira.heritier.iWarehouse.enums.CountOutcome;
import com.ntaganira.heritier.iWarehouse.enums.StockStatus;
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
 * - File      : StockCountLine.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : One line of a closed stock count (INV-08): a unit expected or scanned, what the count
 *               found and what closing did about it. Written when the count closes and never changed
 *               (trg_stock_count_lines_append_only): each row is its own record, so it is not audited.
 * </pre>
 */
@Entity
@Immutable
@Table(name = "stock_count_lines")
@Getter
@Setter
@NoArgsConstructor
public class StockCountLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "count_id", nullable = false)
    private UUID countId;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CountOutcome outcome;

    @Column(nullable = false, length = 30)
    private String code;

    @Column(name = "stock_unit_id")
    private UUID stockUnitId;

    /** The unit's state when the count closed. */
    @Enumerated(EnumType.STRING)
    @Column(name = "unit_status", length = 20)
    private StockStatus unitStatus;

    @Column(name = "expected_location_id")
    private UUID expectedLocationId;

    @Column(name = "found_location_id")
    private UUID foundLocationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CountAction action;

    /** Why nothing was done (held by another document, a sheet found on an off-cut rack). */
    @Column(length = 255)
    private String note;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false, length = 50)
    private String username;
}
