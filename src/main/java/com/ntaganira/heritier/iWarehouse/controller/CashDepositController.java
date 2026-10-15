package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.entity.CashDeposit;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.enums.AttachmentOwner;
import com.ntaganira.heritier.iWarehouse.enums.JournalSource;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.AttachmentService;
import com.ntaganira.heritier.iWarehouse.service.CashDepositService;
import com.ntaganira.heritier.iWarehouse.service.DataChangeService;
import com.ntaganira.heritier.iWarehouse.service.JournalService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : CashDepositController.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : Takings banked (F-13, SDP 10): the deposits, the tills waiting to be banked, banking them with the slip,
 *               a deposit with its tills, journal, documents (the stamped slip) and History. PAGE_CASH_DEPOSITS +
 *               PERM_VIEW_CASH_DEPOSIT; banking PERM_BANK_CASH.
 * </pre>
 */
@Controller
@RequestMapping("/cash-deposits")
public class CashDepositController {

    static final String MODULE = "Cash Banking";
    private static final String VIEW = "hasAuthority('PAGE_CASH_DEPOSITS') and hasAuthority('PERM_VIEW_CASH_DEPOSIT')";
    private static final String BANK = "hasAuthority('PAGE_CASH_DEPOSITS') and hasAuthority('PERM_BANK_CASH')";

    private final CashDepositService depositService;
    private final JournalService journalService;
    private final AttachmentService attachmentService;
    private final DataChangeService dataChangeService;
    private final ActivityLogService activityLogService;
    private final Messages messages;

    public CashDepositController(CashDepositService depositService, JournalService journalService, AttachmentService attachmentService,
                                 DataChangeService dataChangeService, ActivityLogService activityLogService, Messages messages) {
        this.depositService = depositService;
        this.journalService = journalService;
        this.attachmentService = attachmentService;
        this.dataChangeService = dataChangeService;
        this.activityLogService = activityLogService;
        this.messages = messages;
    }

    @GetMapping
    @PreAuthorize(VIEW)
    public String list(@RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("deposits", depositService.findPage(Paging.page(page), Paging.SIZE));
        model.addAttribute("waiting", depositService.toBank());
        return "cash-deposits/list";
    }

    @GetMapping("/new")
    @PreAuthorize(BANK)
    public String form(Model model) {
        model.addAttribute("tills", depositService.toBank());
        model.addAttribute("verifyRequired", depositService.verifyRequired());
        if (!model.containsAttribute("chosen")) {
            model.addAttribute("chosen", new HashSet<UUID>());
        }
        return "cash-deposits/form";
    }

    @PostMapping("/new")
    @PreAuthorize(BANK)
    public String bank(@RequestParam(required = false) List<UUID> tillIds, @RequestParam(required = false) String slipNumber,
                       @RequestParam(required = false) BigDecimal amount, @RequestParam(required = false) String notes, Model model) {
        try {
            CashDeposit d = depositService.bank(new CashDepositService.Form(tillIds, slipNumber, amount, notes));
            activityLogService.record(MODULE, "CREATE_CASH_DEPOSIT", "Banked " + d.getAmount().toPlainString() + " RWF (" + d.getNumber()
                    + ", slip " + d.getSlipNumber() + ") from " + tillIds.size() + " till(s)", ActivityStatus.SUCCESS);
            return "redirect:/cash-deposits/" + d.getId() + "?banked";
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "CREATE_CASH_DEPOSIT", "Failed to bank the takings: " + error, ActivityStatus.FAILED);
            model.addAttribute("flashError", error);
            model.addAttribute("errorField", e.getField());
            model.addAttribute("chosen", tillIds == null ? new HashSet<UUID>() : new HashSet<>(tillIds));
            model.addAttribute("slipNumber", slipNumber);
            model.addAttribute("amount", amount == null ? null : amount.toPlainString());
            model.addAttribute("notes", notes);
            return form(model);
        }
    }

    @GetMapping("/{id}")
    @PreAuthorize(VIEW)
    public String view(@PathVariable UUID id, @RequestParam(defaultValue = "tills") String tab, @RequestParam(defaultValue = "0") int page,
                       @RequestParam(required = false) String banked, Model model) {
        CashDeposit d = depositService.findDetailed(id);
        String open = List.of("documents", "history").contains(tab) ? tab : "tills";
        model.addAttribute("deposit", d);
        model.addAttribute("tills", Paging.of(depositService.tillsOf(id), Paging.pageOf("tills", open, page)));
        model.addAttribute("journals", journalService.forSource(id, JournalSource.CASH_DEPOSIT));
        model.addAttribute("documents", attachmentService.page(AttachmentOwner.CASH_DEPOSIT, id, Paging.pageOf("documents", open, page), Paging.SIZE));
        model.addAttribute("history", dataChangeService.history("CashDeposit", id.toString(), Paging.pageOf("history", open, page), Paging.SIZE));
        model.addAttribute("tab", open);
        if (banked != null) {
            model.addAttribute("flashSuccess", messages.get("deposit.banked", d.getNumber()));
        }
        return "cash-deposits/view";
    }
}
