package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.ProcessingService;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProcessingServiceRepository extends JpaRepository<ProcessingService, UUID> {

    List<ProcessingService> findAllByOrderByEnabledDescCodeAsc();

    List<ProcessingService> findByEnabledTrueOrderByCodeAsc();

    boolean existsByCode(String code);
}
