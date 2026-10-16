package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.CuttingJobOutput;
import org.springframework.data.repository.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Append-only: save and read, no update or delete methods on purpose (PRD-03..08). */
public interface CuttingJobOutputRepository extends Repository<CuttingJobOutput, UUID> {

    CuttingJobOutput save(CuttingJobOutput output);

    List<CuttingJobOutput> findByCuttingJobIdOrderByCreatedAtAscIdAsc(UUID cuttingJobId);

    List<CuttingJobOutput> findByCuttingJobIdIn(Collection<UUID> cuttingJobIds);

    /** The cut a unit came out of. */
    Optional<CuttingJobOutput> findByStockUnitId(UUID stockUnitId);

    /** Outputs of a kind recorded in a period (glass broken while cutting, for the damage register). */
    List<CuttingJobOutput> findByKindAndCreatedAtBetweenOrderByCreatedAtAsc(com.ntaganira.heritier.iWarehouse.enums.CuttingOutputKind kind,
                                                                         java.time.LocalDateTime from, java.time.LocalDateTime to);
}
