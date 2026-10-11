package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.Location;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LocationRepository extends JpaRepository<Location, UUID> {

    List<Location> findAllByOrderByCodeAsc();

    /** The header's search: places whose code or name holds the text (the tree has no search of its own). No collection is fetched. */
    @Query("select l from Location l where upper(l.code) like concat('%', upper(:q), '%') or upper(l.name) like concat('%', upper(:q), '%') order by l.code")
    Page<Location> search(@Param("q") String q, Pageable pageable);

    List<Location> findByParentIdOrderByCodeAsc(UUID parentId);

    long countByParentIdAndEnabledTrue(UUID parentId);

    Optional<Location> findByCode(String code);

    boolean existsByCode(String code);

    boolean existsByCodeAndIdNot(String code, UUID id);

    @Query("select l.code from Location l")
    List<String> findAllCodes();
}
