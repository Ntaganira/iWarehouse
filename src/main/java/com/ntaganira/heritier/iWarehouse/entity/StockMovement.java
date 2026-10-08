package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.enums.MovementType;
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
 * - File      : StockMovement.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : One change of a stock unit's location or status (INV-04): from/to, user, time, reason
 *               and the document that caused it. A ledger: inserted by StockService only, never
 *               updated or deleted (@Immutable here, trg_stock_movements_append_only in the database).
 *               Not @AuditedEntity: the row itself is the record, and the unit's change is audited.
 * </pre>
 */
@Entity
@Immutable
@Table(name = "stock_movements")
@Getter
@Setter
@NoArgsConstructor
public class StockMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "stock_unit_id", nullable = false)
    private UUID stockUnitId;

    @Column(name = "moved_at", nullable = false)
    private LocalDateTime movedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", nullable = false, length = 20)
    private MovementType type;

    @Column(name = "from_location_id")
    private UUID fromLocationId;

    @Column(name = "to_location_id")
    private UUID toLocationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 12)
    private StockStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 12)
    private StockStatus toStatus;

    @Column(length = 255)
    private String reason;

    /** Document type that caused the movement, e.g. GOODS_RECEIPT. */
    @Column(name = "ref_type", length = 30)
    private String refType;

    @Column(name = "ref_id")
    private UUID refId;

    @Column(name = "ref_number", length = 30)
    private String refNumber;

    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false, length = 50)
    private String username;
}
