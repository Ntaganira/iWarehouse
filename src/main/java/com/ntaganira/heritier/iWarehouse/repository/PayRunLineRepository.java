package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.dto.PayslipRow;
import com.ntaganira.heritier.iWarehouse.entity.PayRunLine;
import com.ntaganira.heritier.iWarehouse.enums.PayRunStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : PayRunLineRepository.java
 * - Date      : 2026. 10. 11.
 * - User      : Hntaganira
 * - Desc      : The lines of pay runs, and the payslips of an employee or of the user signed in.
 * </pre>
 */
public interface PayRunLineRepository extends JpaRepository<PayRunLine, UUID> {

    List<PayRunLine> findByPayRunIdOrderByEmployeeCode(UUID payRunId);

    boolean existsByPayRunIdAndEmployee_Id(UUID payRunId, UUID employeeId);

    /** An employee's payslips, newest month first. */
    @Query(value = "select new com.ntaganira.heritier.iWarehouse.dto.PayslipRow(l.id, r.id, r.number, r.period, r.status, l.gross, l.netPay) "
            + "from PayRunLine l, PayRun r where r.id = l.payRunId and l.employee.id = :employeeId and r.status in :statuses order by r.period desc",
            countQuery = "select count(l) from PayRunLine l, PayRun r where r.id = l.payRunId and l.employee.id = :employeeId and r.status in :statuses")
    Page<PayslipRow> findPayslipsOfEmployee(@Param("employeeId") UUID employeeId, @Param("statuses") Collection<PayRunStatus> statuses,
                                            Pageable pageable);

    /** The payslips of the employee a user signs in as, newest month first. */
    @Query(value = "select new com.ntaganira.heritier.iWarehouse.dto.PayslipRow(l.id, r.id, r.number, r.period, r.status, l.gross, l.netPay) "
            + "from PayRunLine l, PayRun r where r.id = l.payRunId and l.employee.user.id = :userId and r.status in :statuses order by r.period desc",
            countQuery = "select count(l) from PayRunLine l, PayRun r where r.id = l.payRunId and l.employee.user.id = :userId and r.status in :statuses")
    Page<PayslipRow> findPayslipsOfUser(@Param("userId") Long userId, @Param("statuses") Collection<PayRunStatus> statuses, Pageable pageable);

    /** A line of the employee this user signs in as. */
    @Query("select l from PayRunLine l where l.id = :id and l.employee.user.id = :userId")
    Optional<PayRunLine> findOfUser(@Param("id") UUID id, @Param("userId") Long userId);
}
