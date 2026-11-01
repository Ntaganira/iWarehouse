package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.Cell;
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
 * - File      : CellRepository.java
 * - Date      : 2026. 11. 01.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Cells of the administrative hierarchy dashboard.
 * </pre>
 */
public interface CellRepository extends JpaRepository<Cell, UUID> {

    List<Cell> findBySectorIdOrderByCodeAsc(UUID sectorId);

    List<Cell> findByCodeStartingWithOrderByCodeAsc(String prefix);

    long countByEnabledTrue();

    long countByCodeStartingWith(String prefix);

    @Query(value = """
            select c from Cell c join fetch c.sector s join fetch s.district d join fetch d.province p
            where (:sectorId is null or s.id = :sectorId)
              and (:q = '' or lower(c.name) like lower(concat('%', :q, '%'))
                   or c.code like concat(:q, '%'))
              and (:status = ''
                   or (:status = 'VISIBLE' and p.enabled = true and d.enabled = true and s.enabled = true and c.enabled = true)
                   or (:status = 'HIDDEN' and c.enabled = true and (p.enabled = false or d.enabled = false or s.enabled = false))
                   or (:status = 'INACTIVE' and c.enabled = false))
            order by c.code
            """,
            countQuery = """
            select count(c) from Cell c join c.sector s join s.district d join d.province p
            where (:sectorId is null or s.id = :sectorId)
              and (:q = '' or lower(c.name) like lower(concat('%', :q, '%'))
                   or c.code like concat(:q, '%'))
              and (:status = ''
                   or (:status = 'VISIBLE' and p.enabled = true and d.enabled = true and s.enabled = true and c.enabled = true)
                   or (:status = 'HIDDEN' and c.enabled = true and (p.enabled = false or d.enabled = false or s.enabled = false))
                   or (:status = 'INACTIVE' and c.enabled = false))
            """)
    Page<Cell> search(@Param("q") String q, @Param("status") String status,
                      @Param("sectorId") UUID sectorId, Pageable pageable);
}
