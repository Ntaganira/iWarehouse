package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.enums.TripCountOutcome;
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
 * - File      : TripCountLine.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : A unit of a van count: found on the vehicle, missing from it, or a label not expected (no unit, or not on this vehicle). Append-only (trg_trip_count_lines_append_only).
 * </pre>
 */
@Entity
@Immutable
@Table(name = "trip_count_lines")
@Getter
@Setter
@NoArgsConstructor
public class TripCountLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "count_id", nullable = false)
    private UUID countId;

    @Column(name = "stock_unit_id")
    private UUID stockUnitId;

    @Column(name = "unit_code", nullable = false, length = 30)
    private String unitCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TripCountOutcome outcome;
}
