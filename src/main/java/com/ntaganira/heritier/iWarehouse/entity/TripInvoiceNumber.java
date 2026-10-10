package com.ntaganira.heritier.iWarehouse.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : TripInvoiceNumber.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : An invoice number (MINV) handed to a phone for a trip (SYNC-01), so a sale made offline prints its number at
 *               once. Used by one invoice; a number never used stays on the trip as unused. Not a business record of its
 *               own (the invoice is): not audited.
 * </pre>
 */
@Entity
@Table(name = "trip_invoice_numbers")
@Getter
@Setter
@NoArgsConstructor
public class TripInvoiceNumber {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Version
    private long version;

    @Column(name = "trip_id", nullable = false, updatable = false)
    private UUID tripId;

    @Column(name = "device_id", nullable = false, updatable = false)
    private UUID deviceId;

    @Column(nullable = false, unique = true, length = 30, updatable = false)
    private String number;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private LocalDateTime issuedAt;

    @Column(name = "invoice_id")
    private UUID invoiceId;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    public boolean isUsed() {
        return invoiceId != null;
    }
}
