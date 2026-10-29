package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.dto.EmployeeDto;
import com.ntaganira.heritier.iWarehouse.entity.CostCentre;
import com.ntaganira.heritier.iWarehouse.entity.Employee;
import com.ntaganira.heritier.iWarehouse.enums.EmploymentType;
import com.ntaganira.heritier.iWarehouse.enums.SalaryPayMethod;
import com.ntaganira.heritier.iWarehouse.repository.EmployeeRepository;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The Excel export and import of employees (payroll, NFR-12). */
class EmployeeExcelServiceTest {

    private static final List<String> HEADERS = List.of(
            "Full name", "National ID", "RSSB number", "Job title", "Cost centre code", "Hired on", "Left on",
            "Employment", "Base salary (RWF)", "Transport allowance (RWF)", "Housing allowance (RWF)",
            "Other allowance (RWF)", "Pay method", "Bank name", "Account or mobile-money number");

    private EmployeeService employeeService;
    private EmployeeRepository employeeRepo;
    private EmployeeExcelService service;
    private CostCentre finance;

    @BeforeEach
    void setUp() {
        employeeService = mock(EmployeeService.class);
        employeeRepo = mock(EmployeeRepository.class);
        Messages messages = mock(Messages.class);
        when(messages.get(anyString(), any(Object[].class))).thenAnswer(i -> i.getArgument(0));
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        finance = new CostCentre();
        finance.setId(UUID.randomUUID());
        finance.setCode("FIN");
        finance.setName("Finance");
        finance.setEnabled(true);
        when(employeeService.costCentres()).thenReturn(List.of(finance));

        service = new EmployeeExcelService(employeeService, employeeRepo, messages, validator, transactionManager);
    }

    @Test
    void aValidRowBecomesAnEmployee() {
        when(employeeRepo.existsByNationalId(anyString())).thenReturn(false);
        when(employeeService.create(any(EmployeeDto.class))).thenAnswer(i -> {
            Employee employee = new Employee();
            employee.setId(UUID.randomUUID());
            employee.setCode("EMP-WH-0001");
            return employee;
        });

        EmployeeExcelService.Result result = service.importFile(workbook(
                List.of("Aline Uwase", "1234567890123456", "RSSB-1", "Accountant", "FIN", "2025-01-15", "",
                        "", "300000", "50000", "", "", "BANK", "Bank of Kigali", "00012345")));

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.skipped()).isZero();
        assertThat(result.errors()).isEmpty();
        ArgumentCaptor<EmployeeDto> dto = ArgumentCaptor.forClass(EmployeeDto.class);
        verify(employeeService).create(dto.capture());
        assertThat(dto.getValue().getFullName()).isEqualTo("Aline Uwase");
        assertThat(dto.getValue().getNationalId()).isEqualTo("1234567890123456");
        assertThat(dto.getValue().getCostCentreId()).isEqualTo(finance.getId());
        assertThat(dto.getValue().getHiredOn()).isEqualTo(LocalDate.of(2025, 1, 15));
        assertThat(dto.getValue().getEmployment()).isEqualTo(EmploymentType.PRIMARY);
        assertThat(dto.getValue().getPayMethod()).isEqualTo(SalaryPayMethod.BANK);
        assertThat(dto.getValue().getBaseSalary()).isEqualByComparingTo("300000");
        assertThat(dto.getValue().getTransportAllowance()).isEqualByComparingTo("50000");
    }

    @Test
    void anEmployeeAlreadyThereIsSkipped() {
        when(employeeRepo.existsByNationalId("1234567890123456")).thenReturn(true);

        EmployeeExcelService.Result result = service.importFile(workbook(
                List.of("Aline Uwase", "1234567890123456", "", "", "", "2025-01-15", "", "", "300000",
                        "", "", "", "", "", "")));

        assertThat(result.created()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.errors()).isEmpty();
        verify(employeeService, never()).create(any(EmployeeDto.class));
    }

    @Test
    void aNationalIdRepeatedInTheWorkbookIsRefusedTheSecondTime() {
        when(employeeRepo.existsByNationalId(anyString())).thenReturn(false);
        when(employeeService.create(any(EmployeeDto.class))).thenAnswer(i -> {
            Employee employee = new Employee();
            employee.setId(UUID.randomUUID());
            return employee;
        });

        EmployeeExcelService.Result result = service.importFile(workbook(
                List.of("Aline Uwase", "1234567890123456", "", "", "", "2025-01-15", "", "", "300000",
                        "", "", "", "", "", ""),
                List.of("Aline Uwase", "1234567890123456", "", "", "", "2025-01-15", "", "", "300000",
                        "", "", "", "", "", "")));

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0).message()).isEqualTo("employee.import.duplicate");
    }

    @Test
    void aBadRowDoesNotStopTheGoodOnes() {
        when(employeeRepo.existsByNationalId(anyString())).thenReturn(false);
        when(employeeService.create(any(EmployeeDto.class))).thenAnswer(i -> {
            Employee employee = new Employee();
            employee.setId(UUID.randomUUID());
            return employee;
        });

        EmployeeExcelService.Result result = service.importFile(workbook(
                List.of("No Date", "1111111111111111", "", "", "", "", "", "", "100000", "", "", "", "", "", ""),
                List.of("Good Row", "2222222222222222", "", "", "", "2025-02-01", "", "", "200000", "", "", "", "", "", "")));

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0).message()).isEqualTo("employee.import.hiredOn");
    }

    @Test
    void theTemplateHasTheColumnsTheImporterReads() {
        byte[] bytes = service.template();

        assertThat(bytes).isNotEmpty();
        List<List<String>> rows = Excel.read(new ByteArrayInputStream(bytes));
        assertThat(rows).anySatisfy(row -> assertThat(row).contains("Full name", "National ID", "Hired on"));
    }

    @Test
    void anEmptyTemplateImportsNothingAndFailsNoRow() {
        MockMultipartFile template = new MockMultipartFile("file", "employees.xlsx", Excel.CONTENT_TYPE, service.template());

        EmployeeExcelService.Result result = service.importFile(template);

        assertThat(result.created()).isZero();
        assertThat(result.skipped()).isZero();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void exportMasksPersonalDataWithoutThePersonalDataRight() {
        Employee employee = employee();
        when(employeeService.findForExport(null, null)).thenReturn(List.of(employee));

        List<List<String>> rows = Excel.read(new ByteArrayInputStream(service.export(null, null, false)));

        List<String> row = rows.stream().filter(r -> r.contains("Aline Uwase")).findFirst().orElseThrow();
        assertThat(row).contains("\u2022\u2022\u2022\u2022 3456", "\u2022\u2022\u2022\u2022 2345");
    }

    @Test
    void exportShowsPersonalDataWithThePersonalDataRight() {
        Employee employee = employee();
        when(employeeService.findForExport(null, null)).thenReturn(List.of(employee));

        List<List<String>> rows = Excel.read(new ByteArrayInputStream(service.export(null, null, true)));

        List<String> row = rows.stream().filter(r -> r.contains("Aline Uwase")).findFirst().orElseThrow();
        assertThat(row).contains("1234567890123456", "00012345");
    }

    private static Employee employee() {
        Employee employee = new Employee();
        employee.setId(UUID.randomUUID());
        employee.setCode("EMP-WH-0001");
        employee.setFullName("Aline Uwase");
        employee.setNationalId("1234567890123456");
        employee.setAccountNumber("00012345");
        employee.setHiredOn(LocalDate.of(2025, 1, 15));
        employee.setBaseSalary(new BigDecimal("300000"));
        employee.setTransportAllowance(new BigDecimal("50000"));
        return employee;
    }

    private static MockMultipartFile workbook(List<String>... rows) {
        List<Excel.Row> data = Arrays.stream(rows).map(row -> Excel.Row.of(row.toArray())).toList();
        Excel.Sheet sheet = new Excel.Sheet("Employees", "Employees", "Import", HEADERS, data);
        byte[] bytes = Excel.write(sheet);
        return new MockMultipartFile("file", "employees.xlsx", Excel.CONTENT_TYPE, bytes);
    }
}
