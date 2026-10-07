package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.ActivityLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;

public interface ActivityLogRepository extends JpaRepository<ActivityLog, Long>, JpaSpecificationExecutor<ActivityLog> {

    @Query("select distinct l.module from ActivityLog l order by l.module")
    List<String> findDistinctModules();

    @Query("select distinct l.action from ActivityLog l order by l.action")
    List<String> findDistinctActions();

    long countByCreatedAtAfter(LocalDateTime since);

    long countByStatusAndCreatedAtAfter(com.ntaganira.heritier.iWarehouse.enums.ActivityStatus status, LocalDateTime since);
}
