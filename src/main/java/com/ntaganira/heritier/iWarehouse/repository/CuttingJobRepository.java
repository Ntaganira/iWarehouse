package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.CuttingJob;
import com.ntaganira.heritier.iWarehouse.enums.CuttingJobStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CuttingJobRepository extends JpaRepository<CuttingJob, UUID>, JpaSpecificationExecutor<CuttingJob> {

    @EntityGraph(attributePaths = {"product", "customer"})
    Page<CuttingJob> findAll(Specification<CuttingJob> spec, Pageable pageable);

    /** A job with its pieces, product and customer. */
    @EntityGraph(attributePaths = {"lines", "product", "customer"})
    Optional<CuttingJob> findDetailedById(UUID id);

    /** Taking a sheet and recording the cut take this lock first, so a job is cut once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from CuttingJob j where j.id = :id")
    Optional<CuttingJob> lockById(@Param("id") UUID id);

    /** Jobs cutting the rest of a job, not cancelled. */
    List<CuttingJob> findByParentJobIdAndStatusNot(UUID parentJobId, CuttingJobStatus status);

    /** The job a unit was taken for or cut by. */
    Optional<CuttingJob> findFirstBySourceUnitIdAndStatusIn(UUID sourceUnitId, Collection<CuttingJobStatus> statuses);

    /** Operators of jobs in a status, by name (yield report filter). */
    @Query("select distinct j.operatorName from CuttingJob j where j.status = :status and j.operatorName is not null"
            + " order by j.operatorName")
    List<String> findOperators(@Param("status") CuttingJobStatus status);

    /** Jobs cut in a period, for the yield report (PRD-09). */
    @EntityGraph(attributePaths = {"product"})
    List<CuttingJob> findByStatusAndCompletedAtGreaterThanEqualAndCompletedAtLessThanOrderByCompletedAtAsc(
            CuttingJobStatus status, LocalDateTime from, LocalDateTime to);
}
