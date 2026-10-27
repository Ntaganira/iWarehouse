package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.NumberFormats;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.dto.EmployeeDto;
import com.ntaganira.heritier.iWarehouse.entity.Driver;
import com.ntaganira.heritier.iWarehouse.entity.Employee;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.enums.EmploymentType;
import com.ntaganira.heritier.iWarehouse.enums.SalaryPayMethod;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.DataChangeService;
import com.ntaganira.heritier.iWarehouse.service.EmployeeService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : EmployeeController.java
 * - Date      : 2026. 10. 11.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Employees (/payroll/employees): the list, an employee's page (details, payslips, history) and the form.
 *               The national ID and account number show in full only with PERM_VIEW_EMPLOYEE_DATA (NFR-12), which the
 *               form needs too; others see the last four characters.
 * </pre>
 */
@Controller
@RequestMapping("/payroll/employees")
public class EmployeeController {

    private final EmployeeService employeeService;
    private final DataChangeService dataChangeService;
    private final ActivityLogService activityLogService;
    private final Messages messages;
    private final NumberFormats num;

    public EmployeeController(EmployeeService employeeService, DataChangeService dataChangeService, ActivityLogService activityLogService,
                              Messages messages, NumberFormats num) {
        this.employeeService = employeeService;
        this.dataChangeService = dataChangeService;
        this.activityLogService = activityLogService;
        this.messages = messages;
        this.num = num;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PAGE_EMPLOYEES') and hasAuthority('PERM_VIEW_EMPLOYEE')")
    public String list(@RequestParam(required = false) String search, @RequestParam(required = false) String status,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("employees", employeeService.findPage(search, status, Paging.page(page), Paging.SIZE));
        model.addAttribute("search", search);
        model.addAttribute("status", status);
        model.addAttribute("activeCount", employeeService.activeCount());
        model.addAttribute("paginationQuery", QueryString.of("search", search, "status", status));
        return "payroll/employees/list";
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PAGE_EMPLOYEES') and hasAuthority('PERM_VIEW_EMPLOYEE')")
    public String view(@PathVariable UUID id, @RequestParam(defaultValue = "details") String tab,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        boolean personal = AppUserPrincipal.currentHas("PERM_VIEW_EMPLOYEE_DATA");
        String open = List.of("details", "payslips", "history").contains(tab) ? tab : "details";
        Employee employee = employeeService.findDetailed(id);
        model.addAttribute("employee", employee);
        model.addAttribute("personal", personal);
        // Personal data (NFR-12): in full only for those who may see it, the last four characters otherwise
        model.addAttribute("nationalIdShown", personal ? employee.getNationalId() : Driver.masked(employee.getNationalId()));
        model.addAttribute("accountShown", personal ? employee.getAccountNumber() : Driver.masked(employee.getAccountNumber()));
        model.addAttribute("driver", employeeService.driverOf(employee).orElse(null));
        model.addAttribute("costCentre", employee.getCostCentreId() == null ? null : employeeService.costCentres().stream()
                .filter(c -> c.getId().equals(employee.getCostCentreId())).findFirst().orElse(null));
        model.addAttribute("payslips", employeeService.payslips(id, Paging.pageOf("payslips", open, page), Paging.SIZE));
        model.addAttribute("history", dataChangeService.history("Employee", id.toString(), Paging.pageOf("history", open, page), Paging.SIZE));
        model.addAttribute("tab", open);
        return "payroll/employees/view";
    }

    @GetMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_EMPLOYEES') and hasAuthority('PERM_MANAGE_EMPLOYEE') and hasAuthority('PERM_VIEW_EMPLOYEE_DATA')")
    public String createForm(Model model) {
        return form(model, new EmployeeDto());
    }

    @PostMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_EMPLOYEES') and hasAuthority('PERM_MANAGE_EMPLOYEE') and hasAuthority('PERM_VIEW_EMPLOYEE_DATA')")
    public String create(@Valid @ModelAttribute("employeeDto") EmployeeDto dto, BindingResult result, Model model, RedirectAttributes redirect) {
        if (result.hasErrors()) {
            return invalid(model, dto, result);
        }
        try {
            Employee e = employeeService.create(dto);
            activityLogService.record(PayrollRateController.MODULE, "CREATE_EMPLOYEE", "Added employee " + e.getCode() + " " + e.getFullName()
                    + " (" + num.money(e.getMonthlyPay()) + " RWF a month)", ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("employee.created", e.getFullName(), e.getCode()));
            return "redirect:/payroll/employees/" + e.getId();
        } catch (BusinessException e) {
            activityLogService.record(PayrollRateController.MODULE, "CREATE_EMPLOYEE", "Failed to add an employee: "
                    + messages.get(e.getMessageKey(), e.getArgs()), ActivityStatus.FAILED);
            return rejected(model, dto, result, e);
        }
    }

    @GetMapping("/{id}/edit")
    @PreAuthorize("hasAuthority('PAGE_EMPLOYEES') and hasAuthority('PERM_MANAGE_EMPLOYEE') and hasAuthority('PERM_VIEW_EMPLOYEE_DATA')")
    public String editForm(@PathVariable UUID id, Model model) {
        return form(model, employeeService.form(employeeService.findDetailed(id)));
    }

    @PostMapping("/{id}/edit")
    @PreAuthorize("hasAuthority('PAGE_EMPLOYEES') and hasAuthority('PERM_MANAGE_EMPLOYEE') and hasAuthority('PERM_VIEW_EMPLOYEE_DATA')")
    public String update(@PathVariable UUID id, @Valid @ModelAttribute("employeeDto") EmployeeDto dto, BindingResult result, Model model,
                         RedirectAttributes redirect) {
        dto.setId(id);
        if (result.hasErrors()) {
            return invalid(model, dto, result);
        }
        try {
            Employee e = employeeService.update(id, dto);
            activityLogService.record(PayrollRateController.MODULE, "UPDATE_EMPLOYEE", "Updated employee " + e.getCode() + " " + e.getFullName()
                    + (e.getLeftOn() != null ? " (left on " + e.getLeftOn() + ")" : ""), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("employee.updated", e.getFullName()));
            return "redirect:/payroll/employees/" + id;
        } catch (BusinessException e) {
            activityLogService.record(PayrollRateController.MODULE, "UPDATE_EMPLOYEE", "Failed to update employee " + codeOf(id) + ": "
                    + messages.get(e.getMessageKey(), e.getArgs()), ActivityStatus.FAILED);
            return rejected(model, dto, result, e);
        }
    }

    private String form(Model model, EmployeeDto dto) {
        Employee current = dto.getId() == null ? null : employeeService.findDetailed(dto.getId());
        model.addAttribute("employeeDto", dto);
        model.addAttribute("employee", current);
        model.addAttribute("users", employeeService.linkableUsers(current));
        model.addAttribute("employments", EmploymentType.values());
        model.addAttribute("payMethods", SalaryPayMethod.values());
        model.addAttribute("costCentres", employeeService.costCentres().stream().filter(c -> c.isEnabled() || c.getId().equals(dto.getCostCentreId())).toList());
        return "payroll/employees/form";
    }

    private String invalid(Model model, EmployeeDto dto, BindingResult result) {
        model.addAttribute("formErrors", result.getFieldErrors());
        return form(model, dto);
    }

    private String rejected(Model model, EmployeeDto dto, BindingResult result, BusinessException e) {
        String error = messages.get(e.getMessageKey(), e.getArgs());
        if (e.getField() != null) {
            result.rejectValue(e.getField(), e.getMessageKey(), e.getArgs(), error);
        } else {
            model.addAttribute("flashError", error);
        }
        return invalid(model, dto, result);
    }

    private String codeOf(UUID id) {
        try {
            return employeeService.findDetailed(id).getCode();
        } catch (NotFoundException e) {
            return id.toString();
        }
    }
}
