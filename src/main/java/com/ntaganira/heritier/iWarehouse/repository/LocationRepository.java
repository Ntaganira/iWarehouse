package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.Location;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LocationRepository extends JpaRepository<Location, UUID> {

    List<Location> findAllByOrderByCodeAsc();

    List<Location> findByParentIdOrderByCodeAsc(UUID parentId);

    long countByParentIdAndEnabledTrue(UUID parentId);

    Optional<Location> findByCode(String code);

    boolean existsByCode(String code);

    boolean existsByCodeAndIdNot(String code, UUID id);

    @Query("select l.code from Location l")
    List<String> findAllCodes();
}
