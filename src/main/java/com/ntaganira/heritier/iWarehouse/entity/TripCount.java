package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.enums.TripCountKind;
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
 * - File      : TripCount.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : A count of the glass on a vehicle on the road (SDP 6, BP 7.3 rule 10, F-08): the driver's daily count from the phone or a supervisor's unannounced one; what was expected on board, found, missing and not expected. Append-only (trg_trip_counts_append_only), its units in trip_count_lines.
 * </pre>
 */
@Entity
@Immutable
@Table(name = "trip_counts")
@Getter
@Setter
@NoArgsConstructor
public class TripCount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "trip_id", nullable = false)
    private UUID tripId;

    @Column(name = "client_id", unique = true)
    private UUID clientId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TripCountKind kind;

    @Column(name = "counted_at", nullable = false)
    private LocalDateTime countedAt;

    @Column(nullable = false)
    private int expected;

    @Column(nullable = false)
    private int found;

    @Column(nullable = false)
    private int missing;

    @Column(nullable = false)
    private int extra;

    @Column(length = 255)
    private String note;

    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false, length = 50)
    private String username;

    public boolean isClean() {
        return missing == 0 && extra == 0;
    }
}
