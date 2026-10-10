package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : TripFuel.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : Fuel bought for a trip (FLT-12): the day, litres, what it cost in RWF, the station and the receipt. Kept
 *               for the fleet reports; paying for it is booked by the accountant. Taken off with a reason while the trip
 *               is not cancelled.
 * </pre>
 */
@Entity
@Table(name = "trip_fuel")
@AuditedEntity(ref = "filledOn")
@Getter
@Setter
@NoArgsConstructor
public class TripFuel extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", nullable = false, updatable = false)
    private Trip trip;

    @Column(name = "filled_on", nullable = false)
    private LocalDate filledOn;

    @Column(nullable = false, precision = 8, scale = 2)
    private BigDecimal litres;

    /** RWF. */
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(length = 80)
    private String station;

    @Column(length = 255)
    private String note;
}
