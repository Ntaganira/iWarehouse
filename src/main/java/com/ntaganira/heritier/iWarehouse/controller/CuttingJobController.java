package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.audit.AuditContext;
import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.NumberFormats;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.dto.CuttingJobDto;
import com.ntaganira.heritier.iWarehouse.dto.CuttingResultDto;
import com.ntaganira.heritier.iWarehouse.entity.CuttingJob;
import com.ntaganira.heritier.iWarehouse.entity.CuttingJobLine;
import com.ntaganira.heritier.iWarehouse.entity.StockUnit;
import com.ntaganira.heritier.iWarehouse.enums.*;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.CuttingJobService;
import com.ntaganira.heritier.iWarehouse.service.CuttingYield;
import com.ntaganira.heritier.iWarehouse.service.DataChangeService;
import com.ntaganira.heritier.iWarehouse.service.StockService;
import jakarta.validation.Validator;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindingResult;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : CuttingJobController.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Cutting job screens (PRD-01..09): list, job page (pieces, the cut, History), add and edit
 *               drafts, take a sheet or off-cut (suggested or scanned), put it back, record the cut,
 *               cancel with a reason, cut the rest, and the yield report. PAGE_PRODUCTION +
 *               PERM_VIEW_CUTTING_JOB; drafts and cancelling PERM_MANAGE_CUTTING_JOB; taking a sheet and
 *               recording the cut PERM_EXECUTE_CUTTING_JOB; yield PAGE_CUTTING_YIELD + PERM_VIEW_CUTTING_YIELD.
 * </pre>
 */
@Controller
@RequestMapping("/cutting-jobs")
public class CuttingJobController {

    static final String MODULE = "Cutting Jobs";
    private static final int REASON_MAX = 255;

    private final CuttingJobService jobService;
    private final StockService stockService;
    private final DataChangeService dataChangeService;
    private final ActivityLogService activityLogService;
    private final SpringValidatorAdapter validator;
    private final Messages messages;
    private final NumberFormats num;

    public CuttingJobController(CuttingJobService jobService, StockService stockService, DataChangeService dataChangeService,
                                ActivityLogService activityLogService, Validator validator, Messages messages,
                                NumberFormats num) {
        this.jobService = jobService;
        this.stockService = stockService;
        this.dataChangeService = dataChangeService;
        this.activityLogService = activityLogService;
        this.validator = new SpringValidatorAdapter(validator);
        this.messages = messages;
        this.num = num;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PAGE_PRODUCTION') and hasAuthority('PERM_VIEW_CUTTING_JOB')")
    public String list(@RequestParam(required = false) String search,
                       @RequestParam(required = false) String status,
                       @RequestParam(required = false) String purpose,
                       @RequestParam(defaultValue = "0") int page,
                       Model model) {
        Page<CuttingJob> jobs = jobService.findPage(search, status, purpose, Paging.page(page), Paging.SIZE);
        model.addAttribute("jobs", jobs);
        model.addAttribute("needs", jobService.needs(jobs.getContent()));
        model.addAttribute("statuses", CuttingJobStatus.values());
        model.addAttribute("purposes", CuttingPurpose.values());
        model.addAttribute("search", search);
        model.addAttribute("status", status);
        model.addAttribute("purpose", purpose);
        model.addAttribute("paginationQuery", QueryString.of("search", search, "status", status, "purpose", purpose));
        return "cutting-jobs/list";
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PAGE_PRODUCTION') and hasAuthority('PERM_VIEW_CUTTING_JOB')")
    public String view(@PathVariable UUID id, @RequestParam(defaultValue = "pieces") String tab,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        CuttingJob job = jobService.findDetailed(id);
        model.addAttribute("job", job);
        model.addAttribute("need", CuttingJobService.need(job));
        model.addAttribute("source", jobService.source(job).orElse(null));
        model.addAttribute("outcome", jobService.outcome(job));
        model.addAttribute("names", jobService.processingNames());
        model.addAttribute("parent", job.getParentJobId() == null ? null : jobService.findById(job.getParentJobId()));
        model.addAttribute("restJobs", jobService.restJobs(id));
        model.addAttribute("locations", stockService.locationsById());
        model.addAttribute("history", dataChangeService.historyWithChildren("CuttingJob", id.toString(),
                List.of("CuttingJobLine"), "job", Paging.page(page), Paging.SIZE));
        boolean cut = job.getStatus() == CuttingJobStatus.IN_PROGRESS || job.getStatus() == CuttingJobStatus.COMPLETED;
        model.addAttribute("tab", "history".equals(tab) || ("cut".equals(tab) && cut) ? tab : "pieces");
        return "cutting-jobs/view";
    }

    // ---------------------------------------------------------------- drafts (PRD-01)

    @GetMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_PRODUCTION') and hasAuthority('PERM_MANAGE_CUTTING_JOB')")
    public String createForm(Model model) {
        return form(model, jobService.newForm(), null);
    }

    @PostMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_PRODUCTION') and hasAuthority('PERM_MANAGE_CUTTING_JOB')")
    public String create(@ModelAttribute("jobDto") CuttingJobDto dto, BindingResult result, Model model,
                         RedirectAttributes redirect) {
        validate(dto, result);
        if (result.hasErrors()) {
            return invalid(model, dto, null, result);
        }
        try {
            CuttingJob job = jobService.create(dto);
            activityLogService.record(MODULE, "CREATE_CUTTING_JOB", "Created cutting job " + job.getNumber() + ": "
                    + describe(job), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("cutting.created", job.getNumber()));
            return "redirect:/cutting-jobs/" + job.getId();
        } catch (BusinessException e) {
            activityLogService.record(MODULE, "CREATE_CUTTING_JOB", "Failed to create a cutting job: "
                    + messages.get(e.getMessageKey(), e.getArgs()), ActivityStatus.FAILED);
            return rejected(model, dto, null, result, e);
        }
    }

    @GetMapping("/{id}/edit")
    @PreAuthorize("hasAuthority('PAGE_PRODUCTION') and hasAuthority('PERM_MANAGE_CUTTING_JOB')")
    public String editForm(@PathVariable UUID id, Model model, RedirectAttributes redirect) {
        CuttingJob job = jobService.findDetailed(id);
        if (job.getStatus() != CuttingJobStatus.DRAFT) {
            redirect.addFlashAttribute("flashError", messages.get("cutting.notDraft", job.getNumber()));
            return "redirect:/cutting-jobs/" + id;
        }
        return form(model, jobService.formOf(job), job);
    }

    @PostMapping("/{id}/edit")
    @PreAuthorize("hasAuthority('PAGE_PRODUCTION') and hasAuthority('PERM_MANAGE_CUTTING_JOB')")
    public String update(@PathVariable UUID id, @ModelAttribute("jobDto") CuttingJobDto dto, BindingResult result,
                         Model model, RedirectAttributes redirect) {
        CuttingJob current = jobService.findDetailed(id);
        dto.setId(id);
        validate(dto, result);
        if (result.hasErrors()) {
            return invalid(model, dto, current, result);
        }
        try {
            CuttingJob job = jobService.update(id, dto);
            activityLogService.record(MODULE, "UPDATE_CUTTING_JOB", "Updated cutting job " + job.getNumber() + ": "
                    + describe(job), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("cutting.updated", job.getNumber()));
            return "redirect:/cutting-jobs/" + id;
        } catch (BusinessException e) {
            activityLogService.record(MODULE, "UPDATE_CUTTING_JOB", "Failed to update cutting job " + current.getNumber()
                    + ": " + messages.get(e.getMessageKey(), e.getArgs()), ActivityStatus.FAILED);
            return rejected(model, dto, current, result, e);
        }
    }

    // ---------------------------------------------------------------- the source (PRD-02)

    @GetMapping("/{id}/start")
    @PreAuthorize("hasAuthority('PAGE_PRODUCTION') and hasAuthority('PERM_EXECUTE_CUTTING_JOB')")
    public String startForm(@PathVariable UUID id, Model model, RedirectAttributes redirect) {
        CuttingJob job = jobService.findDetailed(id);
        if (job.getStatus() != CuttingJobStatus.DRAFT) {
            redirect.addFlashAttribute("flashError", messages.get("cutting.notDraft", job.getNumber()));
            return "redirect:/cutting-jobs/" + id;
        }
        return startPage(model, job, null, null);
    }

    @PostMapping("/{id}/start")
    @PreAuthorize("hasAuthority('PAGE_PRODUCTION') and hasAuthority('PERM_EXECUTE_CUTTING_JOB')")
    public String start(@PathVariable UUID id, @RequestParam(required = false) UUID unitId,
                        @RequestParam(required = false) String code, Model model, RedirectAttributes redirect) {
        if (unitId == null && !StringUtils.hasText(code)) {
            return startPage(model, jobService.findDetailed(id), code, messages.get("cutting.source.codeRequired"));
        }
        try {
            CuttingJob job = jobService.start(id, unitId, code);
            activityLogService.record(MODULE, "START_CUTTING_JOB", "Took " + job.getSourceCode() + " ("
                    + job.getSourceWidthMm() + " x " + job.getSourceHeightMm() + " mm, " + num.m2(job.getSourceAreaM2())
                    + " m2) for cutting job " + job.getNumber(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("cutting.started", job.getSourceCode(), job.getNumber()));
            return "redirect:/cutting-jobs/" + id + "?tab=cut";
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "START_CUTTING_JOB", "Failed to take a unit for " + numberOf(id) + ": " + error,
                    ActivityStatus.FAILED);
            CuttingJob job = jobService.findDetailed(id);
            if (job.getStatus() != CuttingJobStatus.DRAFT) {
                redirect.addFlashAttribute("flashError", error);
                return "redirect:/cutting-jobs/" + id;
            }
            return startPage(model, job, code, error);
        }
    }

    @PostMapping("/{id}/release")
    @PreAuthorize("hasAuthority('PAGE_PRODUCTION') and hasAuthority('PERM_EXECUTE_CUTTING_JOB')")
    public String release(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        if (!StringUtils.hasText(reason) || reason.trim().length() > REASON_MAX) {
            redirect.addFlashAttribute("flashError", messages.get("po.reason.required"));
            return "redirect:/cutting-jobs/" + id;
        }
        try {
            String code = jobService.findById(id).getSourceCode();
            CuttingJob job = AuditContext.withReason(reason.trim(), () -> jobService.release(id, reason));
            activityLogService.record(MODULE, "RELEASE_CUTTING_JOB", "Put " + code + " back uncut from cutting job "
                    + job.getNumber() + ": " + reason.trim(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("cutting.released", code, job.getNumber()));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "RELEASE_CUTTING_JOB", "Failed to put the unit of " + numberOf(id)
                    + " back: " + error, ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:/cutting-jobs/" + id;
    }

    // ---------------------------------------------------------------- the cut (PRD-03..08)

    @GetMapping("/{id}/complete")
    @PreAuthorize("hasAuthority('PAGE_PRODUCTION') and hasAuthority('PERM_EXECUTE_CUTTING_JOB')")
    public String completeForm(@PathVariable UUID id, Model model, RedirectAttributes redirect) {
        CuttingJob job = jobService.findDetailed(id);
        if (job.getStatus() != CuttingJobStatus.IN_PROGRESS) {
            redirect.addFlashAttribute("flashError", messages.get("cutting.notInProgress", job.getNumber()));
            return "redirect:/cutting-jobs/" + id;
        }
        return completePage(model, job, jobService.resultForm(job));
    }

    @PostMapping("/{id}/complete")
    @PreAuthorize("hasAuthority('PAGE_PRODUCTION') and hasAuthority('PERM_EXECUTE_CUTTING_JOB')")
    public String complete(@PathVariable UUID id, @ModelAttribute("resultDto") CuttingResultDto dto, BindingResult result,
                           Model model, RedirectAttributes redirect) {
        CuttingJob current = jobService.findDetailed(id);
        if (current.getStatus() != CuttingJobStatus.IN_PROGRESS) {
            redirect.addFlashAttribute("flashError", messages.get("cutting.notInProgress", current.getNumber()));
            return "redirect:/cutting-jobs/" + id;
        }
        dto.getLeftovers().removeIf(CuttingResultDto.Leftover::isBlank);
        dto.getBroken().removeIf(CuttingResultDto.Broken::isBlank);
        validator.validate(dto, result);
        if (result.hasErrors()) {
            return completeInvalid(model, current, dto, result);
        }
        try {
            CuttingJobService.CutResult cut = jobService.complete(id, dto);
            CuttingJob job = cut.job();
            activityLogService.record(MODULE, "COMPLETE_CUTTING_JOB", "Cut " + job.getSourceCode() + " for "
                    + job.getNumber() + ": " + cut.pieces().size() + " piece(s) " + num.m2(job.getPiecesAreaM2()) + " m2"
                    + codes(cut.pieces()) + ", " + cut.offcuts().size() + " off-cut(s) " + num.m2(job.getOffcutAreaM2()) + " m2"
                    + codes(cut.offcuts()) + ", cullet " + num.m2(job.getCulletAreaM2()) + " m2 (" + num.kg(job.getCulletKg())
                    + " kg), broken " + num.m2(job.getBrokenAreaM2()) + " m2; yield " + job.getYieldPercent().toPlainString()
                    + "%; " + num.money(cut.spoilageCost()) + " RWF to spoilage", ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("cutting.completed", job.getNumber(), cut.pieces().size(),
                    cut.offcuts().size(), job.getYieldPercent()));
            return "redirect:/cutting-jobs/" + id + "?tab=cut";
        } catch (BusinessException e) {
            activityLogService.record(MODULE, "COMPLETE_CUTTING_JOB", "Failed to record the cut of " + current.getNumber()
                    + ": " + messages.get(e.getMessageKey(), e.getArgs()), ActivityStatus.FAILED);
            String error = messages.get(e.getMessageKey(), e.getArgs());
            if (e.getField() != null) {
                result.rejectValue(e.getField(), e.getMessageKey(), e.getArgs(), error);
            } else {
                model.addAttribute("flashError", error);
            }
            return completeInvalid(model, current, dto, result);
        }
    }

    // ---------------------------------------------------------------- cancel, cut the rest

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('PAGE_PRODUCTION') and hasAuthority('PERM_MANAGE_CUTTING_JOB')")
    public String cancel(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        if (!StringUtils.hasText(reason) || reason.trim().length() > REASON_MAX) {
            redirect.addFlashAttribute("flashError", messages.get("po.reason.required"));
            return "redirect:/cutting-jobs/" + id;
        }
        try {
            String code = jobService.findById(id).getSourceCode();
            CuttingJob job = AuditContext.withReason(reason.trim(), () -> jobService.cancel(id, reason));
            activityLogService.record(MODULE, "CANCEL_CUTTING_JOB", "Cancelled cutting job " + job.getNumber()
                    + (code == null ? "" : " (" + code + " put back uncut)") + ": " + reason.trim(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("cutting.cancelledMsg", job.getNumber()));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "CANCEL_CUTTING_JOB", "Failed to cancel cutting job " + numberOf(id) + ": "
                    + error, ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:/cutting-jobs/" + id;
    }

    @PostMapping("/{id}/rest")
    @PreAuthorize("hasAuthority('PAGE_PRODUCTION') and hasAuthority('PERM_MANAGE_CUTTING_JOB')")
    public String cutRest(@PathVariable UUID id, RedirectAttributes redirect) {
        try {
            CuttingJob rest = jobService.cutRest(id);
            CuttingJob job = jobService.findById(id);
            activityLogService.record(MODULE, "CREATE_CUTTING_JOB", "Created cutting job " + rest.getNumber()
                    + " for the pieces " + job.getNumber() + " did not cut: " + describe(rest), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("cutting.restCreated", rest.getNumber(), job.getNumber()));
            return "redirect:/cutting-jobs/" + rest.getId();
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "CREATE_CUTTING_JOB", "Failed to create a job for the rest of " + numberOf(id)
                    + ": " + error, ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
            return "redirect:/cutting-jobs/" + id;
        }
    }

    // ---------------------------------------------------------------- yield (PRD-09)

    @GetMapping("/yield")
    @PreAuthorize("hasAuthority('PAGE_CUTTING_YIELD') and hasAuthority('PERM_VIEW_CUTTING_YIELD')")
    public String yield(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                        @RequestParam(required = false) UUID product,
                        @RequestParam(required = false) String operator,
                        @RequestParam(defaultValue = "0") int opage, @RequestParam(defaultValue = "0") int gpage,
                        @RequestParam(defaultValue = "0") int bpage, Model model) {
        LocalDate today = jobService.today();
        LocalDate end = to == null ? today : to;
        LocalDate start = from == null ? end.withDayOfMonth(1) : from;
        if (start.isAfter(end)) {
            LocalDate swap = start;
            start = end;
            end = swap;
        }
        CuttingJobService.YieldReport report = jobService.yieldReport(start, end, product, operator);
        model.addAttribute("report", report);
        // Three tables, each with its own page (operator, glass, breakage); each pager keeps the filters and the other pages
        Page<CuttingYield.Row> operators = Paging.of(report.byOperator(), opage);
        Page<CuttingYield.Row> products = Paging.of(report.byProduct(), gpage);
        Page<CuttingYield.Breakage> breakage = Paging.of(report.breakage(), bpage);
        String filters = QueryString.of("from", start, "to", end, "product", product, "operator", operator);
        model.addAttribute("operatorPage", operators);
        model.addAttribute("productPage", products);
        model.addAttribute("breakagePage", breakage);
        model.addAttribute("operatorQuery", filters + "&gpage=" + products.getNumber() + "&bpage=" + breakage.getNumber());
        model.addAttribute("productQuery", filters + "&opage=" + operators.getNumber() + "&bpage=" + breakage.getNumber());
        model.addAttribute("breakageQuery", filters + "&opage=" + operators.getNumber() + "&gpage=" + products.getNumber());
        model.addAttribute("from", start);
        model.addAttribute("to", end);
        model.addAttribute("product", product);
        model.addAttribute("operator", operator);
        model.addAttribute("products", jobService.allProducts());
        model.addAttribute("operators", jobService.operators());
        return "cutting-jobs/yield";
    }

    // ---------------------------------------------------------------- helpers

    /** "for stock, CLR-6, 3 piece(s) in 2 size(s) 5.0000 m2" or "for Kigali Builders (Q-17), ...". */
    private String describe(CuttingJob job) {
        String who = job.getPurpose() == CuttingPurpose.CUSTOMER
                ? "for " + job.getCustomer().getName() + (job.getCustomerRef() == null ? "" : " (" + job.getCustomerRef() + ")")
                : "for stock";
        String sizes = job.getLines().stream()
                .map(l -> l.getQuantity() + " x " + l.getWidthMm() + "x" + l.getHeightMm()
                        + (l.getProcessing() == null ? "" : " [" + l.getProcessing() + "]"))
                .collect(Collectors.joining(", "));
        return who + ", " + job.getProduct().getCode() + ", " + job.getPieces() + " piece(s) "
                + num.m2(job.getPiecesWantedM2()) + " m2: " + sizes;
    }

    private static String codes(List<StockUnit> units) {
        return units.isEmpty() ? "" : " (" + units.stream().map(StockUnit::getCode).collect(Collectors.joining(", ")) + ")";
    }

    /** Drops empty rows, then runs Bean Validation (so a spare empty row is not an error). */
    private void validate(CuttingJobDto dto, BindingResult result) {
        dto.getLines().removeIf(CuttingJobDto.Line::isBlank);
        validator.validate(dto, result);
    }

    private String numberOf(UUID id) {
        try {
            return jobService.findById(id).getNumber();
        } catch (NotFoundException e) {
            return id.toString();
        }
    }

    private String form(Model model, CuttingJobDto dto, CuttingJob current) {
        if (dto.getLines().isEmpty()) {
            dto.getLines().add(new CuttingJobDto.Line());
        }
        model.addAttribute("jobDto", dto);
        model.addAttribute("job", current);
        model.addAttribute("products", jobService.products(current));
        model.addAttribute("customers", jobService.customers(current));
        model.addAttribute("services", jobService.processing(dto));
        model.addAttribute("purposes", CuttingPurpose.values());
        model.addAttribute("today", jobService.today());
        return "cutting-jobs/form";
    }

    private String invalid(Model model, CuttingJobDto dto, CuttingJob current, BindingResult result) {
        model.addAttribute("formErrors", result.getFieldErrors());
        if (result.hasGlobalErrors()) {
            model.addAttribute("flashError", result.getGlobalError().getDefaultMessage());
        }
        return form(model, dto, current);
    }

    /** A business rule refused the form: show it next to its field, or as a toast. */
    private String rejected(Model model, CuttingJobDto dto, CuttingJob current, BindingResult result, BusinessException e) {
        String error = messages.get(e.getMessageKey(), e.getArgs());
        if (e.getField() != null) {
            result.rejectValue(e.getField(), e.getMessageKey(), e.getArgs(), error);
        } else {
            model.addAttribute("flashError", error);
        }
        return invalid(model, dto, current, result);
    }

    private String startPage(Model model, CuttingJob job, String code, String error) {
        model.addAttribute("job", job);
        model.addAttribute("need", CuttingJobService.need(job));
        model.addAttribute("suggestions", jobService.suggestions(job));
        model.addAttribute("locations", stockService.locationsById());
        model.addAttribute("names", jobService.processingNames());
        model.addAttribute("code", code);
        model.addAttribute("codeError", error);
        return "cutting-jobs/start";
    }

    private String completePage(Model model, CuttingJob job, CuttingResultDto dto) {
        if (dto.getLeftovers().isEmpty()) {
            dto.getLeftovers().add(new CuttingResultDto.Leftover());
        }
        if (dto.getBroken().isEmpty()) {
            dto.getBroken().add(new CuttingResultDto.Broken());
        }
        model.addAttribute("job", job);
        model.addAttribute("resultDto", dto);
        model.addAttribute("source", jobService.source(job).orElseThrow(() -> new NotFoundException("StockUnit", job.getSourceUnitId())));
        model.addAttribute("names", jobService.processingNames());
        model.addAttribute("pieceLocations", jobService.pieceLocations());
        model.addAttribute("offcutLocations", jobService.offcutLocations());
        model.addAttribute("threshold", jobService.threshold());
        model.addAttribute("reasons", BreakageReason.values());
        model.addAttribute("lineById", job.getLines().stream().collect(Collectors.toMap(CuttingJobLine::getId, l -> l)));
        model.addAttribute("seeCost", AppUserPrincipal.currentHas("PERM_VIEW_STOCK_COST"));
        return "cutting-jobs/complete";
    }

    private String completeInvalid(Model model, CuttingJob job, CuttingResultDto dto, BindingResult result) {
        model.addAttribute("formErrors", result.getFieldErrors());
        return completePage(model, job, dto);
    }
}
