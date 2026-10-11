package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.audit.AuditContext;
import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.NumberFormats;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.dto.PayRunLinesDto;
import com.ntaganira.heritier.iWarehouse.entity.PayRun;
import com.ntaganira.heritier.iWarehouse.entity.PayRunLine;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.enums.PayRunStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import com.ntaganira.heritier.iWarehouse.service.*;
import jakarta.validation.Validator;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindingResult;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : PayRunController.java
 * - Date      : 2026. 10. 11.
 * - User      : Hntaganira
 * - Desc      : Pay runs (/payroll/runs): a month's run made, its lines changed while a draft, submitted, approved (posted)
 *               or sent back by another person, cancelled, its salaries paid; the payslips (A4 PDF, one employee a page)
 *               and the exports: the run's summary, the PAYE declaration (RRA) and the RSSB declaration, Excel or PDF.
 *               National IDs print in full only for those with PERM_VIEW_EMPLOYEE_DATA.
 * </pre>
 */
@Controller
@RequestMapping("/payroll/runs")
public class PayRunController {

    private static final int REASON_MAX = 255;
    private static final String BASE = "/payroll/runs";
    private static final String MODULE = PayrollRateController.MODULE;
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MM/yyyy");
    private static final DateTimeFormatter FILE_MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    /** What the run's export holds. */
    public enum Report { SUMMARY, PAYE, RSSB }

    private final PayRunService runService;
    private final DataChangeService dataChangeService;
    private final ActivityLogService activityLogService;
    private final PdfService pdfService;
    private final ReportFiles reportFiles;
    private final SpringValidatorAdapter validator;
    private final Messages messages;
    private final NumberFormats num;

    public PayRunController(PayRunService runService, DataChangeService dataChangeService, ActivityLogService activityLogService,
                            PdfService pdfService, ReportFiles reportFiles, Validator validator, Messages messages, NumberFormats num) {
        this.runService = runService;
        this.dataChangeService = dataChangeService;
        this.activityLogService = activityLogService;
        this.pdfService = pdfService;
        this.reportFiles = reportFiles;
        this.validator = new SpringValidatorAdapter(validator);
        this.messages = messages;
        this.num = num;
    }

    // ---------------------------------------------------------------- reading

    @GetMapping
    @PreAuthorize("hasAuthority('PAGE_PAY_RUNS') and hasAuthority('PERM_VIEW_PAY_RUN')")
    public String list(@RequestParam(required = false) String search, @RequestParam(required = false) String status,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("runs", runService.findPage(search, status, Paging.page(page), Paging.SIZE));
        model.addAttribute("statuses", PayRunStatus.values());
        model.addAttribute("search", search);
        model.addAttribute("status", status);
        model.addAttribute("pending", runService.pendingCount());
        model.addAttribute("months", runService.openMonths());
        model.addAttribute("suggested", runService.suggestedMonth());
        model.addAttribute("paginationQuery", QueryString.of("search", search, "status", status));
        return "payroll/runs/list";
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PAGE_PAY_RUNS') and hasAuthority('PERM_VIEW_PAY_RUN')")
    public String view(@PathVariable UUID id, @RequestParam(defaultValue = "lines") String tab, @RequestParam(defaultValue = "0") int page,
                       Model model) {
        String open = List.of("lines", "history").contains(tab) ? tab : "lines";
        PayRun run = runService.findDetailed(id);
        List<PayRunLine> lines = runService.lines(id);
        model.addAttribute("run", run);
        model.addAttribute("lines", Paging.of(lines, Paging.pageOf("lines", open, page)));
        model.addAttribute("journals", runService.journals(run));
        model.addAttribute("own", runService.isOwn(run));
        model.addAttribute("today", runService.today());
        model.addAttribute("reports", Report.values());
        model.addAttribute("history", dataChangeService.historyWithChildren("PayRun", id.toString(), "PayRunLine", "payRunId",
                Paging.pageOf("history", open, page), Paging.SIZE));
        model.addAttribute("tab", open);
        return "payroll/runs/view";
    }

    // ---------------------------------------------------------------- preparing

    @PostMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_PAY_RUNS') and hasAuthority('PERM_PREPARE_PAY_RUN')")
    public String create(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate period,
                         RedirectAttributes redirect) {
        if (period == null) {
            redirect.addFlashAttribute("flashError", messages.get("payRun.period.required"));
            return "redirect:" + BASE;
        }
        try {
            PayRun run = runService.create(period);
            activityLogService.record(MODULE, "CREATE_PAY_RUN", "Prepared pay run " + run.getNumber() + " for " + run.getPeriod().format(MONTH)
                    + ": " + run.getEmployees() + " employee(s), net pay " + num.money(run.getTotalNet()) + " RWF", ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("payRun.created", run.getNumber(), run.getEmployees()));
            return "redirect:" + BASE + "/" + run.getId();
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "CREATE_PAY_RUN", "Failed to prepare the pay run of " + period.format(MONTH) + ": " + error,
                    ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
            return "redirect:" + BASE;
        }
    }

    @GetMapping("/{id}/edit")
    @PreAuthorize("hasAuthority('PAGE_PAY_RUNS') and hasAuthority('PERM_PREPARE_PAY_RUN')")
    public String editForm(@PathVariable UUID id, Model model, RedirectAttributes redirect) {
        PayRun run = runService.findDetailed(id);
        if (!run.isDraft()) {
            redirect.addFlashAttribute("flashError", messages.get("payRun.notDraft", run.getNumber()));
            return "redirect:" + BASE + "/" + id;
        }
        return linesForm(model, run, PayRunLinesDto.of(runService.lines(id)));
    }

    @PostMapping("/{id}/edit")
    @PreAuthorize("hasAuthority('PAGE_PAY_RUNS') and hasAuthority('PERM_PREPARE_PAY_RUN')")
    public String saveLines(@PathVariable UUID id, @ModelAttribute("linesDto") PayRunLinesDto dto, BindingResult result, Model model,
                            RedirectAttributes redirect) {
        validator.validate(dto, result);
        PayRun current = runService.findDetailed(id);
        if (result.hasErrors()) {
            model.addAttribute("formErrors", result.getFieldErrors());
            return linesForm(model, current, dto);
        }
        try {
            PayRun run = runService.saveLines(id, dto);
            activityLogService.record(MODULE, "UPDATE_PAY_RUN", "Changed the lines of pay run " + run.getNumber() + ": net pay "
                    + num.money(run.getTotalNet()) + " RWF", ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("payRun.saved", run.getNumber()));
            return "redirect:" + BASE + "/" + id;
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "UPDATE_PAY_RUN", "Failed to change the lines of pay run " + current.getNumber() + ": " + error,
                    ActivityStatus.FAILED);
            if (e.getField() != null) {
                result.rejectValue(e.getField(), e.getMessageKey(), e.getArgs(), error);
            } else {
                model.addAttribute("flashError", error);
            }
            model.addAttribute("formErrors", result.getFieldErrors());
            return linesForm(model, current, dto);
        }
    }

    @PostMapping("/{id}/add-missing")
    @PreAuthorize("hasAuthority('PAGE_PAY_RUNS') and hasAuthority('PERM_PREPARE_PAY_RUN')")
    public String addMissing(@PathVariable UUID id, RedirectAttributes redirect) {
        try {
            int added = runService.addMissing(id);
            activityLogService.record(MODULE, "UPDATE_PAY_RUN", "Added " + added + " employee(s) to pay run " + numberOf(id), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute(added > 0 ? "flashSuccess" : "flashWarning", messages.get(added > 0 ? "payRun.added" : "payRun.addedNone", added));
        } catch (BusinessException e) {
            fail(redirect, "UPDATE_PAY_RUN", "Failed to add employees to pay run " + numberOf(id), e);
        }
        return "redirect:" + BASE + "/" + id;
    }

    @PostMapping("/{id}/lines/{lineId}/remove")
    @PreAuthorize("hasAuthority('PAGE_PAY_RUNS') and hasAuthority('PERM_PREPARE_PAY_RUN')")
    public String removeLine(@PathVariable UUID id, @PathVariable UUID lineId, RedirectAttributes redirect) {
        try {
            String name = runService.removeLine(id, lineId);
            activityLogService.record(MODULE, "UPDATE_PAY_RUN", "Took " + name + " off pay run " + numberOf(id), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("payRun.removed", name));
        } catch (BusinessException e) {
            fail(redirect, "UPDATE_PAY_RUN", "Failed to take an employee off pay run " + numberOf(id), e);
        }
        return "redirect:" + BASE + "/" + id;
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAuthority('PAGE_PAY_RUNS') and hasAuthority('PERM_PREPARE_PAY_RUN')")
    public String submit(@PathVariable UUID id, RedirectAttributes redirect) {
        try {
            PayRun run = runService.submit(id);
            activityLogService.record(MODULE, "SUBMIT_PAY_RUN", "Submitted pay run " + run.getNumber() + " for approval: " + run.getEmployees()
                    + " employee(s), net pay " + num.money(run.getTotalNet()) + " RWF", ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("payRun.submitted", run.getNumber()));
        } catch (BusinessException e) {
            fail(redirect, "SUBMIT_PAY_RUN", "Failed to submit pay run " + numberOf(id), e);
        }
        return "redirect:" + BASE + "/" + id;
    }

    // ---------------------------------------------------------------- deciding

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('PAGE_PAY_RUNS') and hasAuthority('PERM_APPROVE_PAY_RUN')")
    public String approve(@PathVariable UUID id, RedirectAttributes redirect) {
        try {
            PayRun run = runService.approve(id);
            activityLogService.record(MODULE, "APPROVE_PAY_RUN", "Approved pay run " + run.getNumber() + " of " + run.getSubmittedBy()
                    + ": gross " + num.money(run.getTotalGross()) + " RWF, net pay " + num.money(run.getTotalNet()) + " RWF", ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("payRun.approved", run.getNumber()));
        } catch (BusinessException e) {
            fail(redirect, "APPROVE_PAY_RUN", "Failed to approve pay run " + numberOf(id), e);
        }
        return "redirect:" + BASE + "/" + id;
    }

    @PostMapping("/{id}/send-back")
    @PreAuthorize("hasAuthority('PAGE_PAY_RUNS') and hasAuthority('PERM_APPROVE_PAY_RUN')")
    public String sendBack(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        if (!StringUtils.hasText(reason) || reason.trim().length() > REASON_MAX) {
            redirect.addFlashAttribute("flashError", messages.get("po.reason.required"));
            return "redirect:" + BASE + "/" + id;
        }
        try {
            PayRun run = AuditContext.withReason(reason.trim(), () -> runService.sendBack(id, reason));
            activityLogService.record(MODULE, "REJECT_PAY_RUN", "Sent pay run " + run.getNumber() + " back: " + reason.trim(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("payRun.returnedMsg", run.getNumber()));
        } catch (BusinessException e) {
            fail(redirect, "REJECT_PAY_RUN", "Failed to send pay run " + numberOf(id) + " back", e);
        }
        return "redirect:" + BASE + "/" + id;
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('PAGE_PAY_RUNS') and hasAuthority('PERM_PREPARE_PAY_RUN')")
    public String cancel(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        if (!StringUtils.hasText(reason) || reason.trim().length() > REASON_MAX) {
            redirect.addFlashAttribute("flashError", messages.get("po.reason.required"));
            return "redirect:" + BASE + "/" + id;
        }
        try {
            PayRun run = AuditContext.withReason(reason.trim(), () -> runService.cancel(id, reason));
            activityLogService.record(MODULE, "CANCEL_PAY_RUN", "Cancelled pay run " + run.getNumber() + ": " + reason.trim(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("payRun.cancelledMsg", run.getNumber()));
        } catch (BusinessException e) {
            fail(redirect, "CANCEL_PAY_RUN", "Failed to cancel pay run " + numberOf(id), e);
        }
        return "redirect:" + BASE + "/" + id;
    }

    // ---------------------------------------------------------------- paying

    @PostMapping("/{id}/pay")
    @PreAuthorize("hasAuthority('PAGE_PAY_RUNS') and hasAuthority('PERM_PREPARE_PAY_RUN')")
    public String pay(@PathVariable UUID id, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate paidOn,
                      @RequestParam(required = false) String reference, RedirectAttributes redirect) {
        if (reference != null && reference.trim().length() > 60) {
            redirect.addFlashAttribute("flashError", messages.get("payRun.reference.size"));
            return "redirect:" + BASE + "/" + id;
        }
        try {
            PayRun run = runService.pay(id, paidOn, reference);
            activityLogService.record(MODULE, "PAY_SALARIES", "Paid the salaries of pay run " + run.getNumber() + " on " + run.getPaidOn() + ": "
                    + num.money(run.getTotalNet()) + " RWF" + (run.getPaymentReference() != null ? " (" + run.getPaymentReference() + ")" : ""),
                    ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("payRun.paidMsg", run.getNumber(), num.money(run.getTotalNet())));
        } catch (BusinessException e) {
            fail(redirect, "PAY_SALARIES", "Failed to pay the salaries of pay run " + numberOf(id), e);
        }
        return "redirect:" + BASE + "/" + id;
    }

    // ---------------------------------------------------------------- payslips and exports

    /** Every payslip of the run, one employee a page. */
    @GetMapping("/{id}/payslips")
    @PreAuthorize("hasAuthority('PAGE_PAY_RUNS') and hasAuthority('PERM_VIEW_PAY_RUN')")
    public ResponseEntity<byte[]> payslips(@PathVariable UUID id) {
        PayRun run = runService.findDetailed(id);
        byte[] body = payslipPdf(run, runService.lines(id), pdfService, reportFiles);
        activityLogService.record(MODULE, "EXPORT_PAYSLIPS", "Downloaded the payslips of pay run " + run.getNumber() + " as PDF", ActivityStatus.SUCCESS);
        return ReportFiles.file("payslips-" + run.getPeriod().format(FILE_MONTH) + ".pdf", MediaType.APPLICATION_PDF, body);
    }

    /** One employee's payslip. */
    @GetMapping("/{id}/payslips/{lineId}")
    @PreAuthorize("hasAuthority('PAGE_PAY_RUNS') and hasAuthority('PERM_VIEW_PAY_RUN')")
    public ResponseEntity<byte[]> payslip(@PathVariable UUID id, @PathVariable UUID lineId) {
        PayRun run = runService.findDetailed(id);
        PayRunLine line = runService.line(run, lineId);
        byte[] body = payslipPdf(run, List.of(line), pdfService, reportFiles);
        activityLogService.record(MODULE, "EXPORT_PAYSLIPS", "Downloaded the payslip of " + line.getEmployeeCode() + " in pay run " + run.getNumber()
                + " as PDF", ActivityStatus.SUCCESS);
        return ReportFiles.file("payslip-" + line.getEmployeeCode() + "-" + run.getPeriod().format(FILE_MONTH) + ".pdf", MediaType.APPLICATION_PDF, body);
    }

    /** The run's summary, the PAYE declaration or the RSSB declaration, as Excel or PDF. */
    @GetMapping("/{id}/export")
    @PreAuthorize("hasAuthority('PAGE_PAY_RUNS') and hasAuthority('PERM_VIEW_PAY_RUN')")
    public ResponseEntity<byte[]> export(@PathVariable UUID id, @RequestParam(defaultValue = "SUMMARY") Report report,
                                         @RequestParam(defaultValue = "XLSX") ReportFiles.Format format) {
        PayRun run = runService.findDetailed(id);
        List<PayRunLine> lines = runService.lines(id);
        boolean personal = AppUserPrincipal.currentHas("PERM_VIEW_EMPLOYEE_DATA");
        String month = run.getPeriod().format(MONTH);
        String subtitle = m("payRun.exportSubtitle", run.getNumber(), month, m("payRun.status." + run.getStatus())) + " · " + m("payRun.x.note");
        Excel.Sheet sheet = switch (report) {
            case SUMMARY -> {
                Excel.Builder s = Excel.sheet(m("payRun.report.SUMMARY"), m("payRun.report.SUMMARY"), subtitle, m("employee.code"), m("employee.fullName"),
                        m("payRun.col.gross"), m("payRun.col.paye"), m("payRun.x.pensionEe"), m("payRun.x.maternityEe"), m("payRun.col.cbhi"),
                        m("payRun.col.other"), m("payRun.col.net"), m("payRun.x.employer"), m("payRun.x.cost"));
                for (PayRunLine l : lines) {
                    s.row(l.getEmployeeCode(), l.getEmployeeName(), rwf(l.getGross()), rwf(l.getPaye()), rwf(l.getPensionEmployee()),
                            rwf(l.getMaternityEmployee()), rwf(l.getCbhi()), rwf(l.getOtherDeductions()), rwf(l.getNetPay()),
                            rwf(l.getEmployerContributions()), rwf(l.getEmployerCost()));
                }
                s.bold(m("payRun.total"), null, rwf(run.getTotalGross()), rwf(run.getTotalPaye()), rwf(sum(lines, PayRunLine::getPensionEmployee)),
                        rwf(sum(lines, PayRunLine::getMaternityEmployee)), rwf(run.getTotalCbhi()), rwf(run.getTotalOther()), rwf(run.getTotalNet()),
                        rwf(run.getTotalEmployer()), rwf(run.getTotalCost()));
                yield s.build();
            }
            case PAYE -> {
                Excel.Builder s = Excel.sheet(m("payRun.report.PAYE"), m("payRun.report.PAYE"), subtitle, m("employee.code"), m("employee.fullName"),
                        m("employee.nationalId"), m("employee.employment"), m("payRun.x.taxable"), m("payRun.col.paye"));
                for (PayRunLine l : lines) {
                    s.row(l.getEmployeeCode(), l.getEmployeeName(), shown(l.getNationalId(), personal), m("employee.employment." + l.getEmployment()),
                            rwf(l.getGross()), rwf(l.getPaye()));
                }
                s.bold(m("payRun.total"), null, null, null, rwf(run.getTotalGross()), rwf(run.getTotalPaye()));
                yield s.build();
            }
            case RSSB -> {
                Excel.Builder s = Excel.sheet(m("payRun.report.RSSB"), m("payRun.report.RSSB"), subtitle, m("employee.code"), m("employee.fullName"),
                        m("payRun.x.rssbNumber"), m("employee.nationalId"), m("payRun.x.base"), m("payRun.x.pensionEe"), m("payRun.x.pensionEr"),
                        m("payRun.x.hazards"), m("payRun.x.maternityEe"), m("payRun.x.maternityEr"), m("payRun.col.cbhi"), m("payRun.total"));
                for (PayRunLine l : lines) {
                    s.row(l.getEmployeeCode(), l.getEmployeeName(), l.getRssbNumber(), shown(l.getNationalId(), personal), rwf(l.getRssbBase()),
                            rwf(l.getPensionEmployee()), rwf(l.getPensionEmployer()), rwf(l.getOccupationalHazards()), rwf(l.getMaternityEmployee()),
                            rwf(l.getMaternityEmployer()), rwf(l.getCbhi()), rwf(l.getRssbTotal()));
                }
                s.bold(m("payRun.total"), null, null, null, rwf(sum(lines, PayRunLine::getRssbBase)), rwf(sum(lines, PayRunLine::getPensionEmployee)),
                        rwf(sum(lines, PayRunLine::getPensionEmployer)), rwf(sum(lines, PayRunLine::getOccupationalHazards)),
                        rwf(sum(lines, PayRunLine::getMaternityEmployee)), rwf(sum(lines, PayRunLine::getMaternityEmployer)), rwf(run.getTotalCbhi()),
                        rwf(run.getTotalRssb()));
                yield s.build();
            }
        };
        activityLogService.record(MODULE, "EXPORT_PAY_RUN", "Exported the " + report.name() + " of pay run " + run.getNumber() + " to "
                + format.label(), ActivityStatus.SUCCESS);
        return reportFiles.download(format, report.name().toLowerCase(Locale.ROOT) + "-" + run.getPeriod().format(FILE_MONTH), sheet);
    }

    /** Payslips on A4 (one employee a page): shared with My profile, where each employee downloads their own. */
    static byte[] payslipPdf(PayRun run, List<PayRunLine> lines, PdfService pdfService, ReportFiles reportFiles) {
        Map<String, Object> model = reportFiles.letterhead();
        model.put("run", run);
        model.put("lines", lines);
        model.put("rates", run.getRateSet());
        Map<UUID, String> ids = new HashMap<>();
        Map<UUID, String> accounts = new HashMap<>();
        lines.forEach(l -> {
            ids.put(l.getId(), printed(l.getNationalId()));
            accounts.put(l.getId(), printed(l.getAccountNumber()));
        });
        model.put("ids", ids);
        model.put("accounts", accounts);
        return pdfService.render("payroll/payslip-pdf", model);
    }

    /** Personal data on paper: the last four characters (NFR-12). */
    static String printed(String value) {
        if (value == null) {
            return null;
        }
        return "**** " + (value.length() <= 4 ? value : value.substring(value.length() - 4));
    }

    // ---------------------------------------------------------------- helpers

    private String linesForm(Model model, PayRun run, PayRunLinesDto dto) {
        List<PayRunLine> lines = runService.lines(run.getId());
        model.addAttribute("run", run);
        model.addAttribute("runLines", lines);
        model.addAttribute("linesDto", dto);
        model.addAttribute("owed", runService.shortagesOwed(lines));
        return "payroll/runs/lines";
    }

    private void fail(RedirectAttributes redirect, String action, String what, BusinessException e) {
        String error = messages.get(e.getMessageKey(), e.getArgs());
        activityLogService.record(MODULE, action, what + ": " + error, ActivityStatus.FAILED);
        redirect.addFlashAttribute("flashError", error);
    }

    private String shown(String nationalId, boolean personal) {
        return personal ? nationalId : printed(nationalId);
    }

    /** Payroll amounts are whole RWF: shown without decimals in the exports. */
    private static BigDecimal rwf(BigDecimal amount) {
        return amount == null ? null : amount.setScale(0, java.math.RoundingMode.HALF_UP);
    }

    private static BigDecimal sum(List<PayRunLine> lines, java.util.function.Function<PayRunLine, BigDecimal> amount) {
        return lines.stream().map(amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String m(String key, Object... args) {
        return messages.get(key, args);
    }

    private String numberOf(UUID id) {
        try {
            return runService.findDetailed(id).getNumber();
        } catch (NotFoundException e) {
            return id.toString();
        }
    }
}
