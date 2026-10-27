package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.AccessReview;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : AccessReviewRepository.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Access reviews: newest first, and the latest.
 * </pre>
 */
public interface AccessReviewRepository extends JpaRepository<AccessReview, UUID> {

    Page<AccessReview> findAllByOrderByReviewedAtDesc(Pageable pageable);

    Optional<AccessReview> findFirstByOrderByReviewedAtDesc();
}
