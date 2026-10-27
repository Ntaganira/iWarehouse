package com.ntaganira.heritier.iWarehouse.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Immutable;

import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : SiteTransferLine.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : What a transfer between sites asks for: so many sheets of a glass, of a size when it matters. Written with
 *               the request and never changed.
 * </pre>
 */
@Entity
@Table(name = "site_transfer_lines")
@Immutable
@Getter
@Setter
@NoArgsConstructor
public class SiteTransferLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "transfer_id", nullable = false)
    private UUID transferId;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "width_mm")
    private Integer widthMm;

    @Column(name = "height_mm")
    private Integer heightMm;

    @Column(nullable = false)
    private int quantity;

    public boolean isSized() {
        return widthMm != null;
    }
}
