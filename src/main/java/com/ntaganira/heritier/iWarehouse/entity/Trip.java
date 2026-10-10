package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.TripStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : Trip.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : A moving-shop trip (FLT-05): a vehicle, its driver, a date and the area it covers, with its loading
 *               manifest (the units planned, each confirmed by a scan) and the fuel bought for it. Departing moves the
 *               units loaded to the vehicle, in the driver's charge (FLT-07), and keeps what left (pieces and kg) and
 *               the odometer (FLT-12). A planned trip can be cancelled with a reason. Two sets, so one entity graph
 *               fetches both without repeating rows.
 * </pre>
 */
@Entity
@Table(name = "trips")
@AuditedEntity(ref = "number")
@Getter
@Setter
@NoArgsConstructor
public class Trip extends BaseEntity {

    @Column(nullable = false, unique = true, length = 30, updatable = false)
    private String number;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vehicle_id", nullable = false)
    private Vehicle vehicle;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "driver_id", nullable = false)
    private Driver driver;

    @Column(name = "trip_date", nullable = false)
    private LocalDate tripDate;

    /** The route or area the vehicle covers. */
    @Column(nullable = false, length = 120)
    private String area;

    @Column(length = 255)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TripStatus status = TripStatus.PLANNED;

    @Column(name = "departed_at")
    private LocalDateTime departedAt;

    @Column(name = "departed_by", length = 50)
    private String departedBy;

    /** What left when it departed. */
    @Column(name = "loaded_pieces")
    private Integer loadedPieces;

    @Column(name = "loaded_kg", precision = 12, scale = 2)
    private BigDecimal loadedKg;

    @Column(name = "odometer_start")
    private Integer odometerStart;

    @Column(name = "odometer_end")
    private Integer odometerEnd;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "cancelled_by", length = 50)
    private String cancelledBy;

    @Column(name = "cancel_reason", length = 255)
    private String cancelReason;

    @OneToMany(mappedBy = "trip", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<TripLine> lines = new LinkedHashSet<>();

    @OneToMany(mappedBy = "trip", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<TripFuel> fuel = new LinkedHashSet<>();

    public boolean isPlanned() {
        return status == TripStatus.PLANNED;
    }

    public boolean isDeparted() {
        return status == TripStatus.DEPARTED;
    }

    public boolean isCancelled() {
        return status == TripStatus.CANCELLED;
    }

    /** Km driven, once both readings are in. */
    public Integer getDistanceKm() {
        return odometerStart == null || odometerEnd == null ? null : odometerEnd - odometerStart;
    }
}
