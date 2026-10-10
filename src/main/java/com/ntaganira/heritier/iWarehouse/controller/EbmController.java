package com.ntaganira.heritier.iWarehouse.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.entity.EbmDevice;
import com.ntaganira.heritier.iWarehouse.entity.EbmReceipt;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.service.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : EbmController.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : EBM signing (TAX-02, TAX-03): who signs (simulator or VSDC), the device, the settings still missing,
 *               the receipts waiting and how old the oldest is, every receipt with its attempts, request and answer.
 *               PAGE_EBM; initialising the device, retrying, correcting a purchase code and the simulator's outage
 *               PERM_MANAGE_EBM.
 * </pre>
 */
@Controller
@RequestMapping("/ebm")
public class EbmController {

    static final String MODULE = "EBM";

    private final EbmService ebmService;
    private final DataChangeService dataChangeService;
    private final ActivityLogService activityLogService;
    private final Messages messages;
    private final ObjectMapper mapper;

    public EbmController(EbmService ebmService, DataChangeService dataChangeService, ActivityLogService activityLogService,
                         Messages messages, ObjectMapper mapper) {
        this.mapper = mapper;
        this.ebmService = ebmService;
        this.dataChangeService = dataChangeService;
        this.activityLogService = activityLogService;
        this.messages = messages;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PAGE_EBM')")
    public String list(@RequestParam(required = false) String show, @RequestParam(required = false) String search,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("summary", ebmService.summary());
        model.addAttribute("receipts", ebmService.findPage(show, search, Paging.page(page), Paging.SIZE));
        model.addAttribute("show", show);
        model.addAttribute("search", search);
        model.addAttribute("paginationQuery", QueryString.of("show", show, "search", search));
        return "ebm/list";
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PAGE_EBM')")
    public String view(@PathVariable UUID id, @RequestParam(defaultValue = "0") int page, Model model) {
        EbmReceipt receipt = ebmService.find(id);
        model.addAttribute("receipt", receipt);
        model.addAttribute("qr", receipt.isSigned() ? Labels.qrSvg(ebmService.qrData(receipt)) : null);
        model.addAttribute("requestJson", pretty(receipt.getRequestJson()));
        model.addAttribute("responseJson", pretty(receipt.getResponseJson()));
        model.addAttribute("history", dataChangeService.history("EbmReceipt", id.toString(), Paging.page(page), Paging.SIZE));
        return "ebm/view";
    }

    /** JSON indented for reading; as it is when it is not JSON. */
    private String pretty(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(mapper.readTree(json));
        } catch (JsonProcessingException e) {
            return json;
        }
    }

    @PostMapping("/{id}/retry")
    @PreAuthorize("hasAuthority('PAGE_EBM') and hasAuthority('PERM_MANAGE_EBM')")
    public String retry(@PathVariable UUID id, RedirectAttributes redirect) {
        try {
            EbmReceipt receipt = ebmService.retry(id);
            EbmReceipt after = ebmService.find(id);
            activityLogService.record(MODULE, "RETRY_EBM_RECEIPT", "Sent the EBM receipt of " + receipt.getDocumentNumber()
                    + " (EBM no. " + receipt.getInvcNo() + ") again: " + (after.isSigned() ? "signed " + after.getReceiptLabel()
                    : "not signed, " + after.getResultCode()), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute(after.isSigned() ? "flashSuccess" : "flashError", after.isSigned()
                    ? messages.get("ebm.retried.signed", receipt.getDocumentNumber(), after.getReceiptLabel())
                    : messages.get("ebm.retried.waiting", receipt.getDocumentNumber()));
        } catch (BusinessException e) {
            fail(redirect, "RETRY_EBM_RECEIPT", "Failed to send an EBM receipt again", e);
        }
        return "redirect:/ebm/" + id;
    }

    @PostMapping("/retry")
    @PreAuthorize("hasAuthority('PAGE_EBM') and hasAuthority('PERM_MANAGE_EBM')")
    public String retryAll(RedirectAttributes redirect) {
        int count = ebmService.retryAll();
        activityLogService.record(MODULE, "RETRY_EBM_RECEIPT", "Made " + count + " unsigned EBM receipts due now", ActivityStatus.SUCCESS);
        redirect.addFlashAttribute("flashSuccess", messages.get("ebm.retriedAll", count));
        return "redirect:/ebm?show=unsigned";
    }

    @PostMapping("/{id}/purchase-code")
    @PreAuthorize("hasAuthority('PAGE_EBM') and hasAuthority('PERM_MANAGE_EBM')")
    public String purchaseCode(@PathVariable UUID id, @RequestParam(required = false) String purchaseCode, RedirectAttributes redirect) {
        try {
            EbmReceipt receipt = ebmService.changePurchaseCode(id, purchaseCode);
            activityLogService.record(MODULE, "UPDATE_EBM_RECEIPT", "Purchase code of " + receipt.getDocumentNumber() + " set to "
                    + (receipt.getPurchaseCode() == null ? "(none)" : receipt.getPurchaseCode()) + " and sent again", ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("ebm.purchaseCode.changed", receipt.getDocumentNumber()));
        } catch (BusinessException e) {
            fail(redirect, "UPDATE_EBM_RECEIPT", "Failed to change a purchase code", e);
        }
        return "redirect:/ebm/" + id;
    }

    @PostMapping("/device/init")
    @PreAuthorize("hasAuthority('PAGE_EBM') and hasAuthority('PERM_MANAGE_EBM')")
    public String initialise(RedirectAttributes redirect) {
        try {
            EbmDevice device = ebmService.initialise();
            activityLogService.record(MODULE, "INITIALISE_EBM_DEVICE", "Initialised EBM device " + device.getDeviceSerial() + " (TIN "
                    + device.getTin() + ", branch " + device.getBranchId() + (device.getSdcId() == null ? ", installed before"
                    : ", SDC " + device.getSdcId() + ", MRC " + device.getMrcNo()) + ")", ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get(device.isAlreadyInstalled() ? "ebm.device.installedBefore"
                    : "ebm.device.initialised", device.getDeviceSerial()));
        } catch (BusinessException e) {
            fail(redirect, "INITIALISE_EBM_DEVICE", "Failed to initialise the EBM device", e);
        }
        return "redirect:/ebm";
    }

    /** Switches the simulator's outage on or off, to show a sale completing while EBM is down (AT-09). */
    @PostMapping("/simulator/outage")
    @PreAuthorize("hasAuthority('PAGE_EBM') and hasAuthority('PERM_MANAGE_EBM')")
    public String outage(@RequestParam boolean down, RedirectAttributes redirect) {
        try {
            ebmService.simulateOutage(down);
            activityLogService.record(MODULE, "UPDATE_EBM_SIMULATOR", (down ? "Started" : "Ended") + " a simulated EBM outage",
                    ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get(down ? "ebm.outage.started" : "ebm.outage.ended"));
        } catch (BusinessException e) {
            fail(redirect, "UPDATE_EBM_SIMULATOR", "Failed to switch the simulated EBM outage", e);
        }
        return "redirect:/ebm";
    }

    private void fail(RedirectAttributes redirect, String action, String what, BusinessException e) {
        String error = messages.get(e.getMessageKey(), e.getArgs());
        activityLogService.record(MODULE, action, what + ": " + error, ActivityStatus.FAILED);
        redirect.addFlashAttribute("flashError", error);
    }
}
