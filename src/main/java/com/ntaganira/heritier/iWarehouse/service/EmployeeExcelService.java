package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.dto.EmployeeDto;
import com.ntaganira.heritier.iWarehouse.entity.CostCentre;
import com.ntaganira.heritier.iWarehouse.entity.Driver;
import com.ntaganira.heritier.iWarehouse.entity.Employee;
import com.ntaganira.heritier.iWarehouse.enums.EmploymentType;
import com.ntaganira.heritier.iWarehouse.enums.SalaryPayMethod;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.repository.EmployeeRepository;
import jakarta.validation.Validator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.ObjectError;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : EmployeeExcelService.java
 * - Date      : 2026. 10. 29.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Excel export and import of employees (payroll): the list with its filters, the clean import template,
 *               and rows added through EmployeeService so their code, audit and business rules always apply. An
 *               employee already there (same national ID) is skipped, never changed. National ID and account number
 *               are masked unless the user may see employee personal data (NFR-12).
 * </pre>
 */
@Service
public class EmployeeExcelService {

    private static final List<String> HEADERS = List.of(
            "Full name", "National ID", "RSSB number", "Job title", "Cost centre code", "Hired on", "Left on",
            "Employment", "Base salary (RWF)", "Transport allowance (RWF)", "Housing allowance (RWF)",
            "Other allowance (RWF)", "Pay method", "Bank name", "Account or mobile-money number");

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            dateFormat("d/M/uuuu"),
            dateFormat("M/d/uuuu"),
            dateFormat("d-MMM-uuuu"));

    private final EmployeeService employeeService;
    private final EmployeeRepository employeeRepo;
    private final Messages messages;
    private final SpringValidatorAdapter validator;
    private final TransactionTemplate transaction;

    public EmployeeExcelService(EmployeeService employeeService, EmployeeRepository employeeRepo, Messages messages,
                                Validator validator, PlatformTransactionManager transactionManager) {
        this.employeeService = employeeService;
        this.employeeRepo = employeeRepo;
        this.messages = messages;
        this.validator = new SpringValidatorAdapter(validator);
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** A line the importer refused, by its Excel row number (the header is row 1 when it is the first row). */
    public record RowError(int row, String message) {
    }

    /** What the import did; an existing employee or a national ID repeated in the workbook is skipped. */
    public record Result(int created, int skipped, List<RowError> errors) {

        public boolean hasErrors() {
            return !errors.isEmpty();
        }
    }

    /** The employees matching the list's filters, as an Excel sheet. */
    public byte[] export(String search, String status, boolean personal) {
        List<Employee> employees = employeeService.findForExport(search, status);
        Map<UUID, String> costCentres = new HashMap<>();
        for (CostCentre centre : employeeService.costCentres()) {
            costCentres.put(centre.getId(), centre.getLabel());
        }

        Excel.Builder sheet = Excel.sheet(messages.get("employee.title"), messages.get("employee.title"), subtitle(employees.size(), search, status, personal),
                messages.get("employee.code"), messages.get("employee.fullName"), messages.get("employee.nationalId"),
                messages.get("employee.rssbNumber"), messages.get("employee.jobTitle"), messages.get("employee.costCentre"),
                messages.get("employee.hiredOn"), messages.get("employee.leftOn"), messages.get("employee.employment"),
                messages.get("employee.baseSalary"), messages.get("employee.transport"), messages.get("employee.housing"),
                messages.get("employee.otherAllowance"), messages.get("employee.monthlyPay"), messages.get("employee.payMethod"),
                messages.get("employee.bankName"), messages.get("employee.accountNumber"))
                .text(2, 3, 16);
        for (Employee e : employees) {
            sheet.row(e.getCode(), e.getFullName(), shown(e.getNationalId(), personal), e.getRssbNumber(), e.getJobTitle(),
                    e.getCostCentreId() == null ? null : costCentres.get(e.getCostCentreId()),
                    e.getHiredOn(), e.getLeftOn(), messages.get("employee.employment." + e.getEmployment()),
                    rwf(e.getBaseSalary()), rwf(e.getTransportAllowance()), rwf(e.getHousingAllowance()), rwf(e.getOtherAllowance()),
                    rwf(e.getMonthlyPay()), messages.get("employee.payMethod." + e.getPayMethod()), e.getBankName(),
                    shown(e.getAccountNumber(), personal));
        }
        return Excel.write(sheet.build());
    }

    /** The clean workbook: the columns, then instructions in the user's language. */
    public byte[] template() {
        List<Excel.Row> instructions = List.of(
                Excel.Row.of("1", messages.get("employee.import.template.step1")),
                Excel.Row.of("2", messages.get("employee.import.template.step2")),
                Excel.Row.of("3", messages.get("employee.import.template.step3")),
                Excel.Row.of("4", messages.get("employee.import.template.step4")),
                Excel.Row.of("5", messages.get("employee.import.template.step5")),
                Excel.Row.of("6", messages.get("employee.import.template.step6")),
                Excel.Row.of("7", messages.get("employee.import.template.step7")));
        Excel.Sheet employees = new Excel.Sheet(messages.get("employee.title"), messages.get("employee.import.template.title"),
                messages.get("employee.import.template.subtitle"), HEADERS, List.of(), Set.of(1, 2, 14));
        Excel.Sheet help = new Excel.Sheet(messages.get("employee.import.template.sheetInstructions"),
                messages.get("employee.import.template.helpTitle"), messages.get("employee.import.template.helpSubtitle"),
                List.of(messages.get("employee.import.template.stepColumn"), messages.get("employee.import.template.actionColumn")),
                instructions);
        return Excel.write(employees, help);
    }

    /** Reads the first sheet; valid rows import even when another row is refused. */
    public Result importFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw BusinessException.of("employee.import.file.required");
        }
        List<List<String>> rows;
        try (InputStream in = file.getInputStream()) {
            rows = Excel.read(in);
        } catch (IOException | RuntimeException e) {
            throw BusinessException.of("employee.import.read");
        }

        int headerAt = -1;
        Map<String, Integer> columns = Map.of();
        for (int r = 0; r < Math.min(rows.size(), 20); r++) {
            Map<String, Integer> found = columns(rows.get(r));
            if (found.containsKey("fullName")) {
                headerAt = r;
                columns = found;
                break;
            }
        }
        if (headerAt < 0) {
            throw BusinessException.of("employee.import.header");
        }

        Map<String, CostCentre> costCentres = new HashMap<>();
        for (CostCentre centre : employeeService.costCentres()) {
            if (centre.isEnabled()) {
                costCentres.put(centre.getCode().toUpperCase(Locale.ROOT), centre);
            }
        }

        List<RowError> errors = new ArrayList<>();
        Set<String> nationalIds = new LinkedHashSet<>();
        int created = 0;
        int skipped = 0;

        for (int r = headerAt + 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            if (row.stream().allMatch(String::isBlank)) {
                continue;
            }
            int excelRow = r + 1;
            try {
                EmployeeDto dto = row(row, columns, costCentres);
                validate(dto);
                if (!nationalIds.add(dto.getNationalId())) {
                    errors.add(new RowError(excelRow, messages.get("employee.import.duplicate", dto.getNationalId())));
                    skipped++;
                    continue;
                }
                if (employeeRepo.existsByNationalId(dto.getNationalId())) {
                    skipped++;
                    continue;
                }
                transaction.executeWithoutResult(status -> employeeService.create(dto));
                created++;
            } catch (RowProblem e) {
                errors.add(new RowError(excelRow, message(e)));
            } catch (BusinessException e) {
                errors.add(new RowError(excelRow, messages.get(e.getMessageKey(), e.getArgs())));
            } catch (RuntimeException e) {
                errors.add(new RowError(excelRow, messages.get("employee.import.rowFailed")));
            }
        }

        return new Result(created, skipped, List.copyOf(errors));
    }

    private String subtitle(int employees, String search, String status, boolean personal) {
        StringBuilder text = new StringBuilder(messages.get("employee.export.subtitle", employees));
        if (StringUtils.hasText(search)) {
            text.append(" \u2014 ").append(messages.get("employee.export.search", search.trim()));
        }
        if ("active".equals(status)) {
            text.append(" \u2014 ").append(messages.get("employee.status.active"));
        } else if ("left".equals(status)) {
            text.append(" \u2014 ").append(messages.get("employee.status.left"));
        }
        if (!personal) {
            text.append(" \u2014 ").append(messages.get("employee.export.masked"));
        }
        return text.toString();
    }

    private static EmployeeDto row(List<String> row, Map<String, Integer> columns, Map<String, CostCentre> costCentres) {
        EmployeeDto dto = new EmployeeDto();
        dto.setFullName(required(row, columns, "fullName", "employee.import.name").trim());
        dto.setNationalId(required(row, columns, "nationalId", "employee.import.nationalId"));
        dto.setRssbNumber(text(row, columns, "rssb"));
        dto.setJobTitle(text(row, columns, "jobTitle"));

        String centreCode = text(row, columns, "costCentre");
        if (!centreCode.isBlank()) {
            CostCentre centre = costCentres.get(centreCode.toUpperCase(Locale.ROOT));
            if (centre == null) {
                throw new RowProblem("employee.import.costCentre", centreCode);
            }
            dto.setCostCentreId(centre.getId());
        }

        dto.setHiredOn(date(row, columns, "hiredOn", true));
        dto.setLeftOn(date(row, columns, "leftOn", false));
        dto.setEmployment(employment(text(row, columns, "employment")));
        dto.setBaseSalary(amount(row, columns, "baseSalary", true, "employee.import.salary"));
        dto.setTransportAllowance(amount(row, columns, "transport", false, "employee.import.amount"));
        dto.setHousingAllowance(amount(row, columns, "housing", false, "employee.import.amount"));
        dto.setOtherAllowance(amount(row, columns, "other", false, "employee.import.amount"));
        dto.setPayMethod(payMethod(text(row, columns, "payMethod")));
        dto.setBankName(text(row, columns, "bankName"));
        dto.setAccountNumber(text(row, columns, "account"));
        return dto;
    }

    private void validate(EmployeeDto dto) {
        BeanPropertyBindingResult result = new BeanPropertyBindingResult(dto, "employeeDto");
        validator.validate(dto, result);
        if (result.hasErrors()) {
            throw validationProblem(result.getAllErrors().get(0));
        }
    }

    private RowProblem validationProblem(ObjectError error) {
        String text = error.getDefaultMessage();
        if (text != null && text.startsWith("{") && text.endsWith("}")) {
            return new RowProblem(text.substring(1, text.length() - 1));
        }
        return RowProblem.text(text == null ? messages.get("employee.import.rowFailed") : text);
    }

    private String message(RowProblem e) {
        return e.keyed ? messages.get(e.key, e.args) : e.text;
    }

    private static Map<String, Integer> columns(List<String> row) {
        Map<String, Integer> found = new LinkedHashMap<>();
        for (int c = 0; c < row.size(); c++) {
            String key = switch (normalize(row.get(c))) {
                case "FULLNAME", "NAME", "EMPLOYEENAME" -> "fullName";
                case "NATIONALID", "NATIONALIDENTITY", "ID" -> "nationalId";
                case "RSSBNUMBER", "RSSB" -> "rssb";
                case "JOBTITLE", "POSITION", "TITLE" -> "jobTitle";
                case "COSTCENTRECODE", "COSTCENTRE", "COSTCENTER", "DEPARTMENT" -> "costCentre";
                case "HIREDON", "HIREDATE", "DATEHIRED", "STARTDATE" -> "hiredOn";
                case "LEFTON", "LEFTDATE", "ENDDATE", "LASTDAY" -> "leftOn";
                case "EMPLOYMENT", "EMPLOYMENTTYPE", "PAYE" -> "employment";
                case "BASESALARYRWF", "BASESALARY", "SALARY" -> "baseSalary";
                case "TRANSPORTALLOWANCERWF", "TRANSPORTALLOWANCE", "TRANSPORT" -> "transport";
                case "HOUSINGALLOWANCERWF", "HOUSINGALLOWANCE", "HOUSING" -> "housing";
                case "OTHERALLOWANCERWF", "OTHERALLOWANCE", "OTHER" -> "other";
                case "PAYMETHOD", "PAIDBY" -> "payMethod";
                case "BANKNAME", "BANK" -> "bankName";
                case "ACCOUNTORMOBILEMONEYNUMBER", "ACCOUNTNUMBER", "ACCOUNT", "MOBILEMONEYNUMBER" -> "account";
                default -> null;
            };
            if (key != null) {
                found.putIfAbsent(key, c);
            }
        }
        return found;
    }

    private static String text(List<String> row, Map<String, Integer> columns, String key) {
        Integer c = columns.get(key);
        return c == null || c >= row.size() ? "" : row.get(c).trim();
    }

    private static String required(List<String> row, Map<String, Integer> columns, String key, String error) {
        String value = text(row, columns, key);
        if (value.isBlank()) {
            throw new RowProblem(error);
        }
        return value;
    }

    private static LocalDate date(List<String> row, Map<String, Integer> columns, String key, boolean required) {
        String value = text(row, columns, key);
        if (value.isBlank()) {
            if (required) {
                throw new RowProblem(key.equals("hiredOn") ? "employee.import.hiredOn" : "employee.import.date");
            }
            return null;
        }
        String clean = value.replace('\u00A0', ' ').trim();
        clean = clean.replaceAll("\\s+\\d{1,2}:\\d{2}(:\\d{2})?$", "").trim();
        if (clean.length() > 10 && clean.charAt(10) == 'T') {
            clean = clean.substring(0, 10);
        }
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(clean, format);
            } catch (DateTimeParseException ignored) {
                // try the next accepted shape
            }
        }
        throw new RowProblem("employee.import.date", value);
    }

    private static BigDecimal amount(List<String> row, Map<String, Integer> columns, String key, boolean required, String error) {
        String value = text(row, columns, key);
        if (value.isBlank()) {
            if (required) {
                throw new RowProblem(error);
            }
            return null;
        }
        String clean = value.replace('\u00A0', ' ').replace(" ", "")
                .replaceAll("(?i)RWF", "").replaceAll("(?i)FRW", "");
        int comma = clean.indexOf(',');
        if (comma >= 0 && clean.indexOf('.') < 0 && clean.indexOf(',', comma + 1) < 0
                && clean.length() - comma - 1 <= 2) {
            clean = clean.substring(0, comma) + "." + clean.substring(comma + 1);
        } else {
            clean = clean.replace(",", "");
        }
        try {
            return new BigDecimal(clean);
        } catch (NumberFormatException e) {
            throw new RowProblem("employee.import.amount", value);
        }
    }

    private static EmploymentType employment(String value) {
        if (value.isBlank()) {
            return EmploymentType.PRIMARY;
        }
        return switch (normalize(value)) {
            case "PRIMARY", "FIRST", "FIRSTEMPLOYER" -> EmploymentType.PRIMARY;
            case "SECONDARY", "SECOND", "SECONDEMPLOYER" -> EmploymentType.SECONDARY;
            default -> throw new RowProblem("employee.import.employment", value);
        };
    }

    private static SalaryPayMethod payMethod(String value) {
        if (value.isBlank()) {
            return SalaryPayMethod.BANK;
        }
        return switch (normalize(value)) {
            case "BANK", "BANKTRANSFER", "TRANSFER" -> SalaryPayMethod.BANK;
            case "MOBILEMONEY", "MOBILE", "MOMO" -> SalaryPayMethod.MOBILE_MONEY;
            case "CASH", "VAULT" -> SalaryPayMethod.CASH;
            default -> throw new RowProblem("employee.import.payMethod", value);
        };
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    private static DateTimeFormatter dateFormat(String pattern) {
        return DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT);
    }

    private static String shown(String value, boolean personal) {
        return personal ? value : Driver.masked(value);
    }

    /** Payroll amounts are whole RWF: shown without decimals in the export. */
    private static BigDecimal rwf(BigDecimal amount) {
        return amount == null ? null : amount.setScale(0, RoundingMode.HALF_UP);
    }

    private static final class RowProblem extends RuntimeException {

        final String key;
        final Object[] args;
        final String text;
        final boolean keyed;

        RowProblem(String key, Object... args) {
            this.key = key;
            this.args = args;
            this.text = null;
            this.keyed = true;
        }

        private RowProblem(String text, boolean raw) {
            this.key = null;
            this.args = null;
            this.text = text;
            this.keyed = false;
        }

        static RowProblem text(String text) {
            return new RowProblem(text, false);
        }
    }
}
