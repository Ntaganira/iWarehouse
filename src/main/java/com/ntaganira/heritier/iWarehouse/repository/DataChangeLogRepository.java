package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.DataChangeLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Read-only: rows are written only by DataChangeEventListener. No save/delete methods on purpose. */
public interface DataChangeLogRepository extends Repository<DataChangeLog, Long>, JpaSpecificationExecutor<DataChangeLog> {

    Optional<DataChangeLog> findById(Long id);

    Page<DataChangeLog> findByEntityTypeAndEntityIdOrderByServerTimeDesc(String entityType, String entityId, Pageable pageable);

    List<DataChangeLog> findByRequestIdOrderByIdAsc(String requestId);

    @Query("select distinct d.entityType from DataChangeLog d order by d.entityType")
    List<String> findDistinctEntityTypes();

    long countByServerTimeAfter(LocalDateTime since);
}
