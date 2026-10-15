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
 *               manifest (the units planned, each confirmed by a scan); its fuel and other costs are vehicle costs naming it. Departing moves the
 *               units loaded to the vehicle, in the driver's charge (FLT-07), and keeps what left (pieces and kg) and
 *               the odometer (FLT-12). A planned trip can be cancelled with a reason. At the end of the day the trip
 *               comes back: what became of its units (FLT-09), the cash its driver declares (MPOS-07), then it closes
 *               once its stock and cash are settled (SRS 5.1). Two sets, so one entity graph fetches both without
 *               repeating rows.
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

    /** Scanned back and reconciled (FLT-09): what became of the units loaded. */
    @Column(name = "returned_at")
    private LocalDateTime returnedAt;

    @Column(name = "returned_by", length = 50)
    private String returnedBy;

    @Column(name = "returned_pieces")
    private Integer returnedPieces;

    @Column(name = "sold_pieces")
    private Integer soldPieces;

    @Column(name = "broken_pieces")
    private Integer brokenPieces;

    @Column(name = "missing_pieces")
    private Integer missingPieces;

    /** What the driver says they hold at the end of the day (MPOS-07). */
    @Column(name = "cash_declared", precision = 18, scale = 2)
    private BigDecimal cashDeclared;

    @Column(name = "cash_declared_at")
    private LocalDateTime cashDeclaredAt;

    @Column(name = "cash_declared_by", length = 50)
    private String cashDeclaredBy;

    /** PHONE (the driver on the mobile POS) or OFFICE (recorded at the warehouse). */
    @Column(name = "cash_declared_via", length = 10)
    private String cashDeclaredVia;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    /** The load request (F-09): the route, the towns, the days out and the day the trip is to be settled by. */
    @Column(length = 200)
    private String route;

    @Column(length = 300)
    private String towns;

    @Column(name = "days_out")
    private Integer daysOut;

    @Column(name = "settle_by")
    private LocalDate settleBy;

    /** The load's value at cost when the request was approved. */
    @Column(name = "load_value", precision = 18, scale = 2)
    private BigDecimal loadValue;

    /** The Director of Commercial's approval of the load request; withdrawn when the manifest changes. */
    @Column(name = "load_approved_by", length = 50)
    private String loadApprovedBy;

    @Column(name = "load_approved_by_id")
    private Long loadApprovedById;

    @Column(name = "load_approved_at")
    private LocalDateTime loadApprovedAt;

    /** The COO's waiver of the previous trip not yet cleared (BP 7.3 rule 9), with the reason. */
    @Column(name = "previous_override_reason", length = 255)
    private String previousOverrideReason;

    @Column(name = "previous_override_by", length = 50)
    private String previousOverrideBy;

    @Column(name = "previous_override_at")
    private LocalDateTime previousOverrideAt;

    /** Cleared by the Director of Commercial (F-11); the Director of Finance's countersignature closes the trip. */
    @Column(name = "cleared_by", length = 50)
    private String clearedBy;

    @Column(name = "cleared_by_id")
    private Long clearedById;

    @Column(name = "cleared_at")
    private LocalDateTime clearedAt;

    @Column(name = "closed_by", length = 50)
    private String closedBy;

    @OneToMany(mappedBy = "trip", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<TripLine> lines = new LinkedHashSet<>();

    public boolean isPlanned() {
        return status == TripStatus.PLANNED;
    }

    public boolean isDeparted() {
        return status == TripStatus.DEPARTED;
    }

    public boolean isCancelled() {
        return status == TripStatus.CANCELLED;
    }

    public boolean isReturned() {
        return status == TripStatus.RETURNED;
    }

    public boolean isClosed() {
        return status == TripStatus.CLOSED;
    }

    /** On the road or back and not closed: its phone may still sync sales, report breakage and declare cash. */
    public boolean isOpenForPhone() {
        return status == TripStatus.DEPARTED || status == TripStatus.RETURNED;
    }

    public boolean isCashDeclared() {
        return cashDeclaredAt != null;
    }

    /** Km driven, once both readings are in. */
    public Integer getDistanceKm() {
        return odometerStart == null || odometerEnd == null ? null : odometerEnd - odometerStart;
    }

    public boolean isLoadApproved() {
        return loadApprovedAt != null;
    }

    public boolean isCleared() {
        return clearedAt != null;
    }
}
