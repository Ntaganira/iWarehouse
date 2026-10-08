package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.ActivityLog;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface ActivityLogRepository extends JpaRepository<ActivityLog, Long>, JpaSpecificationExecutor<ActivityLog> {

    @Query("select distinct l.module from ActivityLog l order by l.module")
    List<String> findDistinctModules();

    @Query("select distinct l.action from ActivityLog l order by l.action")
    List<String> findDistinctActions();

    long countByCreatedAtBetween(LocalDateTime from, LocalDateTime to);

    long countByStatusAndCreatedAtBetween(ActivityStatus status, LocalDateTime from, LocalDateTime to);

    @Query("select count(distinct l.userId) from ActivityLog l where l.createdAt between :from and :to")
    long countDistinctUsersBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** Rows of (yyyy-MM-dd, count) for the dashboard chart. */
    @Query(value = "select to_char(created_at, 'YYYY-MM-DD'), count(*) from activity_logs"
            + " where created_at >= :since group by 1", nativeQuery = true)
    List<Object[]> countPerDaySince(@Param("since") LocalDateTime since);

    List<ActivityLog> findTop6ByOrderByCreatedAtDescIdDesc();

    List<ActivityLog> findTop6ByUserIdOrderByCreatedAtDescIdDesc(Long userId);
}
