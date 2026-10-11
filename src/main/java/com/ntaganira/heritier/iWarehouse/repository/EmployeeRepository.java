package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.Employee;
import com.ntaganira.heritier.iWarehouse.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : EmployeeRepository.java
 * - Date      : 2026. 10. 11.
 * - User      : Hntaganira
 * - Desc      : Employees (payroll) with the user they sign in with.
 * </pre>
 */
public interface EmployeeRepository extends JpaRepository<Employee, UUID>, JpaSpecificationExecutor<Employee> {

    @EntityGraph(attributePaths = {"user"})
    Page<Employee> findAll(Specification<Employee> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"user"})
    Optional<Employee> findDetailedById(UUID id);

    /** Those paid in a month: hired by its last day and not gone before its first, by code. */
    @EntityGraph(attributePaths = {"user"})
    @Query("select e from Employee e where e.hiredOn <= :end and (e.leftOn is null or e.leftOn >= :start) order by e.code")
    List<Employee> findWorkingIn(@Param("start") LocalDate start, @Param("end") LocalDate end);

    @EntityGraph(attributePaths = {"user"})
    Optional<Employee> findByUser_Id(Long userId);

    boolean existsByNationalId(String nationalId);

    boolean existsByNationalIdAndIdNot(String nationalId, UUID id);

    boolean existsByUser_Id(Long userId);

    boolean existsByUser_IdAndIdNot(Long userId, UUID id);

    long countByLeftOnIsNull();

    /** Users an employee can be linked to: enabled and no employee's yet. */
    @Query("select u from User u where u.enabled = true and not exists (select e from Employee e where e.user = u) order by u.fullName")
    List<User> findLinkableUsers();
}
