package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.dto.EmployeeDto;
import com.ntaganira.heritier.iWarehouse.dto.PayslipRow;
import com.ntaganira.heritier.iWarehouse.entity.Driver;
import com.ntaganira.heritier.iWarehouse.entity.Employee;
import com.ntaganira.heritier.iWarehouse.entity.User;
import com.ntaganira.heritier.iWarehouse.enums.DocumentType;
import com.ntaganira.heritier.iWarehouse.enums.PayRunStatus;
import com.ntaganira.heritier.iWarehouse.enums.SalaryPayMethod;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.DriverRepository;
import com.ntaganira.heritier.iWarehouse.repository.EmployeeRepository;
import com.ntaganira.heritier.iWarehouse.repository.PayRunLineRepository;
import com.ntaganira.heritier.iWarehouse.repository.UserRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : EmployeeService.java
 * - Date      : 2026. 10. 11.
 * - User      : Hntaganira
 * - Desc      : Employees (payroll): their code (EMP-WH-0001), salary, allowances, how PAYE is withheld and how they are
 *               paid. One national ID, one user each; a bank or mobile-money payment needs the account. An employee who
 *               left keeps their record (never deleted) and leaves the runs of later months.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class EmployeeService {

    static final List<PayRunStatus> PAID_OUT = List.of(PayRunStatus.POSTED, PayRunStatus.PAID);

    private final EmployeeRepository repo;
    private final UserRepository userRepo;
    private final DriverRepository driverRepo;
    private final PayRunLineRepository lineRepo;
    private final DocumentNumberService numbers;

    public EmployeeService(EmployeeRepository repo, UserRepository userRepo, DriverRepository driverRepo, PayRunLineRepository lineRepo,
                           DocumentNumberService numbers) {
        this.repo = repo;
        this.userRepo = userRepo;
        this.driverRepo = driverRepo;
        this.lineRepo = lineRepo;
        this.numbers = numbers;
    }

    public Page<Employee> findPage(String search, String status, int page, int size) {
        Specification<Employee> spec = (root, query, cb) -> {
            Predicate p = cb.conjunction();
            if (StringUtils.hasText(search)) {
                String term = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                p = cb.and(p, cb.or(cb.like(cb.lower(root.get("code")), term), cb.like(cb.lower(root.get("fullName")), term),
                        cb.like(cb.lower(root.get("jobTitle")), term)));
            }
            if ("active".equals(status)) {
                p = cb.and(p, cb.isNull(root.get("leftOn")));
            } else if ("left".equals(status)) {
                p = cb.and(p, cb.isNotNull(root.get("leftOn")));
            }
            return p;
        };
        return repo.findAll(spec, PageRequest.of(page, size, Sort.by("code")));
    }

    public Employee findDetailed(UUID id) {
        return repo.findDetailedById(id).orElseThrow(() -> new NotFoundException("Employee", id));
    }

    public long activeCount() {
        return repo.countByLeftOnIsNull();
    }

    /** Users the form offers: enabled and no other employee's; the employee's own user stays in the list. */
    public List<User> linkableUsers(Employee employee) {
        List<User> users = new ArrayList<>(repo.findLinkableUsers());
        if (employee != null && employee.getUser() != null) {
            users.add(employee.getUser());
            users.sort(Comparator.comparing(User::getFullName, String.CASE_INSENSITIVE_ORDER));
        }
        return users;
    }

    /** The driver record of the employee's user, when they drive. */
    public Optional<Driver> driverOf(Employee employee) {
        return employee.getUser() == null ? Optional.empty() : driverRepo.findByUser_Id(employee.getUser().getId());
    }

    /** Their payslips (posted and paid runs), newest month first. */
    public Page<PayslipRow> payslips(UUID employeeId, int page, int size) {
        return lineRepo.findPayslipsOfEmployee(employeeId, PAID_OUT, PageRequest.of(page, size));
    }

    public EmployeeDto form(Employee e) {
        EmployeeDto dto = new EmployeeDto();
        dto.setId(e.getId());
        dto.setFullName(e.getFullName());
        dto.setUserId(e.getUser() == null ? null : e.getUser().getId());
        dto.setNationalId(e.getNationalId());
        dto.setRssbNumber(e.getRssbNumber());
        dto.setJobTitle(e.getJobTitle());
        dto.setHiredOn(e.getHiredOn());
        dto.setLeftOn(e.getLeftOn());
        dto.setEmployment(e.getEmployment());
        dto.setBaseSalary(whole(e.getBaseSalary()));
        dto.setTransportAllowance(whole(e.getTransportAllowance()));
        dto.setHousingAllowance(whole(e.getHousingAllowance()));
        dto.setOtherAllowance(whole(e.getOtherAllowance()));
        dto.setPayMethod(e.getPayMethod());
        dto.setBankName(e.getBankName());
        dto.setAccountNumber(e.getAccountNumber());
        return dto;
    }

    @Transactional
    public Employee create(EmployeeDto dto) {
        if (repo.existsByNationalId(dto.getNationalId())) {
            throw BusinessException.onField("nationalId", "employee.nationalId.taken");
        }
        Employee employee = new Employee();
        employee.setUser(user(dto.getUserId(), null));
        apply(employee, dto);
        employee.setCode(numbers.next(DocumentType.EMPLOYEE));
        return repo.save(employee);
    }

    @Transactional
    public Employee update(UUID id, EmployeeDto dto) {
        Employee employee = findDetailed(id);
        if (repo.existsByNationalIdAndIdNot(dto.getNationalId(), id)) {
            throw BusinessException.onField("nationalId", "employee.nationalId.taken");
        }
        employee.setUser(user(dto.getUserId(), id));
        apply(employee, dto);
        return employee;
    }

    /** The user to link: enabled and no other employee's; none when not given. */
    private User user(Long userId, UUID employeeId) {
        if (userId == null) {
            return null;
        }
        User user = userRepo.findById(userId).orElseThrow(() -> BusinessException.onField("userId", "employee.user.invalid"));
        boolean taken = employeeId == null ? repo.existsByUser_Id(userId) : repo.existsByUser_IdAndIdNot(userId, employeeId);
        if (taken) {
            throw BusinessException.onField("userId", "employee.user.taken", user.getFullName());
        }
        if (!user.isEnabled()) {
            throw BusinessException.onField("userId", "employee.user.disabled", user.getUsername());
        }
        return user;
    }

    private static void apply(Employee e, EmployeeDto dto) {
        if (dto.getLeftOn() != null && dto.getLeftOn().isBefore(dto.getHiredOn())) {
            throw BusinessException.onField("leftOn", "employee.leftOn.beforeHired");
        }
        if (dto.getPayMethod().needsAccount() && !StringUtils.hasText(dto.getAccountNumber())) {
            throw BusinessException.onField("accountNumber", "employee.account.required");
        }
        e.setFullName(dto.getFullName().trim());
        e.setNationalId(dto.getNationalId());
        e.setRssbNumber(StringUtils.hasText(dto.getRssbNumber()) ? dto.getRssbNumber() : null);
        e.setJobTitle(StringUtils.hasText(dto.getJobTitle()) ? dto.getJobTitle().trim() : null);
        e.setHiredOn(dto.getHiredOn());
        e.setLeftOn(dto.getLeftOn());
        e.setEmployment(dto.getEmployment());
        e.setBaseSalary(amount(dto.getBaseSalary()));
        e.setTransportAllowance(amount(dto.getTransportAllowance()));
        e.setHousingAllowance(amount(dto.getHousingAllowance()));
        e.setOtherAllowance(amount(dto.getOtherAllowance()));
        e.setPayMethod(dto.getPayMethod());
        boolean bank = dto.getPayMethod() == SalaryPayMethod.BANK;
        e.setBankName(bank && StringUtils.hasText(dto.getBankName()) ? dto.getBankName().trim() : null);
        e.setAccountNumber(dto.getPayMethod().needsAccount() ? dto.getAccountNumber() : null);
    }

    private static BigDecimal amount(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2) : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal whole(BigDecimal value) {
        return value == null ? null : value.setScale(0, RoundingMode.HALF_UP);
    }
}
