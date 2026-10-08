package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.CuttingJobLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface CuttingJobLineRepository extends JpaRepository<CuttingJobLine, UUID> {

    /** The pieces wanted of a page of jobs, in one query. */
    List<CuttingJobLine> findByJob_IdIn(Collection<UUID> jobIds);
}
