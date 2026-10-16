package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.entity.StockVariance;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.enums.AttachmentOwner;
import com.ntaganira.heritier.iWarehouse.enums.VarianceCause;
import com.ntaganira.heritier.iWarehouse.enums.VarianceStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.service.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : StockVarianceController.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : Count variances (IMP 16, VR-010): the list (waiting first), a variance's page (the count's differences with
 *               their value, the class, the due day; resolve it with the root cause, corrective action and responsible
 *               officer; its documents and History) and the printed variance report.
 * </pre>
 */
@Controller
@RequestMapping("/stock-variances")
public class StockVarianceController {

    static final String MODULE = "Stock Variances";
    private static final String VIEW = "hasAuthority('PAGE_STOCK_VARIANCES') and hasAuthority('PERM_VIEW_STOCK_VARIANCE')";

    private final StockVarianceService varianceService;
    private final StockCountService countService;
    private final StockService stockService;
    private final AttachmentService attachmentService;
    private final DataChangeService dataChangeService;
    private final ActivityLogService activityLogService;
    private final CompanyLogoService logoService;
    private final SettingService settingService;
    private final Messages messages;

    public StockVarianceController(StockVarianceService varianceService, StockCountService countService, StockService stockService,
                                   AttachmentService attachmentService, DataChangeService dataChangeService, ActivityLogService activityLogService,
                                   CompanyLogoService logoService, SettingService settingService, Messages messages) {
        this.varianceService = varianceService;
        this.countService = countService;
        this.stockService = stockService;
        this.attachmentService = attachmentService;
        this.dataChangeService = dataChangeService;
        this.activityLogService = activityLogService;
        this.logoService = logoService;
        this.settingService = settingService;
        this.messages = messages;
    }

    @GetMapping
    @PreAuthorize(VIEW)
    public String list(@RequestParam(required = false) String search, @RequestParam(required = false) String status,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("variances", varianceService.findPage(search, status, page, Paging.SIZE));
        model.addAttribute("search", search);
        model.addAttribute("status", status);
        model.addAttribute("statuses", VarianceStatus.values());
        StringBuilder q = new StringBuilder();
        if (StringUtils.hasText(search)) {
            q.append("search=").append(java.net.URLEncoder.encode(search, java.nio.charset.StandardCharsets.UTF_8));
        }
        if (StringUtils.hasText(status)) {
            q.append(q.isEmpty() ? "" : "&").append("status=").append(status);
        }
        model.addAttribute("query", q.toString());
        return "stock-variances/list";
    }

    @GetMapping("/{id}")
    @PreAuthorize(VIEW)
    public String view(@PathVariable UUID id, @RequestParam(defaultValue = "lines") String tab, @RequestParam(defaultValue = "0") int page,
                       Model model) {
        StockVariance v = varianceService.findDetailed(id);
        String open = List.of("documents", "history").contains(tab) ? tab : "lines";
        model.addAttribute("variance", v);
        model.addAttribute("lines", Paging.of(varianceService.lines(v), Paging.pageOf("lines", open, page)));
        model.addAttribute("locations", stockService.locationsById());
        model.addAttribute("causes", VarianceCause.values());
        model.addAttribute("documents", attachmentService.page(AttachmentOwner.STOCK_VARIANCE, id, Paging.pageOf("documents", open, page), Paging.SIZE));
        model.addAttribute("history", dataChangeService.history("StockVariance", id.toString(), Paging.pageOf("history", open, page), Paging.SIZE));
        model.addAttribute("tab", open);
        return "stock-variances/view";
    }

    @PostMapping("/{id}/resolve")
    @PreAuthorize("hasAuthority('PAGE_STOCK_VARIANCES') and hasAuthority('PERM_RESOLVE_VARIANCE')")
    public String resolve(@PathVariable UUID id, @RequestParam(required = false) VarianceCause rootCause,
                          @RequestParam(required = false) String correctiveAction, @RequestParam(required = false) String responsibleOfficer,
                          @RequestParam(required = false) String note, RedirectAttributes redirect) {
        try {
            StockVariance v = varianceService.resolve(id, new StockVarianceService.Form(rootCause, correctiveAction, responsibleOfficer, note));
            activityLogService.record(MODULE, "APPROVE_STOCK_VARIANCE", "Resolved variance " + v.getNumber() + " (" + v.getRootCause() + "): "
                    + v.getCorrectiveAction() + "; responsible " + v.getResponsibleOfficer(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("variance.resolvedMsg", v.getNumber()));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "APPROVE_STOCK_VARIANCE", "Failed to resolve variance " + id + ": " + error, ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:/stock-variances/" + id;
    }

    /** The printed variance report (VR-010): the count, each difference with its value, the investigation, the signatures. */
    @GetMapping("/{id}/report")
    @PreAuthorize(VIEW)
    public String report(@PathVariable UUID id, Model model) {
        StockVariance v = varianceService.findDetailed(id);
        model.addAttribute("variance", v);
        model.addAttribute("lines", varianceService.lines(v));
        model.addAttribute("participants", countService.participants(v.getCount().getId()));
        model.addAttribute("locations", stockService.locationsById());
        model.addAttribute("companyLogoUrl", logoService.url());
        model.addAttribute("companyName", settingService.get(com.ntaganira.heritier.iWarehouse.enums.SettingKey.COMPANY_NAME));
        return "stock-variances/report";
    }
}
