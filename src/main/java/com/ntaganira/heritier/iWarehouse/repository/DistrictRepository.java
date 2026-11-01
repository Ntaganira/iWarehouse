package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.District;
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
 * - File      : DistrictRepository.java
 * - Date      : 2026. 11. 01.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Districts of the administrative hierarchy dashboard.
 * </pre>
 */
public interface DistrictRepository extends JpaRepository<District, UUID> {

    List<District> findByProvinceIdOrderByCodeAsc(UUID provinceId);

    List<District> findByCodeStartingWithOrderByCodeAsc(String prefix);

    long countByEnabledTrue();

    long countByCodeStartingWith(String prefix);

    @Query(value = """
            select d from District d join fetch d.province p
            where (:provinceId is null or p.id = :provinceId)
              and (:q = '' or lower(d.name) like lower(concat('%', :q, '%'))
                   or d.code like concat(:q, '%'))
              and (:status = ''
                   or (:status = 'VISIBLE' and p.enabled = true and d.enabled = true)
                   or (:status = 'HIDDEN' and p.enabled = false and d.enabled = true)
                   or (:status = 'INACTIVE' and d.enabled = false))
            order by d.code
            """,
            countQuery = """
            select count(d) from District d join d.province p
            where (:provinceId is null or p.id = :provinceId)
              and (:q = '' or lower(d.name) like lower(concat('%', :q, '%'))
                   or d.code like concat(:q, '%'))
              and (:status = ''
                   or (:status = 'VISIBLE' and p.enabled = true and d.enabled = true)
                   or (:status = 'HIDDEN' and p.enabled = false and d.enabled = true)
                   or (:status = 'INACTIVE' and d.enabled = false))
            """)
    Page<District> search(@Param("q") String q, @Param("status") String status,
                          @Param("provinceId") UUID provinceId, Pageable pageable);
}
