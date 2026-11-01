package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.Sector;
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
 * - File      : SectorRepository.java
 * - Date      : 2026. 11. 01.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Sectors of the administrative hierarchy dashboard.
 * </pre>
 */
public interface SectorRepository extends JpaRepository<Sector, UUID> {

    List<Sector> findByDistrictIdOrderByCodeAsc(UUID districtId);

    List<Sector> findByCodeStartingWithOrderByCodeAsc(String prefix);

    long countByEnabledTrue();

    long countByCodeStartingWith(String prefix);

    @Query(value = """
            select s from Sector s join fetch s.district d join fetch d.province p
            where (:districtId is null or d.id = :districtId)
              and (:q = '' or lower(s.name) like lower(concat('%', :q, '%'))
                   or s.code like concat(:q, '%'))
              and (:status = ''
                   or (:status = 'VISIBLE' and p.enabled = true and d.enabled = true and s.enabled = true)
                   or (:status = 'HIDDEN' and s.enabled = true and (p.enabled = false or d.enabled = false))
                   or (:status = 'INACTIVE' and s.enabled = false))
            order by s.code
            """,
            countQuery = """
            select count(s) from Sector s join s.district d join d.province p
            where (:districtId is null or d.id = :districtId)
              and (:q = '' or lower(s.name) like lower(concat('%', :q, '%'))
                   or s.code like concat(:q, '%'))
              and (:status = ''
                   or (:status = 'VISIBLE' and p.enabled = true and d.enabled = true and s.enabled = true)
                   or (:status = 'HIDDEN' and s.enabled = true and (p.enabled = false or d.enabled = false))
                   or (:status = 'INACTIVE' and s.enabled = false))
            """)
    Page<Sector> search(@Param("q") String q, @Param("status") String status,
                        @Param("districtId") UUID districtId, Pageable pageable);
}
