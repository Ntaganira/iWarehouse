package com.ntaganira.heritier.iWarehouse.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Immutable;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : TripPrice.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : A glass's price per m² on a list, as it stood when the trip's prices were first downloaded (MPOS-04).
 *               Only the glass on board is kept. Append-only (trg_trip_prices_append_only).
 * </pre>
 */
@Entity
@Immutable
@Table(name = "trip_prices")
@IdClass(TripPrice.Key.class)
@Getter
@Setter
@NoArgsConstructor
public class TripPrice {

    @Id
    @Column(name = "trip_id")
    private UUID tripId;

    @Id
    @Column(name = "price_list_id")
    private UUID priceListId;

    @Id
    @Column(name = "product_id")
    private UUID productId;

    @Column(name = "price_per_m2", nullable = false, precision = 18, scale = 2)
    private BigDecimal pricePerM2;

    /** The key: trip, list and glass. */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private UUID tripId;
        private UUID priceListId;
        private UUID productId;
    }
}
