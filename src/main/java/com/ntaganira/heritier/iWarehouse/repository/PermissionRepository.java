package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.Permission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/** Permissions are seeded by module migrations and only renamed or switched on/off from the UI. */
public interface PermissionRepository extends JpaRepository<Permission, Long>, JpaSpecificationExecutor<Permission> {

    List<Permission> findAllByOrderByModuleAscCodeAsc();

    @Query("select distinct p.module from Permission p order by p.module")
    List<String> findDistinctModules();
}
