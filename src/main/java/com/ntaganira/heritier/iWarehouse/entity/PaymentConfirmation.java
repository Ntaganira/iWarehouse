package com.ntaganira.heritier.iWarehouse.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Immutable;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : PaymentConfirmation.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : Finance's confirmation that a bank transfer paid on an invoice has arrived (SDP 14): goods paid that way
 *               leave once every transfer of their invoice is confirmed. Once per payment, append-only
 *               (trg_payment_confirmations_append_only).
 * </pre>
 */
@Entity
@Immutable
@Table(name = "payment_confirmations")
@Getter
@Setter
@NoArgsConstructor
public class PaymentConfirmation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "sales_payment_id", nullable = false)
    private UUID salesPaymentId;

    @Column(name = "invoice_id", nullable = false)
    private UUID invoiceId;

    @Column(name = "bank_reference", length = 60)
    private String bankReference;

    @Column(length = 255)
    private String note;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false, length = 50)
    private String username;
}
