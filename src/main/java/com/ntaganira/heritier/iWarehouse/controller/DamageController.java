package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.audit.AuditContext;
import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.dto.DamageReportDto;
import com.ntaganira.heritier.iWarehouse.entity.*;
import com.ntaganira.heritier.iWarehouse.enums.*;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.repository.CreditNoteRepository;
import com.ntaganira.heritier.iWarehouse.repository.CreditNoteUnitRepository;
import com.ntaganira.heritier.iWarehouse.repository.CustomerRepository;
import com.ntaganira.heritier.iWarehouse.repository.DriverRepository;
import com.ntaganira.heritier.iWarehouse.repository.SupplierRepository;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import com.ntaganira.heritier.iWarehouse.service.*;
import jakarta.validation.Validator;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : DamageController.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : Damage (IMP 13, F-07, DMG-009): the damage reports, the form (also from a credit note's returned units,
 *               held for inspection), a report's page (its units, documents and History; inspect, approve unit by unit,
 *               reject, cancel) and the damage register of every damage of a period, exported.
 * </pre>
 */
@Controller
@RequestMapping("/damage")
public class DamageController {

    static final String MODULE = "Damage";
    private static final String VIEW = "hasAuthority('PAGE_DAMAGE') and hasAuthority('PERM_VIEW_DAMAGE')";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final DamageService damageService;
    private final DamageRegisterService registerService;
    private final StockService stockService;
    private final AttachmentService attachmentService;
    private final DataChangeService dataChangeService;
    private final ActivityLogService activityLogService;
    private final CustomerRepository customerRepo;
    private final SupplierRepository supplierRepo;
    private final DriverRepository driverRepo;
    private final CreditNoteRepository creditNoteRepo;
    private final CreditNoteUnitRepository creditNoteUnitRepo;
    private final ReportFiles reportFiles;
    private final SpringValidatorAdapter validator;
    private final Messages messages;
    private final Clock clock;

    public DamageController(DamageService damageService, DamageRegisterService registerService, StockService stockService,
                            AttachmentService attachmentService, DataChangeService dataChangeService, ActivityLogService activityLogService,
                            CustomerRepository customerRepo, SupplierRepository supplierRepo, DriverRepository driverRepo,
                            CreditNoteRepository creditNoteRepo, CreditNoteUnitRepository creditNoteUnitRepo,
                            ReportFiles reportFiles, Validator validator, Messages messages, Clock clock) {
        this.damageService = damageService;
        this.registerService = registerService;
        this.stockService = stockService;
        this.attachmentService = attachmentService;
        this.dataChangeService = dataChangeService;
        this.activityLogService = activityLogService;
        this.customerRepo = customerRepo;
        this.supplierRepo = supplierRepo;
        this.driverRepo = driverRepo;
        this.creditNoteRepo = creditNoteRepo;
        this.creditNoteUnitRepo = creditNoteUnitRepo;
        this.reportFiles = reportFiles;
        this.validator = new SpringValidatorAdapter(validator);
        this.messages = messages;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- the reports

    @GetMapping
    @PreAuthorize(VIEW)
    public String list(@RequestParam(required = false) String search, @RequestParam(required = false) String status,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        var reports = damageService.findPage(search, status, page, Paging.SIZE);
        model.addAttribute("reports", reports);
        model.addAttribute("unitCounts", damageService.unitCounts(reports.getContent().stream().map(DamageReport::getId).toList()));
        model.addAttribute("search", search);
        model.addAttribute("status", status);
        model.addAttribute("statuses", DamageStatus.values());
        StringBuilder q = new StringBuilder();
        if (StringUtils.hasText(search)) {
            q.append("search=").append(java.net.URLEncoder.encode(search, java.nio.charset.StandardCharsets.UTF_8));
        }
        if (StringUtils.hasText(status)) {
            q.append(q.isEmpty() ? "" : "&").append("status=").append(status);
        }
        model.addAttribute("query", q.toString());
        return "damage/list";
    }

    // ---------------------------------------------------------------- the register (IMP 13.2, DMG-009)

    @GetMapping("/register")
    @PreAuthorize(VIEW)
    public String register(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                           @RequestParam(required = false) DamageRegisterService.Source source, @RequestParam(defaultValue = "0") int page,
                           Model model) {
        LocalDate today = LocalDate.now(clock);
        LocalDate f = from == null ? today.withDayOfMonth(1) : from;
        LocalDate t = to == null || to.isBefore(f) ? today : to;
        DamageRegisterService.Register register = registerService.register(f, t, source);
        model.addAttribute("register", register);
        model.addAttribute("rows", Paging.of(register.rows(), page));
        model.addAttribute("from", f);
        model.addAttribute("to", t);
        model.addAttribute("source", source);
        model.addAttribute("sources", DamageRegisterService.Source.values());
        model.addAttribute("query", "from=" + f + "&to=" + t + (source == null ? "" : "&source=" + source));
        return "damage/register";
    }

    @GetMapping("/register/export")
    @PreAuthorize(VIEW)
    public ResponseEntity<byte[]> registerExport(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                 @RequestParam(required = false) DamageRegisterService.Source source,
                                                 @RequestParam(defaultValue = "XLSX") ReportFiles.Format format) {
        DamageRegisterService.Register r = registerService.register(from, to, source);
        Excel.Builder sheet = Excel.sheet(m("damage.register.title"), m("damage.register.title"),
                m("damage.register.of", from.format(DAY), to.format(DAY)), m("damage.register.day"), m("damage.register.source"),
                m("damage.register.document"), m("damage.place"), m("damage.register.units"), m("damage.register.area"), m("damage.register.value"),
                m("damage.cause"), m("common.status"));
        for (DamageRegisterService.Row row : r.rows()) {
            sheet.row(row.day(), m("damage.register.source." + row.source()), row.number(), row.place() == null ? "" : row.place(), row.units(),
                    row.areaM2(), row.value(), causeText(row), row.statusKey() == null ? "" : m(row.statusKey()));
        }
        sheet.bold(m("damage.register.total"), "", "", "", r.total().units(), r.total().areaM2(), r.total().value(), "", "");
        activityLogService.record(MODULE, "EXPORT_DAMAGE_REGISTER", "Exported the damage register of " + from + " to " + to + " to " + format.label(),
                ActivityStatus.SUCCESS);
        return reportFiles.download(format, "damage-register-" + from + "-" + to, sheet.build());
    }

    private String causeText(DamageRegisterService.Row row) {
        String key = row.causeKey() == null ? null : messages.get(row.causeKey());
        if (key != null && row.cause() != null) {
            return key + ": " + row.cause();
        }
        return key != null ? key : row.cause() == null ? "" : row.cause();
    }

    // ---------------------------------------------------------------- reporting

    @GetMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_DAMAGE') and hasAuthority('PERM_REPORT_DAMAGE')")
    public String form(@RequestParam(required = false) UUID creditNote, Model model) {
        DamageReportDto dto = new DamageReportDto();
        dto.setOccurredOn(LocalDate.now(clock));
        if (creditNote != null) {
            // A customer's return held for inspection (F-07 C): the units the credit note put back on a rack
            creditNoteRepo.findById(creditNote).ifPresent(n -> {
                List<CreditNoteUnit> back = creditNoteUnitRepo.findByCreditNoteIdOrderByUnitCode(n.getId()).stream()
                        .filter(u -> u.getOutcome() == ReturnOutcome.RESTOCK).toList();
                dto.setCreditNoteId(n.getId());
                dto.setCause(DamageCause.CUSTOMER_RETURN);
                dto.setCodes(back.stream().map(CreditNoteUnit::getUnitCode).collect(Collectors.joining("\n")));
                dto.setDescription(messages.get("damage.fromCreditNote", n.getNumber()));
                if (!back.isEmpty() && back.get(0).getLocation() != null) {
                    dto.setLocationId(back.get(0).getLocation().getId());
                }
            });
        }
        return form(dto, model);
    }

    private String form(DamageReportDto dto, Model model) {
        model.addAttribute("damageDto", dto);
        model.addAttribute("places", stockService.locationsById().values().stream()
                .filter(Location::isEnabled).filter(l -> l.getType() != LocationType.VEHICLE)
                .sorted(Comparator.comparing(Location::getCode)).toList());
        model.addAttribute("causes", DamageCause.values());
        model.addAttribute("recoveries", RecoveryFrom.values());
        model.addAttribute("customers", customerRepo.findByEnabledTrueOrderByNameAsc().stream().filter(c -> !c.isDefaultCustomer()).toList());
        model.addAttribute("suppliers", supplierRepo.findAll(org.springframework.data.domain.Sort.by("name")));
        model.addAttribute("drivers", driverRepo.findAll().stream().filter(Driver::isEnabled).toList());
        model.addAttribute("today", LocalDate.now(clock));
        return "damage/form";
    }

    @PostMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_DAMAGE') and hasAuthority('PERM_REPORT_DAMAGE')")
    public String report(@ModelAttribute("damageDto") DamageReportDto dto, BindingResult binding, Model model, RedirectAttributes redirect) {
        validator.validate(dto, binding);
        if (binding.hasErrors()) {
            return form(dto, model);
        }
        try {
            DamageReport report = damageService.report(dto);
            activityLogService.record(MODULE, "CREATE_DAMAGE_REPORT", "Reported damage " + report.getNumber() + ": " + report.getLines().size()
                    + " units at " + report.getLocation().getCode() + ", " + report.getEstimatedLoss().toPlainString() + " RWF at cost ("
                    + report.getCause() + ")", ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("damage.reported", report.getNumber()));
            return "redirect:/damage/" + report.getId();
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "CREATE_DAMAGE_REPORT", "Failed to report damage: " + error, ActivityStatus.FAILED);
            if (e.getField() != null) {
                binding.rejectValue(e.getField(), e.getMessageKey(), e.getArgs(), error);
            } else {
                model.addAttribute("flashError", error);
            }
            return form(dto, model);
        }
    }

    // ---------------------------------------------------------------- a report

    @GetMapping("/{id}")
    @PreAuthorize(VIEW)
    public String view(@PathVariable UUID id, @RequestParam(defaultValue = "lines") String tab, @RequestParam(defaultValue = "0") int page,
                       Model model) {
        DamageReport report = damageService.findDetailed(id);
        String open = List.of("documents", "history").contains(tab) ? tab : "lines";
        Long me = AppUserPrincipal.current().map(AppUserPrincipal::getId).orElse(null);
        model.addAttribute("report", report);
        model.addAttribute("units", damageService.unitsOf(report));
        model.addAttribute("locations", stockService.locationsById());
        model.addAttribute("mine", me != null && me.equals(report.getReportedById()));
        model.addAttribute("inspectedByMe", me != null && me.equals(report.getInspectedById()));
        model.addAttribute("inspectionRequired", damageService.inspectionRequired());
        model.addAttribute("outcomes", DamageOutcome.values());
        model.addAttribute("authority", report.isOpen() ? damageService.authorityOf(report) : null);
        model.addAttribute("documents", attachmentService.page(AttachmentOwner.DAMAGE_REPORT, id, Paging.pageOf("documents", open, page), Paging.SIZE));
        model.addAttribute("history", dataChangeService.historyWithChildren("DamageReport", id.toString(), "DamageReportLine", "report",
                Paging.pageOf("history", open, page), Paging.SIZE));
        model.addAttribute("tab", open);
        return "damage/view";
    }

    @PostMapping("/{id}/inspect")
    @PreAuthorize("hasAuthority('PAGE_DAMAGE') and hasAuthority('PERM_INSPECT_DAMAGE')")
    public String inspect(@PathVariable UUID id, @RequestParam(required = false) String note, RedirectAttributes redirect) {
        try {
            DamageReport r = damageService.inspect(id, note);
            activityLogService.record(MODULE, "APPROVE_DAMAGE_INSPECTION", "Inspected damage " + r.getNumber() + ": " + r.getInspectionNote(),
                    ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("damage.inspectedMsg", r.getNumber()));
        } catch (BusinessException e) {
            failed(id, "APPROVE_DAMAGE_INSPECTION", "inspect", e, redirect);
        }
        return "redirect:/damage/" + id;
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('PAGE_DAMAGE') and hasAuthority('PERM_APPROVE_DAMAGE')")
    public String approve(@PathVariable UUID id, @RequestParam Map<String, String> params, RedirectAttributes redirect) {
        Map<UUID, DamageOutcome> outcomes = new HashMap<>();
        params.forEach((k, v) -> {
            if (k.startsWith("outcome_")) {
                try {
                    outcomes.put(UUID.fromString(k.substring(8)), DamageOutcome.valueOf(v));
                } catch (IllegalArgumentException e) {
                    // not an outcome: written off
                }
            }
        });
        try {
            DamageReport r = damageService.approve(id, outcomes, params.get("note"));
            long off = r.getLines().stream().filter(l -> l.getOutcome() == DamageOutcome.WRITE_OFF).count();
            activityLogService.record(MODULE, "APPROVE_DAMAGE_REPORT", "Approved damage " + r.getNumber() + ": " + off + " units written off"
                    + (r.getAdjustmentNumber() == null ? "" : " on " + r.getAdjustmentNumber()) + ", " + (r.getLines().size() - off) + " back to stock"
                    + (r.getRecoveryJournal() == null ? "" : "; " + r.getRecoverAmount().toPlainString() + " RWF charged to " + r.getRecoverParty()
                    + " (" + r.getRecoveryJournal() + ")"), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("damage.approvedMsg", r.getNumber(), off, r.getLines().size() - off));
        } catch (BusinessException e) {
            failed(id, "APPROVE_DAMAGE_REPORT", "approve", e, redirect);
        }
        return "redirect:/damage/" + id;
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('PAGE_DAMAGE') and hasAuthority('PERM_APPROVE_DAMAGE')")
    public String reject(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        try {
            DamageReport r = AuditContext.withReason(reason == null ? "" : reason.trim(), () -> damageService.reject(id, reason));
            activityLogService.record(MODULE, "REJECT_DAMAGE_REPORT", "Rejected damage " + r.getNumber() + ": " + r.getDecisionNote(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("damage.rejectedMsg", r.getNumber()));
        } catch (BusinessException e) {
            failed(id, "REJECT_DAMAGE_REPORT", "reject", e, redirect);
        }
        return "redirect:/damage/" + id;
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('PAGE_DAMAGE') and hasAuthority('PERM_REPORT_DAMAGE')")
    public String cancel(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        try {
            DamageReport r = AuditContext.withReason(reason == null ? "" : reason.trim(), () -> damageService.cancel(id, reason));
            activityLogService.record(MODULE, "CANCEL_DAMAGE_REPORT", "Cancelled damage " + r.getNumber() + ": " + r.getDecisionNote(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("damage.cancelledMsg", r.getNumber()));
        } catch (BusinessException e) {
            failed(id, "CANCEL_DAMAGE_REPORT", "cancel", e, redirect);
        }
        return "redirect:/damage/" + id;
    }

    private void failed(UUID id, String action, String verb, BusinessException e, RedirectAttributes redirect) {
        String error = messages.get(e.getMessageKey(), e.getArgs());
        String number;
        try {
            number = damageService.findDetailed(id).getNumber();
        } catch (RuntimeException x) {
            number = id.toString();
        }
        activityLogService.record(MODULE, action, "Failed to " + verb + " damage " + number + ": " + error, ActivityStatus.FAILED);
        redirect.addFlashAttribute("flashError", error);
    }

    private String m(String key, Object... args) {
        return messages.get(key, args);
    }
}
