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
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : TripPriceList.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : A price list as it stood when a trip's prices were first downloaded (MPOS-04): whether its prices include
 *               VAT and the smallest area a piece is charged. With {@link TripPrice}, what the phones and the server price a
 *               mobile sale with all day. Append-only (trg_trip_price_lists_append_only).
 * </pre>
 */
@Entity
@Immutable
@Table(name = "trip_price_lists")
@IdClass(TripPriceList.Key.class)
@Getter
@Setter
@NoArgsConstructor
public class TripPriceList {

    @Id
    @Column(name = "trip_id")
    private UUID tripId;

    @Id
    @Column(name = "price_list_id")
    private UUID priceListId;

    @Column(nullable = false, length = 20)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "default_list", nullable = false)
    private boolean defaultList;

    @Column(name = "prices_include_vat", nullable = false)
    private boolean pricesIncludeVat;

    @Column(name = "min_chargeable_m2", nullable = false, precision = 10, scale = 4)
    private BigDecimal minChargeableM2;

    @Column(name = "taken_at", nullable = false)
    private LocalDateTime takenAt;

    /** The key: trip and list. */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private UUID tripId;
        private UUID priceListId;
    }
}
