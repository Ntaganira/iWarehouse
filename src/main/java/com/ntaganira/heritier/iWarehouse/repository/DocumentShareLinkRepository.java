package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.DocumentShareLink;
import com.ntaganira.heritier.iWarehouse.enums.ShareableDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : DocumentShareLinkRepository.java
 * - Date      : 2026. 10. 27.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The public links to business documents, looked up by their token or by the document they share.
 * </pre>
 */
public interface DocumentShareLinkRepository extends JpaRepository<DocumentShareLink, UUID> {

    Optional<DocumentShareLink> findByToken(String token);

    Optional<DocumentShareLink> findByDocumentTypeAndDocumentId(ShareableDocument type, UUID documentId);

    boolean existsByToken(String token);
}
