package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.DataChangeLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Read-only: rows are written only by DataChangeEventListener. No save/delete methods on purpose. */
public interface DataChangeLogRepository extends Repository<DataChangeLog, Long>, JpaSpecificationExecutor<DataChangeLog> {

    Optional<DataChangeLog> findById(Long id);

    Page<DataChangeLog> findByEntityTypeAndEntityIdOrderByServerTimeDesc(String entityType, String entityId, Pageable pageable);

    Page<DataChangeLog> findByEntityTypeInOrderByServerTimeDescIdDesc(Collection<String> entityTypes, Pageable pageable);

    Page<DataChangeLog> findByEntityTypeInAndEntityIdInOrderByServerTimeDescIdDesc(Collection<String> entityTypes,
                                                                                  Collection<String> entityIds, Pageable pageable);

    List<DataChangeLog> findByRequestIdOrderByIdAsc(String requestId);

    @Query("select distinct d.entityType from DataChangeLog d order by d.entityType")
    List<String> findDistinctEntityTypes();

    long countByServerTimeBetween(LocalDateTime from, LocalDateTime to);

    /** Rows of (yyyy-MM-dd, count) for the dashboard chart. */
    @Query(value = "select to_char(server_time, 'YYYY-MM-DD'), count(*) from data_change_logs"
            + " where server_time >= :since group by 1", nativeQuery = true)
    List<Object[]> countPerDaySince(@Param("since") LocalDateTime since);

    List<DataChangeLog> findTop6ByOrderByServerTimeDescIdDesc();

    /**
     * History of a record and of its child records (e.g. a purchase order and its lines), newest first.
     * Children of each type are found by the parent id in their snapshot, so deleted children are included; the documents
     * kept on the record (Attachment, by ownerId) are children of every record.
     */
    @Query(value = "select * from data_change_logs d where (d.entity_type = :type and d.entity_id = :id)"
            + " or (d.entity_type in (:childTypes) and (d.after_data ->> :parentField = :id or d.before_data ->> :parentField = :id))"
            + " or (d.entity_type = 'Attachment' and (d.after_data ->> 'ownerId' = :id or d.before_data ->> 'ownerId' = :id))"
            + " order by d.server_time desc, d.id desc",
            countQuery = "select count(*) from data_change_logs d where (d.entity_type = :type and d.entity_id = :id)"
                    + " or (d.entity_type in (:childTypes) and (d.after_data ->> :parentField = :id or d.before_data ->> :parentField = :id))"
                    + " or (d.entity_type = 'Attachment' and (d.after_data ->> 'ownerId' = :id or d.before_data ->> 'ownerId' = :id))",
            nativeQuery = true)
    Page<DataChangeLog> findWithChildren(@Param("type") String type, @Param("id") String id,
                                         @Param("childTypes") Collection<String> childTypes, @Param("parentField") String parentField,
                                         Pageable pageable);
}
