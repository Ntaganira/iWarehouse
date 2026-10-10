package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.Attachment;
import com.ntaganira.heritier.iWarehouse.enums.AttachmentOwner;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : AttachmentRepository.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : The documents kept on a record, newest first; removed ones are left out.
 * </pre>
 */
public interface AttachmentRepository extends JpaRepository<Attachment, UUID> {

    Page<Attachment> findByOwnerTypeAndOwnerIdAndRemovedAtIsNullOrderByCreatedAtDesc(AttachmentOwner ownerType, UUID ownerId, Pageable pageable);

    long countByOwnerTypeAndOwnerIdAndRemovedAtIsNull(AttachmentOwner ownerType, UUID ownerId);
}
