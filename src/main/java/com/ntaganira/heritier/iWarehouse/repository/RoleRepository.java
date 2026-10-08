package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface RoleRepository extends JpaRepository<Role, Long>, JpaSpecificationExecutor<Role> {

    boolean existsByCodeIgnoreCase(String code);

    List<Role> findAllByOrderByCodeAsc();

    /** Rows of (role id, number of users holding it). */
    @Query("select r.id, count(u) from User u join u.roles r group by r.id")
    List<Object[]> countUsersPerRole();

    /** Rows of (permission id, number of roles granting it). */
    @Query("select p.id, count(r) from Role r join r.permissions p group by p.id")
    List<Object[]> countRolesPerPermission();
}
