package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditMask;
import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.ShareableDocument;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : DocumentShareLink.java
 * - Date      : 2026. 10. 27.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The public, read-only link to a business document (digital receipt sharing). One link per
 *               document, created the first time its owner shares it; the random token is the only key, so the
 *               number alone never opens a document. The token is masked in the change log.
 * </pre>
 */
@Entity
@Table(name = "document_share_links")
@AuditedEntity(ref = "documentNumber")
@Getter
@Setter
@NoArgsConstructor
public class DocumentShareLink extends BaseEntity {

    @AuditMask
    @Column(nullable = false, unique = true, length = 64, updatable = false)
    private String token;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 30, updatable = false)
    private ShareableDocument documentType;

    @Column(name = "document_id", nullable = false, updatable = false)
    private UUID documentId;

    @Column(name = "document_number", nullable = false, length = 30, updatable = false)
    private String documentNumber;
}
