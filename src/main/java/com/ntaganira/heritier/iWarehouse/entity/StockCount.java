package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.StockCountStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : StockCount.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : A stock count by scanning (INV-08): a place and every place under it (fixed when it
 *               starts), optionally one glass. Open while labels are scanned; the units on its places are
 *               held meanwhile (INV-05). Closing records the result (StockCountLine) and the totals below;
 *               missing and lost units found go on one adjustment (approved as any other).
 * </pre>
 */
@Entity
@Table(name = "stock_counts")
@AuditedEntity(ref = "number")
@Getter
@Setter
@NoArgsConstructor
public class StockCount extends BaseEntity {

    @Column(nullable = false, unique = true, length = 30, updatable = false)
    private String number;

    /** The place counted, with all its sub-locations. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "location_id", nullable = false, updatable = false)
    private Location location;

    /** Only this glass (a cycle count by product), or every glass. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", updatable = false)
    private Product product;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StockCountStatus status = StockCountStatus.OPEN;

    @Column(length = 255, updatable = false)
    private String note;

    @Column(name = "started_at", nullable = false, updatable = false)
    private LocalDateTime startedAt;

    @Column(name = "started_by", length = 50, updatable = false)
    private String startedBy;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "closed_by", length = 50)
    private String closedBy;

    @Column(name = "cancel_reason", length = 255)
    private String cancelReason;

    @Column(name = "expected_units")
    private Integer expectedUnits;

    @Column(name = "counted_units")
    private Integer countedUnits;

    @Column(name = "matched_units")
    private Integer matchedUnits;

    @Column(name = "missing_units")
    private Integer missingUnits;

    @Column(name = "misplaced_units")
    private Integer misplacedUnits;

    @Column(name = "extra_units")
    private Integer extraUnits;

    /** The adjustment that writes off the missing units and finds the lost ones, if any. */
    @Column(name = "adjustment_id")
    private UUID adjustmentId;

    @Column(name = "adjustment_number", length = 30)
    private String adjustmentNumber;

    /** The place and every place under it when the count started (the units held). */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "stock_count_places", joinColumns = @JoinColumn(name = "count_id"))
    @Column(name = "location_id", nullable = false)
    private Set<UUID> places = new HashSet<>();

    @OneToMany(mappedBy = "count", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("scannedAt DESC")
    private Set<StockCountScan> scans = new LinkedHashSet<>();

    public boolean isOpen() {
        return status == StockCountStatus.OPEN;
    }
}
