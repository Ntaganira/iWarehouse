package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.StockCountParticipant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : StockCountParticipantRepository.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : Who took part in a count and who signed it (IMP 15.1, PF-06 step 6).
 * </pre>
 */
public interface StockCountParticipantRepository extends JpaRepository<StockCountParticipant, UUID> {

    List<StockCountParticipant> findByCountIdOrderByCreatedAtAscNameAsc(UUID countId);

    Optional<StockCountParticipant> findByCountIdAndUserId(UUID countId, Long userId);

    /** Participants of some counts in one role (external auditors at the annual count, M.04). */
    List<StockCountParticipant> findByCountIdInAndRole(java.util.Collection<UUID> countIds, com.ntaganira.heritier.iWarehouse.enums.CountRole role);
}
