package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditMask;
import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : Driver.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : A driver (FLT-03): the user account they sign in with, their national ID, driving licence (number,
 *               category, expiry) and the vehicle they usually drive. An expired licence refuses a trip (FLT-04). The
 *               national ID and licence number are personal data (NFR-12, Law 058/2021): pages show them in full only
 *               with PERM_VIEW_DRIVER_DATA and the change log masks them. Deactivated, never deleted.
 * </pre>
 */
@Entity
@Table(name = "drivers")
@AuditedEntity(ref = "username")
@Getter
@Setter
@NoArgsConstructor
public class Driver extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    /** The user's (usernames never change): the reference of the change log. */
    @Column(nullable = false, length = 50, updatable = false)
    private String username;

    @AuditMask
    @Column(name = "national_id", nullable = false, length = 16)
    private String nationalId;

    @AuditMask
    @Column(name = "licence_number", nullable = false, length = 30)
    private String licenceNumber;

    @Column(name = "licence_category", nullable = false, length = 20)
    private String licenceCategory;

    @Column(name = "licence_expiry", nullable = false)
    private LocalDate licenceExpiry;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "default_vehicle_id")
    private Vehicle defaultVehicle;

    @Column(nullable = false)
    private boolean enabled = true;

    /** "•••• 4321": the last four characters, for those who may not see the whole value. */
    public static String masked(String value) {
        if (value == null) {
            return null;
        }
        return "•••• " + (value.length() <= 4 ? value : value.substring(value.length() - 4));
    }
}
