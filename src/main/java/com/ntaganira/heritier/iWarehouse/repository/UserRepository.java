package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    boolean existsByUsernameIgnoreCase(String username);

    boolean existsByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCaseAndIdNot(String email, Long id);

    List<User> findByRoles_IdOrderByFullNameAsc(Long roleId);

    /** Enabled users holding the role, other than excludeId (keeps at least one administrator). */
    @Query("select count(u) from User u join u.roles r"
            + " where r.code = :code and r.enabled = true and u.enabled = true and u.id <> :excludeId")
    long countEnabledWithRoleExcept(@Param("code") String code, @Param("excludeId") Long excludeId);

    /** Enabled users holding a permission through an enabled role: who an alert goes to (RPT-06). */
    @Query("select distinct u from User u join u.roles r join r.permissions p"
            + " where u.enabled = true and r.enabled = true and p.enabled = true and p.code = :code")
    List<User> findActiveHolding(@Param("code") String code);

    /** Enabled users not registered as drivers yet (FLT-03), by name. */
    @Query("select u from User u where u.enabled = true"
            + " and not exists (select d.id from Driver d where d.user = u) order by u.fullName")
    List<User> findDriverCandidates();
}
