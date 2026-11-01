package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.Village;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : VillageRepository.java
 * - Date      : 2026. 11. 01.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Villages of the administrative hierarchy dashboard; the 8-digit code is the official one.
 * </pre>
 */
public interface VillageRepository extends JpaRepository<Village, UUID> {

    List<Village> findByCellIdOrderByCodeAsc(UUID cellId);

    List<Village> findByCodeStartingWithOrderByCodeAsc(String prefix);

    long countByEnabledTrue();

    long countByCodeStartingWith(String prefix);

    @Query(value = """
            select v from Village v join fetch v.cell c join fetch c.sector s
                   join fetch s.district d join fetch d.province p
            where (:cellId is null or c.id = :cellId)
              and (:q = '' or lower(v.name) like lower(concat('%', :q, '%'))
                   or v.code like concat(:q, '%'))
              and (:status = ''
                   or (:status = 'VISIBLE' and p.enabled = true and d.enabled = true
                       and s.enabled = true and c.enabled = true and v.enabled = true)
                   or (:status = 'HIDDEN' and v.enabled = true
                       and (p.enabled = false or d.enabled = false or s.enabled = false or c.enabled = false))
                   or (:status = 'INACTIVE' and v.enabled = false))
            order by v.code
            """,
            countQuery = """
            select count(v) from Village v join v.cell c join c.sector s
                   join s.district d join d.province p
            where (:cellId is null or c.id = :cellId)
              and (:q = '' or lower(v.name) like lower(concat('%', :q, '%'))
                   or v.code like concat(:q, '%'))
              and (:status = ''
                   or (:status = 'VISIBLE' and p.enabled = true and d.enabled = true
                       and s.enabled = true and c.enabled = true and v.enabled = true)
                   or (:status = 'HIDDEN' and v.enabled = true
                       and (p.enabled = false or d.enabled = false or s.enabled = false or c.enabled = false))
                   or (:status = 'INACTIVE' and v.enabled = false))
            """)
    Page<Village> search(@Param("q") String q, @Param("status") String status,
                         @Param("cellId") UUID cellId, Pageable pageable);
}
