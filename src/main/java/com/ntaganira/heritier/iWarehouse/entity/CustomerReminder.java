package com.ntaganira.heritier.iWarehouse.entity;

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
 * - File      : CustomerReminder.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : A payment reminder issued to a customer (BP 5.1, dunning): its level (1 a reminder, 2 a second
 *               reminder, 3 a final notice, by how long the oldest charge is overdue), the balance and the overdue amount
 *               on the day, the letter printed from it. Append-only (trg_customer_reminders_append_only).
 * </pre>
 */
@Entity
@Immutable
@Table(name = "customer_reminders")
@Getter
@Setter
@NoArgsConstructor
public class CustomerReminder {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 30)
    private String number;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Column(nullable = false)
    private int level;

    @Column(name = "as_of", nullable = false)
    private LocalDate asOf;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal balance;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal overdue;

    @Column(name = "oldest_days", nullable = false)
    private int oldestDays;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false, length = 50)
    private String username;
}
