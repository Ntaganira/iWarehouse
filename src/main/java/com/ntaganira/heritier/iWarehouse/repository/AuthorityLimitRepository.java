package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.AuthorityLimit;
import com.ntaganira.heritier.iWarehouse.enums.AuthorityDecision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : AuthorityLimitRepository.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : The Delegation of Authority's cells: a decision's (one per office), or all of them.
 * </pre>
 */
public interface AuthorityLimitRepository extends JpaRepository<AuthorityLimit, UUID> {

    List<AuthorityLimit> findByDecision(AuthorityDecision decision);
}
