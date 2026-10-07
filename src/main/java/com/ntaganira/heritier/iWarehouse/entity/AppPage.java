package com.ntaganira.heritier.iWarehouse.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** Menu page a role can open, exposed to Spring Security as PAGE_{code}. */
@Entity
@Table(name = "pages")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppPage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String code;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false, length = 100)
    private String module;

    @Column(nullable = false)
    private String path;

    @Column(length = 50)
    private String icon;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private AppPage parent;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Builder.Default
    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
