package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.Province;
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
 * - File      : ProvinceRepository.java
 * - Date      : 2026. 11. 01.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Provinces of the administrative hierarchy dashboard.
 * </pre>
 */
public interface ProvinceRepository extends JpaRepository<Province, UUID> {

    List<Province> findByCodeStartingWithOrderByCodeAsc(String prefix);

    List<Province> findAllByOrderByCodeAsc();

    long countByEnabledTrue();

    long countByCodeStartingWith(String prefix);

    @Query("""
            select p from Province p
            where (:q = '' or lower(p.name) like lower(concat('%', :q, '%'))
                   or p.code like concat(:q, '%'))
              and (:status = ''
                   or (:status = 'VISIBLE' and p.enabled = true)
                   or (:status = 'INACTIVE' and p.enabled = false))
            order by p.code
            """)
    Page<Province> search(@Param("q") String q, @Param("status") String status, Pageable pageable);
}
