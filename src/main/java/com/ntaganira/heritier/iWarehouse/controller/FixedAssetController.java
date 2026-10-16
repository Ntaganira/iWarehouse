package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.audit.AuditContext;
import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.NumberFormats;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.dto.FixedAssetDto;
import com.ntaganira.heritier.iWarehouse.entity.DepreciationRun;
import com.ntaganira.heritier.iWarehouse.entity.FixedAsset;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.enums.FixedAssetCategory;
import com.ntaganira.heritier.iWarehouse.enums.FixedAssetStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.DataChangeService;
import com.ntaganira.heritier.iWarehouse.service.FixedAssetService;
import com.ntaganira.heritier.iWarehouse.service.JournalService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : FixedAssetController.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : The fixed-asset register (C.08, U.12): the assets with their cost, depreciation and book value, an asset's page
 *               (its depreciation, documents of origin, history), registering an asset at its value to date and changing one
 *               (PERM_MANAGE_FIXED_ASSETS), disposing of one (PERM_DISPOSE_ASSET), and the monthly depreciation runs: the next
 *               month's preview, posting it and reversing the latest (PERM_RUN_DEPRECIATION). Each needs PAGE_FIXED_ASSETS.
 * </pre>
 */
@Controller
@RequestMapping("/fixed-assets")
public class FixedAssetController {

    static final String MODULE = "Accounting";
    private static final String VIEW = "hasAuthority('PAGE_FIXED_ASSETS') and hasAuthority('PERM_VIEW_FIXED_ASSETS')";
    private static final String MANAGE = "hasAuthority('PAGE_FIXED_ASSETS') and hasAuthority('PERM_MANAGE_FIXED_ASSETS')";
    private static final String RUN = "hasAuthority('PAGE_FIXED_ASSETS') and hasAuthority('PERM_RUN_DEPRECIATION')";
    private static final String DISPOSE = "hasAuthority('PAGE_FIXED_ASSETS') and hasAuthority('PERM_DISPOSE_ASSET')";
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MM/yyyy");

    private final FixedAssetService assets;
    private final JournalService journalService;
    private final DataChangeService dataChangeService;
    private final ActivityLogService activityLogService;
    private final Messages messages;
    private final NumberFormats num;

    public FixedAssetController(FixedAssetService assets, JournalService journalService, DataChangeService dataChangeService,
                                ActivityLogService activityLogService, Messages messages, NumberFormats num) {
        this.assets = assets;
        this.journalService = journalService;
        this.dataChangeService = dataChangeService;
        this.activityLogService = activityLogService;
        this.messages = messages;
        this.num = num;
    }

    @GetMapping
    @PreAuthorize(VIEW)
    public String list(@RequestParam(required = false) String search, @RequestParam(required = false) String status,
                       @RequestParam(required = false) String category, @RequestParam(defaultValue = "0") int page, Model model) {
        Page<FixedAsset> found = assets.findPage(search, status, category, Paging.page(page), Paging.SIZE);
        model.addAttribute("assets", found);
        model.addAttribute("accumulated", assets.accumulatedOf(found.getContent()));
        model.addAttribute("totals", assets.totals());
        model.addAttribute("costCentres", assets.costCentres());
        model.addAttribute("places", assets.placesById());
        model.addAttribute("statuses", FixedAssetStatus.values());
        model.addAttribute("categories", FixedAssetCategory.values());
        model.addAttribute("search", search);
        model.addAttribute("status", status);
        model.addAttribute("category", category);
        model.addAttribute("nextRun", assets.nextRun());
        model.addAttribute("paginationQuery", QueryString.of("search", search, "status", status, "category", category));
        return "fixed-assets/list";
    }

    @GetMapping("/new")
    @PreAuthorize(MANAGE)
    public String register(Model model) {
        return form(model, assets.newForm(), null);
    }

    @PostMapping("/new")
    @PreAuthorize(MANAGE)
    public String register(@Valid @ModelAttribute("assetDto") FixedAssetDto dto, BindingResult result, Model model, RedirectAttributes redirect) {
        return save(null, dto, result, model, redirect);
    }

    @GetMapping("/{id}")
    @PreAuthorize(VIEW)
    public String view(@PathVariable UUID id, @RequestParam(required = false) String tab, @RequestParam(defaultValue = "0") int page, Model model) {
        FixedAsset asset = assets.findDetailed(id);
        String open = "history".equals(tab) ? tab : "depreciation";
        model.addAttribute("asset", asset);
        model.addAttribute("row", assets.row(asset));
        model.addAttribute("bill", assets.billOf(asset).orElse(null));
        model.addAttribute("costCentre", asset.getCostCentreId() == null ? null : assets.costCentres().get(asset.getCostCentreId()));
        model.addAttribute("place", asset.getLocationId() == null ? null : assets.placesById().get(asset.getLocationId()));
        model.addAttribute("depreciated", assets.isDepreciated(asset));
        model.addAttribute("journals", assets.journals(asset));
        model.addAttribute("today", assets.today());
        model.addAttribute("lines", assets.depreciationOf(id, Paging.pageOf("depreciation", open, page), Paging.SIZE));
        model.addAttribute("history", dataChangeService.history("FixedAsset", id.toString(), Paging.pageOf("history", open, page), Paging.SIZE));
        model.addAttribute("tab", open);
        return "fixed-assets/view";
    }

    @GetMapping("/{id}/edit")
    @PreAuthorize(MANAGE)
    public String edit(@PathVariable UUID id, Model model) {
        FixedAsset asset = assets.findDetailed(id);
        return form(model, assets.formOf(asset), asset);
    }

    @PostMapping("/{id}/edit")
    @PreAuthorize(MANAGE)
    public String edit(@PathVariable UUID id, @Valid @ModelAttribute("assetDto") FixedAssetDto dto, BindingResult result, Model model,
                       RedirectAttributes redirect) {
        return save(id, dto, result, model, redirect);
    }

    @PostMapping("/{id}/dispose")
    @PreAuthorize(DISPOSE)
    public String dispose(@PathVariable UUID id, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate disposedOn,
                          @RequestParam(required = false) BigDecimal proceeds, @RequestParam(required = false) String method,
                          @RequestParam(required = false) String reference, @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        try {
            FixedAsset a = AuditContext.withReason(StringUtils.hasText(reason) ? reason.trim() : null,
                    () -> assets.dispose(id, new FixedAssetService.Disposal(disposedOn, proceeds, method, reference, reason)));
            activityLogService.record(MODULE, "DISPOSE_FIXED_ASSET", "Disposed of fixed asset " + a.getNumber() + " (" + a.getName() + ") on "
                    + a.getDisposedOn() + " for " + num.money(a.getProceeds()) + " RWF: " + a.getDisposalReason(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("asset.disposed", a.getNumber()));
        } catch (BusinessException e) {
            return failed(id, redirect, "DISPOSE_FIXED_ASSET", "dispose of", e);
        }
        return "redirect:/fixed-assets/" + id;
    }

    // ---------------------------------------------------------------- depreciation runs

    @GetMapping("/depreciation")
    @PreAuthorize(VIEW)
    public String runs(@RequestParam(required = false) String month, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "0") int ppage,
                       Model model) {
        YearMonth next = assets.nextRun();
        YearMonth chosen = parseMonth(month, next);
        FixedAssetService.Preview preview = assets.preview(chosen);
        model.addAttribute("runs", assets.runs(Paging.page(page), Paging.SIZE));
        model.addAttribute("preview", preview);
        model.addAttribute("previewLines", Paging.of(preview.lines(), Paging.page(ppage)));
        model.addAttribute("blockedText", preview.blockedKey() == null ? null : messages.get(preview.blockedKey(), preview.blockedArgs()));
        model.addAttribute("month", chosen);
        model.addAttribute("nextRun", next);
        model.addAttribute("firstRun", assets.firstRun().orElse(null));
        model.addAttribute("latest", assets.latestPosted().orElse(null));
        model.addAttribute("paginationQuery", QueryString.of("month", chosen.toString(), "ppage", ppage > 0 ? String.valueOf(ppage) : null));
        model.addAttribute("previewQuery", QueryString.of("month", chosen.toString(), "page", page > 0 ? String.valueOf(page) : null));
        return "fixed-assets/depreciation";
    }

    @PostMapping("/depreciation/run")
    @PreAuthorize(RUN)
    public String run(@RequestParam(required = false) String month, RedirectAttributes redirect) {
        YearMonth chosen = parseMonth(month, assets.nextRun());
        try {
            DepreciationRun run = assets.run(chosen);
            activityLogService.record(MODULE, "CREATE_DEPRECIATION_RUN", "Posted the depreciation of " + chosen.format(MONTH) + " (" + run.getNumber() + "): "
                    + run.getAssets() + " assets, " + num.money(run.getTotal()) + " RWF", ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("depreciation.posted", chosen.format(MONTH), num.money(run.getTotal())));
            return "redirect:/fixed-assets/depreciation/" + run.getId();
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "CREATE_DEPRECIATION_RUN", "Failed to post the depreciation of " + chosen.format(MONTH) + ": " + error,
                    ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
            return "redirect:/fixed-assets/depreciation?month=" + chosen;
        }
    }

    @GetMapping("/depreciation/{id}")
    @PreAuthorize(VIEW)
    public String runView(@PathVariable UUID id, @RequestParam(required = false) String tab, @RequestParam(defaultValue = "0") int page, Model model) {
        DepreciationRun run = assets.findRun(id);
        String open = "history".equals(tab) ? tab : "lines";
        List<com.ntaganira.heritier.iWarehouse.entity.JournalEntry> journals = new ArrayList<>();
        if (run.getJournalId() != null) {
            journals.add(journalService.findById(run.getJournalId()));
        }
        if (run.getReversalJournalId() != null) {
            journals.add(journalService.findById(run.getReversalJournalId()));
        }
        model.addAttribute("run", run);
        model.addAttribute("latest", assets.latestPosted().map(r -> r.getId().equals(run.getId())).orElse(false));
        model.addAttribute("journals", journals);
        model.addAttribute("lines", assets.runLines(id, Paging.pageOf("lines", open, page), Paging.SIZE));
        model.addAttribute("history", dataChangeService.history("DepreciationRun", id.toString(), Paging.pageOf("history", open, page), Paging.SIZE));
        model.addAttribute("tab", open);
        return "fixed-assets/run";
    }

    @PostMapping("/depreciation/{id}/reverse")
    @PreAuthorize(RUN)
    public String reverse(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        try {
            DepreciationRun run = AuditContext.withReason(StringUtils.hasText(reason) ? reason.trim() : null, () -> assets.reverse(id, reason));
            activityLogService.record(MODULE, "REVERSE_DEPRECIATION_RUN", "Reversed depreciation run " + run.getNumber() + ": " + run.getReversalReason(),
                    ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("depreciation.reversed", run.getNumber()));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "REVERSE_DEPRECIATION_RUN", "Failed to reverse depreciation run " + assets.findRun(id).getNumber() + ": " + error,
                    ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:/fixed-assets/depreciation/" + id;
    }

    // ---------------------------------------------------------------- helpers

    private String save(UUID id, FixedAssetDto dto, BindingResult result, Model model, RedirectAttributes redirect) {
        FixedAsset existing = id == null ? null : assets.findDetailed(id);
        if (result.hasErrors()) {
            model.addAttribute("formErrors", result.getFieldErrors());
            return form(model, dto, existing);
        }
        try {
            FixedAsset a = id == null ? assets.register(dto) : assets.update(id, dto);
            activityLogService.record(MODULE, id == null ? "CREATE_FIXED_ASSET" : "UPDATE_FIXED_ASSET", (id == null ? "Registered" : "Changed")
                    + " fixed asset " + a.getNumber() + " (" + a.getName() + "): cost " + num.money(a.getCost()) + " RWF, " + a.getUsefulLifeMonths()
                    + " months", ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("asset.saved", a.getNumber()));
            return "redirect:/fixed-assets/" + a.getId();
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, id == null ? "CREATE_FIXED_ASSET" : "UPDATE_FIXED_ASSET", "Failed to save a fixed asset: " + error,
                    ActivityStatus.FAILED);
            if (e.getField() != null) {
                result.rejectValue(e.getField(), e.getMessageKey(), e.getArgs(), error);
                model.addAttribute("formErrors", result.getFieldErrors());
            } else {
                model.addAttribute("flashError", error);
            }
            return form(model, dto, existing);
        }
    }

    private String form(Model model, FixedAssetDto dto, FixedAsset asset) {
        model.addAttribute("assetDto", dto);
        model.addAttribute("asset", asset);
        model.addAttribute("depreciated", asset != null && assets.isDepreciated(asset));
        model.addAttribute("categories", FixedAssetCategory.values());
        model.addAttribute("lifeOf", lives());
        model.addAttribute("costCentres", assets.activeCostCentres());
        model.addAttribute("places", assets.places());
        model.addAttribute("today", assets.today());
        return "fixed-assets/form";
    }

    private static Map<String, Integer> lives() {
        Map<String, Integer> lives = new java.util.LinkedHashMap<>();
        for (FixedAssetCategory c : FixedAssetCategory.values()) {
            lives.put(c.name(), c.lifeMonths());
        }
        return lives;
    }

    private static YearMonth parseMonth(String month, YearMonth fallback) {
        if (!StringUtils.hasText(month)) {
            return fallback;
        }
        try {
            return YearMonth.parse(month.trim());
        } catch (DateTimeParseException e) {
            return fallback;
        }
    }

    private String failed(UUID id, RedirectAttributes redirect, String action, String verb, BusinessException e) {
        String error = messages.get(e.getMessageKey(), e.getArgs());
        String number;
        try {
            number = assets.findDetailed(id).getNumber();
        } catch (RuntimeException ex) {
            number = id.toString();
        }
        activityLogService.record(MODULE, action, "Failed to " + verb + " fixed asset " + number + ": " + error, ActivityStatus.FAILED);
        redirect.addFlashAttribute("flashError", error);
        return "redirect:/fixed-assets/" + id;
    }
}
