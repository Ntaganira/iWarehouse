package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : StockTransfer.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Units moved to another rack or slot (INV-07). Posted when saved: each unit gets a
 *               TRANSFER movement; never changed afterwards.
 * </pre>
 */
@Entity
@Table(name = "stock_transfers")
@AuditedEntity(ref = "number")
@Getter
@Setter
@NoArgsConstructor
public class StockTransfer extends BaseEntity {

    @Column(nullable = false, unique = true, length = 30, updatable = false)
    private String number;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "to_location_id", nullable = false, updatable = false)
    private Location toLocation;

    @Column(length = 255, updatable = false)
    private String note;

    @Column(name = "posted_at", nullable = false, updatable = false)
    private LocalDateTime postedAt;

    @Column(name = "posted_by", length = 50, updatable = false)
    private String postedBy;

    @OneToMany(mappedBy = "transfer", cascade = CascadeType.ALL)
    @OrderBy("lineNo")
    private List<StockTransferLine> lines = new ArrayList<>();
}
