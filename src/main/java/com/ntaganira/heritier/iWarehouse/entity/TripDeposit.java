package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.enums.PaymentMethod;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : TripDeposit.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Collections a driver banked or sent by mobile money during a trip (BP 7.3 rule 4, F-10 C, SDP 10): from the phone (its clientId: sent twice, taken once) or the office, with the slip's or the transfer's reference. Posted when recorded (Dr Bank or Mobile Money / Cr Driver Float naming the driver): it is no longer on the float. Append-only (trg_trip_deposits_append_only).
 * </pre>
 */
@Entity
@Immutable
@Table(name = "trip_deposits")
@Getter
@Setter
@NoArgsConstructor
public class TripDeposit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 30)
    private String number;

    @Column(name = "client_id", unique = true)
    private UUID clientId;

    @Column(name = "trip_id", nullable = false)
    private UUID tripId;

    @Column(name = "driver_id", nullable = false)
    private UUID driverId;

    /** BANK_TRANSFER (a bank deposit) or MOBILE_MONEY. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private PaymentMethod method;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 60)
    private String reference;

    @Column(name = "deposited_on", nullable = false)
    private LocalDate depositedOn;

    /** PHONE or OFFICE. */
    @Column(name = "recorded_via", nullable = false, length = 10)
    private String recordedVia;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false, length = 50)
    private String username;
}
