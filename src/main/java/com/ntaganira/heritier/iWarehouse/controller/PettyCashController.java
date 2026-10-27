package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.audit.AuditContext;
import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.NumberFormats;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.dto.PettyCashFundDto;
import com.ntaganira.heritier.iWarehouse.entity.PettyCashCount;
import com.ntaganira.heritier.iWarehouse.entity.PettyCashFund;
import com.ntaganira.heritier.iWarehouse.entity.PettyCashReplenishment;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.enums.PettyCashSource;
import com.ntaganira.heritier.iWarehouse.enums.PettyCashVoucherStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.DataChangeService;
import com.ntaganira.heritier.iWarehouse.service.PettyCashService;
import com.ntaganira.heritier.iWarehouse.service.PettyCashVoucherService;
import jakarta.validation.Valid;
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
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : PettyCashController.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Petty cash (CMP 07): the funds with what each holds and the vouchers (waiting, all), a fund's page with its
 *               vouchers, top-ups, counts and history, and its actions: open (PERM_MANAGE_PETTY_CASH), change, top up
 *               (PERM_REPLENISH_PETTY_CASH), count (the custodian, PERM_RECORD_PETTY_CASH, or Finance by surprise,
 *               PERM_AUDIT_PETTY_CASH) and close. Each needs PAGE_PETTY_CASH.
 * </pre>
 */
@Controller
@RequestMapping("/petty-cash")
public class PettyCashController {

    static final String MODULE = "Expenditure";
    private static final String VIEW = "hasAuthority('PAGE_PETTY_CASH') and hasAuthority('PERM_VIEW_PETTY_CASH')";
    private static final String MANAGE = "hasAuthority('PAGE_PETTY_CASH') and hasAuthority('PERM_MANAGE_PETTY_CASH')";

    private final PettyCashService pettyCash;
    private final PettyCashVoucherService vouchers;
    private final DataChangeService dataChangeService;
    private final ActivityLogService activityLogService;
    private final Messages messages;
    private final NumberFormats num;

    public PettyCashController(PettyCashService pettyCash, PettyCashVoucherService vouchers, DataChangeService dataChangeService,
                               ActivityLogService activityLogService, Messages messages, NumberFormats num) {
        this.pettyCash = pettyCash;
        this.vouchers = vouchers;
        this.dataChangeService = dataChangeService;
        this.activityLogService = activityLogService;
        this.messages = messages;
        this.num = num;
    }

    @GetMapping
    @PreAuthorize(VIEW)
    public String list(@RequestParam(required = false) String search, @RequestParam(required = false) String status,
                       @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "0") int fpage, Model model) {
        model.addAttribute("fundPage", Paging.of(pettyCash.funds(), Paging.page(fpage)));
        model.addAttribute("vouchers", vouchers.findPage(search, status, Paging.page(page), Paging.SIZE));
        model.addAttribute("statuses", PettyCashVoucherStatus.values());
        model.addAttribute("search", search);
        model.addAttribute("status", status);
        model.addAttribute("waiting", vouchers.waitingCount());
        model.addAttribute("myFunds", vouchers.myFunds());
        model.addAttribute("paginationQuery", QueryString.of("search", search, "status", status, "fpage", fpage > 0 ? String.valueOf(fpage) : null));
        model.addAttribute("fundQuery", QueryString.of("search", search, "status", status, "page", page > 0 ? String.valueOf(page) : null));
        return "petty-cash/list";
    }

    @GetMapping("/funds/new")
    @PreAuthorize(MANAGE)
    public String open(Model model) {
        PettyCashFundDto dto = new PettyCashFundDto();
        dto.setImprest(pettyCash.maxFloat());
        return form(model, dto, null);
    }

    @PostMapping("/funds/new")
    @PreAuthorize(MANAGE)
    public String open(@Valid @ModelAttribute("fundDto") PettyCashFundDto dto, BindingResult result, Model model, RedirectAttributes redirect) {
        return save(null, dto, result, model, redirect);
    }

    @GetMapping("/funds/{id}/edit")
    @PreAuthorize(MANAGE)
    public String edit(@PathVariable UUID id, Model model) {
        PettyCashFund fund = pettyCash.findDetailed(id);
        return form(model, pettyCash.formOf(fund), fund);
    }

    @PostMapping("/funds/{id}/edit")
    @PreAuthorize(MANAGE)
    public String edit(@PathVariable UUID id, @Valid @ModelAttribute("fundDto") PettyCashFundDto dto, BindingResult result, Model model,
                       RedirectAttributes redirect) {
        return save(id, dto, result, model, redirect);
    }

    @GetMapping("/funds/{id}")
    @PreAuthorize(VIEW)
    public String view(@PathVariable UUID id, @RequestParam(required = false) String tab, @RequestParam(defaultValue = "0") int page, Model model) {
        PettyCashFund fund = pettyCash.findDetailed(id);
        String open = "movements".equals(tab) || "counts".equals(tab) || "history".equals(tab) ? tab : "vouchers";
        PettyCashService.FundRow row = pettyCash.row(fund);
        model.addAttribute("fund", fund);
        model.addAttribute("row", row);
        model.addAttribute("custodian", pettyCash.isCustodian(fund));
        model.addAttribute("topUp", com.ntaganira.heritier.iWarehouse.service.PettyCash.topUpToFloat(row.balance(), fund.getImprest()));
        model.addAttribute("sources", PettyCashSource.values());
        model.addAttribute("waitingVouchers", pettyCash.waitingVouchers(id));
        model.addAttribute("today", pettyCash.today());
        model.addAttribute("vouchers", pettyCash.vouchers(id, Paging.pageOf("vouchers", open, page), Paging.SIZE));
        model.addAttribute("movements", pettyCash.replenishments(id, Paging.pageOf("movements", open, page), Paging.SIZE));
        model.addAttribute("counts", pettyCash.counts(id, Paging.pageOf("counts", open, page), Paging.SIZE));
        model.addAttribute("history", dataChangeService.history("PettyCashFund", id.toString(), Paging.pageOf("history", open, page), Paging.SIZE));
        model.addAttribute("tab", open);
        return "petty-cash/fund";
    }

    @PostMapping("/funds/{id}/top-up")
    @PreAuthorize("hasAuthority('PAGE_PETTY_CASH') and hasAuthority('PERM_REPLENISH_PETTY_CASH')")
    public String topUp(@PathVariable UUID id, @RequestParam(required = false) BigDecimal amount, @RequestParam(required = false) PettyCashSource source,
                        @RequestParam(required = false) String reference,
                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate txnDate, RedirectAttributes redirect) {
        try {
            PettyCashReplenishment r = pettyCash.topUp(id, amount, source, reference, txnDate);
            activityLogService.record(MODULE, "CREATE_PETTY_CASH_TOP_UP", "Topped up petty cash " + r.getFund().getCode() + " by " + num.money(r.getAmount())
                    + " RWF from " + r.getSource() + " (" + r.getNumber() + ")", ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("pettyCash.toppedUp", r.getFund().getCode(), num.money(r.getAmount())));
            return "redirect:/petty-cash/funds/" + id + "?tab=movements";
        } catch (BusinessException e) {
            return failed(id, redirect, "CREATE_PETTY_CASH_TOP_UP", "top up", e);
        }
    }

    @PostMapping("/funds/{id}/count")
    @PreAuthorize("hasAuthority('PAGE_PETTY_CASH') and (hasAuthority('PERM_RECORD_PETTY_CASH') or hasAuthority('PERM_AUDIT_PETTY_CASH'))")
    public String count(@PathVariable UUID id, @RequestParam(required = false) BigDecimal counted, @RequestParam(required = false) String note,
                        RedirectAttributes redirect) {
        try {
            PettyCashCount c = pettyCash.count(id, counted, note);
            activityLogService.record(MODULE, "CREATE_PETTY_CASH_COUNT", "Counted petty cash " + c.getFund().getCode() + " (" + c.getKind() + "): "
                    + num.money(c.getCounted()) + " RWF, expected " + num.money(c.getExpected()) + (c.getDifference().signum() == 0 ? ""
                    : ", difference " + num.money(c.getDifference()) + ": " + c.getNote()), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute(c.getDifference().signum() == 0 ? "flashSuccess" : "flashError", c.getDifference().signum() == 0
                    ? messages.get("pettyCash.counted", c.getFund().getCode())
                    : messages.get("pettyCash.countedDifference", c.getFund().getCode(), num.money(c.getDifference())));
            return "redirect:/petty-cash/funds/" + id + "?tab=counts";
        } catch (BusinessException e) {
            return failed(id, redirect, "CREATE_PETTY_CASH_COUNT", "count", e);
        }
    }

    @PostMapping("/funds/{id}/close")
    @PreAuthorize(MANAGE)
    public String close(@PathVariable UUID id, @RequestParam(required = false) String reason, @RequestParam(required = false) PettyCashSource source,
                        @RequestParam(required = false) String reference, RedirectAttributes redirect) {
        try {
            PettyCashFund fund = AuditContext.withReason(StringUtils.hasText(reason) ? reason.trim() : null,
                    () -> pettyCash.close(id, reason, source, reference));
            activityLogService.record(MODULE, "CLOSE_PETTY_CASH", "Closed petty cash " + fund.getCode() + ": " + fund.getCloseReason(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("pettyCash.closedDone", fund.getCode()));
        } catch (BusinessException e) {
            return failed(id, redirect, "CLOSE_PETTY_CASH", "close", e);
        }
        return "redirect:/petty-cash/funds/" + id;
    }

    // ---------------------------------------------------------------- helpers

    private String save(UUID id, PettyCashFundDto dto, BindingResult result, Model model, RedirectAttributes redirect) {
        PettyCashFund existing = id == null ? null : pettyCash.findDetailed(id);
        if (result.hasErrors()) {
            model.addAttribute("formErrors", result.getFieldErrors());
            return form(model, dto, existing);
        }
        try {
            PettyCashFund fund = id == null ? pettyCash.open(dto) : pettyCash.update(id, dto);
            activityLogService.record(MODULE, id == null ? "CREATE_PETTY_CASH_FUND" : "UPDATE_PETTY_CASH_FUND", (id == null ? "Opened" : "Changed")
                    + " petty cash fund " + fund.getCode() + ": custodian " + fund.getCustodianName() + ", float " + num.money(fund.getImprest()) + " RWF",
                    ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("pettyCash.saved", fund.getCode()));
            return "redirect:/petty-cash/funds/" + fund.getId();
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, id == null ? "CREATE_PETTY_CASH_FUND" : "UPDATE_PETTY_CASH_FUND", "Failed to save a petty cash fund: " + error,
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

    private String form(Model model, PettyCashFundDto dto, PettyCashFund fund) {
        model.addAttribute("fundDto", dto);
        model.addAttribute("fund", fund);
        model.addAttribute("sites", pettyCash.freeSites());
        model.addAttribute("users", pettyCash.users());
        model.addAttribute("maxFloat", pettyCash.maxFloat());
        return "petty-cash/fund-form";
    }

    private String failed(UUID id, RedirectAttributes redirect, String action, String verb, BusinessException e) {
        String error = messages.get(e.getMessageKey(), e.getArgs());
        String code;
        try {
            code = pettyCash.findDetailed(id).getCode();
        } catch (RuntimeException ex) {
            code = id.toString();
        }
        activityLogService.record(MODULE, action, "Failed to " + verb + " petty cash " + code + ": " + error, ActivityStatus.FAILED);
        redirect.addFlashAttribute("flashError", error);
        return "redirect:/petty-cash/funds/" + id;
    }
}
