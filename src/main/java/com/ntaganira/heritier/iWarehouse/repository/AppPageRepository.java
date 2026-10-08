package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.AppPage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Menu pages are seeded by migrations (one per screen group, SRS 3.3). */
public interface AppPageRepository extends JpaRepository<AppPage, Long> {

    List<AppPage> findAllByOrderBySortOrderAsc();
}
